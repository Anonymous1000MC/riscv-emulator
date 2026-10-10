package com.emulator.riscv;

/**
 * Identity of the emulated machine.
 *
 * <p>This is the name the guest sees, not the name of the surrounding mod. The Fabric mod keeps its
 * own {@code riscv-emulator} id, because that is a Minecraft-facing identifier that worlds,
 * resource packs and config directories already depend on; renaming it would break all of them for
 * no benefit. The emulated machine, on the other hand, reports its own name in places where there
 * is no reason to say "Minecraft": the SBI base extension's implementation name, and the device
 * tree's {@code model} and first {@code compatible} string. Those say Theon.
 *
 * <p>Keeping the values here rather than inline means the name is changed in exactly one place.
 */
public final class Theon {

    /** What the machine calls itself, as reported over SBI and in the device tree. */
    public static final String NAME = "Theon";

    /**
     * Device tree {@code model} string. Must stay {@code <something>,riscv-virtio}-shaped: Linux
     * matches the root {@code compatible} property against {@code riscv-virtio} to pick its
     * platform setup, so that string is kept in the compatible list below.
     */
    public static final String MODEL = NAME + " RISC-V emulator";

    /**
     * Device tree root {@code compatible} values. {@code riscv-virtio} is what makes the guest
     * treat this as a {@code virt} machine; the leading string is this machine's own identity.
     */
    public static final String COMPATIBLE = "theon";
    public static final String COMPATIBLE_PLATFORM = "riscv-virtio";

    private Theon() {
    }
}