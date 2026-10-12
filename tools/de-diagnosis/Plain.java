import com.emulator.riscv.RiscVEngine;
import java.nio.file.*;

/**
 * Boots the desktop guest with no machine-level trap stub.
 *
 * <p>LinuxBootRunner parks a faulting guest in a self-loop, which hides everything the kernel
 * would have said about the fault. Letting the trap go to the kernel's own handler produces an
 * oops report with the faulting symbol, which is the only way to tell which instruction diverged.
 */
public class Plain {
    public static void main(String[] a) throws Exception {
        RiscVEngine.MachineConfig c = new RiscVEngine.MachineConfig(
                2048, 1024, 768, 200, 30, 10000, 64, 4, false, true,
                a[1], Path.of(a[0]), Path.of(a[2]), null);
        RiscVEngine e = new RiscVEngine(c);
        final StringBuilder sb = new StringBuilder();
        e.uart().setOutputSink(ch -> {
            if (ch == '\n') { System.out.println("| " + sb); sb.setLength(0); }
            else if (ch != '\r') sb.append(ch);
        });
        long deadline = System.nanoTime() + Long.parseLong(a[3]) * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                if (e.runForNanos(200_000_000L) == 0) break;
            } catch (Throwable t) {
                System.out.println("EXCEPTION " + t);
                break;
            }
        }
        System.out.println("done pc=0x" + Long.toHexString(e.cpu().pc()));
    }
}