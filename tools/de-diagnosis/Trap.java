import com.emulator.riscv.RiscVEngine;
import com.emulator.riscv.cpu.CsrFile;
import java.nio.file.*;

/**
 * Boots the graphical guest and captures the first machine-mode trap.
 *
 * <p>LinuxBootRunner parks a faulting guest in a self-loop stub, which throws away the PC that
 * actually faulted. This installs a stub that instead writes mepc, mcause and mtval into guest RAM
 * so the real faulting instruction can be looked up afterwards.
 */
public class Trap {
    static RiscVEngine E;
    private static final long STUB = 0x8010_0000L;

    public static void main(String[] a) throws Exception {
        try { run(a); } catch (Throwable t) {
            System.out.println("UNCAUGHT " + t);
            try {
                for (java.lang.reflect.Field f : t.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getName().equals("cause") || f.getName().equals("value")) {
                        System.out.println("TRAPFIELD " + f.getName() + " = " + f.get(t));
                    }
                }
            } catch (Throwable ignore) { }
            if (E != null) {
                try {
                    long pc = E.cpu().pc();
                    System.out.println("cpuPc=0x" + Long.toHexString(pc));
                    long cause = E.csr().read(CsrFile.MCAUSE);
                    System.out.println("TRAP mepc=0x" + Long.toHexString(pc) + " mcause=" + cause);
                    int insn = E.memory().read32(pc & ~3L);
                    System.out.println("insn=0x" + Integer.toHexString(insn)
                            + " op=0x" + Integer.toHexString(insn & 0x7F)
                            + " funct3=" + ((insn >>> 12) & 7)
                            + " funct7=0x" + Integer.toHexString((insn >>> 25) & 0x7F)
                            + " rd=x" + ((insn >>> 7) & 31));
                } catch (Throwable ignore) { }
            }
            java.util.Arrays.stream(t.getStackTrace()).limit(8)
                    .forEach(el -> System.out.println("   at " + el));
            for (java.lang.reflect.Field f : t.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                try { System.out.println("   field " + f.getName() + " = " + f.get(t)); }
                catch (Exception ignore) { }
            }
        }
    }

    static void reportTrap(RiscVEngine e, Throwable t) {
        decode(e, e.csr().read(CsrFile.MEPC), e.csr().read(CsrFile.MCAUSE));
    }

    static void decode(RiscVEngine e, long mepc, long mcause) {
        System.out.println("TRAP mepc=0x" + Long.toHexString(mepc)
                + " mcause=" + mcause
                + " cpuPc=0x" + Long.toHexString(e.cpu().pc()));
        int insn = e.memory().read32(mepc & ~3L);
        System.out.println("insn=0x" + Integer.toHexString(insn)
                + " op=0x" + Integer.toHexString(insn & 0x7F)
                + " funct3=" + ((insn >>> 12) & 7)
                + " funct7=0x" + Integer.toHexString((insn >>> 25) & 0x7F)
                + " rd=x" + ((insn >>> 7) & 31));
    }

    static void run(String[] a) throws Exception {
        RiscVEngine.MachineConfig c = new RiscVEngine.MachineConfig(
                2048, Integer.parseInt(a[4]), Integer.parseInt(a[5]), 200, 30, 10000, 64, 4, false, true,
                a[1], Path.of(a[0]), Path.of(a[2]), null);
        RiscVEngine e = new RiscVEngine(c);
        E = e;
        final StringBuilder sb = new StringBuilder();
        e.uart().setOutputSink(ch -> {
            if (ch == '\n') { System.out.println("| " + sb); sb.setLength(0); }
            else if (ch != '\r') sb.append(ch);
        });

        // csrr x5,mepc / csrr x6,mcause / csrr x7,mtval / lui x28,0x88
        // sd x5,0(x28) / sd x6,8(x28) / sd x7,16(x28) / j .
        int[] stub = {
                0x341002F3, 0x342023F3, 0x343023F3, 0x00088E37,
                0x005E3023, 0x006E3423, 0x007E3823, 0x0000006F,
        };
        // A self-loop: it parks the faulting guest without faulting again, and leaves mepc and
        // mcause in the CSRs to be read once the run is over.
        // csrw mie, x0 disables machine interrupts, then j . parks. Without clearing mie the
        // timer interrupt lands on the very next cycle and overwrites mepc with the stub's own
        // address, which is what made the first faulting PC unrecoverable.
        e.memory().write32(STUB, 0x30501073);
        e.memory().write32(STUB + 4, 0x0000006F);
        e.csr().rawWrite(CsrFile.MTVEC, STUB);

        long deadline = System.nanoTime() + Long.parseLong(a[3]) * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                if (e.runForNanos(200_000_000L) == 0) break;
            } catch (Throwable t) {
                reportTrap(e, t);
                break;
            }
        }

        long mepc = e.csr().read(CsrFile.MEPC);
        long mcause = e.csr().read(CsrFile.MCAUSE);
        decode(e, mepc, mcause);
        if (mepc == 0) {
            System.out.println("TRAP: none recorded (cpu pc=" + Long.toHexString(e.cpu().pc()) + ")");
            return;
        }
        System.out.println("TRAP mepc=0x" + Long.toHexString(mepc) + " mcause=0x" + Long.toHexString(mcause));
        int insn = e.memory().read32(mepc & ~3L);
        int op = insn & 0x7F;
        int funct3 = (insn >>> 12) & 7;
        int funct7 = (insn >>> 25) & 0x7F;
        System.out.println("insn=0x" + Integer.toHexString(insn)
                + " op=0x" + Integer.toHexString(op)
                + " funct3=" + funct3 + " funct7=0x" + Integer.toHexString(funct7)
                + " rd=x" + ((insn >>> 7) & 31));
    }
}
