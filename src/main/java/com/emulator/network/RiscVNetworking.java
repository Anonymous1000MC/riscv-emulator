package com.emulator.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import com.emulator.block.RiscVComputerBlockEntity;

/**
 * Framebuffer and control traffic between the server and the client screen.
 *
 * <p>A full 1080p frame is 8 MiB of raw pixels, which no packet pipeline should ever see. Three
 * things keep it that small: only the damaged rectangle is sent, the send rate is capped at
 * {@code framebuffer_fps_limit}, and the result is Deflate compressed before it hits the wire. A
 * desktop frame compresses by roughly an order of magnitude because most of it is flat colour.
 *
 * <p>Client to server traffic is tiny by comparison - keystrokes and mouse movement - and is not
 * compressed.
 */
public final class RiscVNetworking {

    private RiscVNetworking() {
    }

    public static final String MOD_ID = "riscv-emulator";

    /** Server to client: a rectangle of pixels. */
    public static final CustomPayload.Id<FramebufferSyncPayload> FRAMEBUFFER =
            new CustomPayload.Id<>(Identifier.of(MOD_ID, "framebuffer_sync"));

    /** Server to client: the screen should open. */
    public static final CustomPayload.Id<ScreenOpenPayload> SCREEN_OPEN =
            new CustomPayload.Id<>(Identifier.of(MOD_ID, "screen_open"));

    /** Server to client: the screen should close. */
    public static final CustomPayload.Id<ScreenClosePayload> SCREEN_CLOSE =
            new CustomPayload.Id<>(Identifier.of(MOD_ID, "screen_close"));

    /** Client to server: keyboard and mouse activity. */
    public static final CustomPayload.Id<InputPayload> INPUT =
            new CustomPayload.Id<>(Identifier.of(MOD_ID, "input"));

    // ---- payloads ------------------------------------------------------------------------------

    /**
     * One damaged rectangle of the framebuffer.
     *
     * @param x      left edge in pixels
     * @param y      top edge in pixels
     * @param width  rectangle width in pixels
     * @param height rectangle height in pixels
     * @param stride bytes per row of {@code data}
     * @param data   either the raw RGBA rows or a Deflate stream of them
     */
    public record FramebufferSyncPayload(CustomPayload.Id<FramebufferSyncPayload> type,
                                         int x, int y, int width, int height, int stride,
                                         boolean compressed, byte[] data) implements CustomPayload {

        @Override
        public CustomPayload.Id<FramebufferSyncPayload> getId() {
            return type;
        }


        public static final PacketCodec<RegistryByteBuf, FramebufferSyncPayload> CODEC =
                PacketCodec.of(FramebufferSyncPayload::write, FramebufferSyncPayload::decode);

        private static FramebufferSyncPayload decode(RegistryByteBuf buf) {
            int x = buf.readVarInt();
            int y = buf.readVarInt();
            int w = buf.readVarInt();
            int h = buf.readVarInt();
            int stride = buf.readVarInt();
            boolean compressed = buf.readBoolean();
            byte[] data = buf.readByteArray();
            return new FramebufferSyncPayload(FRAMEBUFFER, x, y, w, h, stride, compressed, data);
        }

        private void write(RegistryByteBuf buf) {
            buf.writeVarInt(x);
            buf.writeVarInt(y);
            buf.writeVarInt(width);
            buf.writeVarInt(height);
            buf.writeVarInt(stride);
            buf.writeBoolean(compressed);
            buf.writeByteArray(data);
        }

        /** Inflates and returns the pixels as a direct buffer ready for a texture upload. */
        public ByteBuffer decode() {
            if (!compressed) {
                ByteBuffer out = ByteBuffer.allocateDirect(data.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                out.put(data);
                out.flip();
                return out;
            }
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(data);
                ByteBuffer out = ByteBuffer.allocateDirect(height * stride)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                byte[] chunk = new byte[64 * 1024];
                while (!inflater.finished() && out.hasRemaining()) {
                    int n = inflater.inflate(chunk);
                    if (n == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) {
                            break;
                        }
                    }
                    out.put(chunk, 0, Math.min(n, out.remaining()));
                }
                out.flip();
                return out;
            } catch (java.util.zip.DataFormatException ex) {
                throw new IllegalStateException("corrupt framebuffer stream", ex);
            } finally {
                inflater.end();
            }
        }
    }

    /** Tells the client to open the machine's screen. */
    public record ScreenOpenPayload(CustomPayload.Id<ScreenOpenPayload> type, int width, int height,
                                    boolean crashed, String error) implements CustomPayload {

        @Override
        public CustomPayload.Id<ScreenOpenPayload> getId() {
            return type;
        }


        public static final PacketCodec<RegistryByteBuf, ScreenOpenPayload> CODEC =
                PacketCodec.of(ScreenOpenPayload::write, ScreenOpenPayload::decode);

        private static ScreenOpenPayload decode(RegistryByteBuf buf) {
            int width = buf.readVarInt();
            int height = buf.readVarInt();
            boolean crashed = buf.readBoolean();
            String error = buf.readBoolean() ? buf.readString() : null;
            return new ScreenOpenPayload(SCREEN_OPEN, width, height, crashed, error);
        }

        private void write(RegistryByteBuf buf) {
            buf.writeVarInt(width);
            buf.writeVarInt(height);
            buf.writeBoolean(crashed);
            buf.writeBoolean(error != null);
            if (error != null) {
                buf.writeString(error);
            }
        }
    }

    /** Tells the client the screen is no longer valid. */
    public record ScreenClosePayload(CustomPayload.Id<ScreenClosePayload> type)
            implements CustomPayload {

        @Override
        public CustomPayload.Id<ScreenClosePayload> getId() {
            return type;
        }


        public static final PacketCodec<RegistryByteBuf, ScreenClosePayload> CODEC =
                PacketCodec.of((payload, buf) -> { }, buf -> new ScreenClosePayload(SCREEN_CLOSE));
    }

    /**
     * A single input event from the client.
     *
     * @param kind  see {@link Kind}
     * @param code  key code, button or axis depending on {@code kind}
     * @param value press state or movement amount
     */
    public record InputPayload(CustomPayload.Id<InputPayload> type, int kind, int code, int value,
                               int extra) implements CustomPayload {

        @Override
        public CustomPayload.Id<InputPayload> getId() {
            return type;
        }


        public static final PacketCodec<RegistryByteBuf, InputPayload> CODEC =
                PacketCodec.of(InputPayload::write, InputPayload::decode);

        public static final int KIND_KEY = 0;
        public static final int KIND_MOUSE_MOVE = 1;
        public static final int KIND_MOUSE_BUTTON = 2;
        public static final int KIND_SCROLL = 3;
        public static final int KIND_TEXT = 4;

        private static InputPayload decode(RegistryByteBuf buf) {
            return new InputPayload(INPUT, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt());
        }

        private void write(RegistryByteBuf buf) {
            buf.writeVarInt(kind);
            buf.writeVarInt(code);
            buf.writeVarInt(value);
            buf.writeVarInt(extra);
        }
    }

    // ---- sending -------------------------------------------------------------------------------

    /** Sends one damaged rectangle to a player. */
    public static void sendFrame(ServerPlayerEntity player, int x, int y, int w, int h,
                                 ByteBuffer pixels, boolean compress) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(pixels.remaining());
        byte[] tmp = new byte[64 * 1024];
        while (pixels.hasRemaining()) {
            int n = Math.min(tmp.length, pixels.remaining());
            pixels.get(tmp, 0, n);
            raw.write(tmp, 0, n);
        }
        byte[] body = raw.toByteArray();
        if (compress) {
            byte[] deflated = deflate(body);
            if (deflated.length < body.length) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                        new FramebufferSyncPayload(FRAMEBUFFER, x, y, w, h, w * 4, true, deflated));
                return;
            }
        }
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new FramebufferSyncPayload(FRAMEBUFFER, x, y, w, h, w * 4, false, body));
    }

    public static void sendOpen(ServerPlayerEntity player, int width, int height, String error) {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new ScreenOpenPayload(SCREEN_OPEN, width, height, error != null, error));
    }

    public static void sendClose(ServerPlayerEntity player) {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new ScreenClosePayload(SCREEN_CLOSE));
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 8));
            byte[] chunk = new byte[64 * 1024];
            while (!deflater.finished()) {
                int n = deflater.deflate(chunk);
                if (n <= 0) {
                    break;
                }
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * Sends an input event to the server.
     *
     * <p>Registered as a no-op on the server side; the client entrypoint swaps in the real
     * implementation, which keeps this class free of any client-only imports.
     */
    private static java.util.function.Consumer<InputPayload> inputSender = payload -> { };

    /** Installed by the client entrypoint. */
    public static void setInputSender(java.util.function.Consumer<InputPayload> sender) {
        inputSender = sender == null ? payload -> { } : sender;
    }

    /** Client-side helper: forwards an input event to the server. */
    public static void sendInput(InputPayload payload) {
        inputSender.accept(payload);
    }

    /** Client-side helper: tells the server the screen is gone. */
    public static void sendScreenClosed() {
        inputSender.accept(new InputPayload(INPUT, 5, 0, 0, 0));
    }

    /** Applies one client input event to the guest's virtio-input device. */
    public static void applyInput(RiscVComputerBlockEntity computer, InputPayload payload) {
        if (computer == null || computer.engine() == null) {
            return;
        }
        var input = computer.engine().input();
        switch (payload.kind()) {
            case InputPayload.KIND_KEY -> input.pushKey(payload.code(), payload.value());
            case InputPayload.KIND_MOUSE_MOVE -> input.pushRelative(payload.code(), payload.value());
            case InputPayload.KIND_MOUSE_BUTTON -> input.pushKey(0x110 + payload.code(), payload.value());
            case InputPayload.KIND_SCROLL -> input.pushRelative(0x08, payload.value());
            case InputPayload.KIND_TEXT -> input.pushKey(payload.code(), 1);
            default -> {
            }
        }
    }
}