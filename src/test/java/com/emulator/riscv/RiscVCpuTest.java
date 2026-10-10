package com.emulator.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.emulator.riscv.cpu.CsrFile;
import com.emulator.riscv.cpu.RiscVCpu;
import com.emulator.riscv.cpu.TrapException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the RV64IMACU decoder, the register file and the trap paths.
 *
 * <p>Programs are hand-assembled with {@link Asm} and checked against the architectural result,
 * so a failure points at either a decode bug or a wrong expectation rather than at the machinery
 * used to build the test.
 */
class RiscVCpuTest {

    private TestMachine m;

    /** End of the most recently emitted program, used to stop execution at a program bound. */
    private long programEnd;

    /** Allocates a distinct staging slot per 64 bit constant; see {@link #constant64}. */
    private int scratchSlot;

    @BeforeEach
    void setUp() {
        m = new TestMachine();
        programEnd = TestMachine.CODE;
        scratchSlot = 0;
    }

    @AfterEach
    void tearDown() {
        m.close();
    }

    /** Emits a pointer to {@code address} into x5 followed by {@code tail}. */
    private void loadUsing(long address, int... tail) {
        int[] pointer = load64(5, address);
        int[] program = new int[pointer.length + tail.length];
        System.arraycopy(pointer, 0, program, 0, pointer.length);
        System.arraycopy(tail, 0, program, pointer.length, tail.length);
        Asm.emit(m.memory, TestMachine.CODE, program);
        programEnd = TestMachine.CODE + program.length * 4L;
    }

    /** Runs the pointer setup plus {@code steps} instructions of the body. */
    /**
     * Runs a straight-line snippet.
     *
     * <p>These programs are branch-free, so the exact instruction count depends on whether the
     * 64 bit pointer took two instructions or four and whether the body mixes 32 and 16 bit
     * encodings. Rather than hand-count, run a budget that comfortably exceeds any of them; running
     * past the end hits zeros, raises an illegal instruction, and leaves the registers the test is
     * interested in untouched.
     */
    private void runTail(int extraSteps) {
        m.cpu.runBlock(64 + extraSteps);
    }

    private void run(int steps) {
        m.cpu.runBlock(steps);
    }

    /**
     * Runs until the program counter reaches {@code target}.
     *
     * <p>Trap tests need this: the handler address is followed by zeros, so running past the trap
     * would immediately raise an illegal instruction and overwrite the cause that is being checked.
     */
    private void runUntil(long target, int budget) {
        for (int i = 0; i < budget && m.cpu.pc() != target; i++) {
            m.cpu.step();
        }
    }

    /** Concatenates instruction arrays. */
    private static int[] concat(int[]... parts) {
        int total = 0;
        for (int[] part : parts) {
            total += part.length;
        }
        int[] out = new int[total];
        int at = 0;
        for (int[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    /**
     * Two instructions that materialise {@code value} into {@code rd}.
     *
     * <p>{@code lui} sign-extends its 32 bit immediate, so the low twelve bits have to be sign
     * extended as well. Without that step the pair cannot express an address whose bit 31 is set,
     * which is every address in the 2 GiB RAM window.
     */
    private static int[] load64(int rd, long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("load64 is only used for 32 bit constants");
        }
        if (value <= 0x7FFF_FFFFL) {
            long low12 = (value << 52) >> 52;
            long hi = (value - low12) >> 12;
            return new int[] {
                    Asm.lui(rd, (int) hi),
                    Asm.addi(rd, rd, (int) low12)
            };
        }
        // With bit 31 set, lui and addi both sign-extend, so the 32 bit pattern has to be shifted
        // back down to make the result positive again - exactly what a real assembler's li does.
        return new int[] {
                Asm.lui(rd, (int) (value >>> 12)),
                Asm.addi(rd, rd, (int) ((value << 52) >> 52)),
                Asm.slli(rd, rd, 32),
                Asm.srli(rd, rd, 32)
        };
    }

    // ---- arithmetic ---------------------------------------------------------------------------

    @Test
    void arithmeticAndLogic() {
        m.load(
                Asm.addi(5, 0, 100),
                Asm.addi(6, 0, -30),
                Asm.add(7, 5, 6),
                Asm.sub(8, 5, 6),
                Asm.and(9, 5, 6),
                Asm.or(10, 5, 6),
                Asm.xor(11, 5, 6),
                Asm.slt(12, 6, 5),
                Asm.sltu(13, 6, 5),
                Asm.slli(14, 5, 4),
                Asm.srli(15, 5, 2),
                Asm.srai(16, 6, 2));
        run(12);
        assertEquals(100, m.reg(5));
        assertEquals(-30, m.reg(6));
        assertEquals(70, m.reg(7));
        assertEquals(130, m.reg(8));
        assertEquals(0x64 & 0xFFFFFFE2, m.reg(9));
        assertEquals(0x64 | 0xFFFFFFE2, m.reg(10));
        assertEquals(0x64 ^ 0xFFFFFFE2, m.reg(11));
        assertEquals(1, m.reg(12), "-30 < 100 signed");
        assertEquals(0, m.reg(13), "0xFFFF...FFE2 is not below 100 unsigned");
        assertEquals(1600, m.reg(14));
        assertEquals(25, m.reg(15));
        assertEquals(-8, m.reg(16), "arithmetic shift keeps the sign");
    }

    @Test
    void immediateForms() {
        m.load(
                Asm.addi(5, 0, -1),
                Asm.slti(6, 5, 0),
                Asm.sltiu(7, 5, 0),
                Asm.xori(8, 5, 0xFF),
                Asm.ori(9, 5, 0xF),
                Asm.andi(10, 5, 0xF),
                Asm.srli(11, 5, 60),
                Asm.srai(12, 5, 60));
        run(8);
        assertEquals(-1, m.reg(5));
        assertEquals(1, m.reg(6), "-1 < 0 signed");
        assertEquals(0, m.reg(7), "-1 is huge unsigned");
        assertEquals(-1 ^ 0xFF, m.reg(8));
        assertEquals(-1 | 0xF, m.reg(9));
        assertEquals(0xF, m.reg(10));
        assertEquals(15, m.reg(11), "logical shift fills with zeroes");
        assertEquals(-1, m.reg(12), "arithmetic shift fills with ones");
    }

    @Test
    void shiftAmountsUseTheLowSixBits() {
        m.load(
                Asm.addi(5, 0, 65),
                Asm.addi(6, 0, 65),   // 65 = 64 + 1: only the low six bits are used
                Asm.sll(7, 5, 6),
                Asm.srl(8, 5, 6),
                Asm.addi(9, 0, 64),   // exactly 64 shifts by zero
                Asm.sll(10, 5, 9));
        run(6);
        assertEquals(130, m.reg(7), "shift amount is taken modulo 64");
        assertEquals(32, m.reg(8), "65 >>> 1");
        assertEquals(65, m.reg(10), "a shift amount of 64 shifts by zero");
    }

    @Test
    void multiplyDivideIncludingEdgeCases() {
        m.load(
                Asm.addi(5, 0, 7),
                Asm.addi(6, 0, -3),
                Asm.mul(7, 5, 6),
                Asm.mulh(8, 5, 6),
                Asm.mulhu(9, 5, 6),
                Asm.div(10, 5, 6),
                Asm.rem(11, 5, 6),
                Asm.divu(12, 6, 5),
                Asm.remu(13, 6, 5));
        run(9);
        assertEquals(-21, m.reg(7));
        assertEquals(-1, m.reg(8), "mulh: the high half of -21 is all ones");
        assertEquals(6, m.reg(9), "mulhu: unsigned 7 * (2^64-3)");
        assertEquals(-2, m.reg(10));
        assertEquals(1, m.reg(11));
        assertEquals(0x2492492492492491L, m.reg(12), "unsigned 0xFFFF...FFE2 / 7");
        assertEquals(6, m.reg(13), "unsigned 0xFFFF...FFE2 % 7");
    }

    @Test()
    void signedAndUnsignedHighMultiplies() {
        // x5 = x6 = 2^32, whose square is exactly 2^64, so the high half is one.
        m.load(
                Asm.lui(5, 0x1),
                Asm.slli(5, 5, 20),
                Asm.lui(6, 0x1),
                Asm.slli(6, 6, 20),
                Asm.mulh(7, 5, 6),
                Asm.mulhu(8, 5, 6),
                Asm.mul(9, 5, 6));
        run(7);
        assertEquals(1, m.reg(7), "mulh");
        assertEquals(1, m.reg(8), "mulhu matches mulh for positive operands");
        assertEquals(0, m.reg(9), "the low half is 2^64, which is zero in 64 bits");
    }

    @Test
    void divideByZeroUsesTheSpecifiedResults() {
        m.load(
                Asm.addi(5, 0, 42),
                Asm.add(6, 0, 0),
                Asm.div(7, 5, 6),
                Asm.divu(8, 5, 6),
                Asm.rem(9, 5, 6),
                Asm.remu(10, 5, 6));
        run(6);
        assertEquals(-1, m.reg(7), "signed divide by zero yields -1");
        assertEquals(-1, m.reg(8), "unsigned divide by zero yields all ones");
        assertEquals(42, m.reg(9), "remainder by zero yields the dividend");
        assertEquals(42, m.reg(10));
    }

    @Test
    void overflowingDivisionDoesNotTrap() {
        m.load(
                // x5 = Long.MIN_VALUE, built by shifting so the sign survives.
                Asm.addi(5, 0, 1),
                Asm.slli(5, 5, 63),
                Asm.addi(6, 0, -1),
                Asm.div(7, 5, 6),
                Asm.rem(8, 5, 6));
        run(5);
        assertEquals(Long.MIN_VALUE, m.reg(7), "MIN/-1 wraps to MIN");
        assertEquals(0, m.reg(8));
    }

    @Test
    void wordWidthOperationsSignExtend() {
        m.load(
                Asm.lui(5, 0x80000),
                Asm.addi(6, 0, 1),
                Asm.addw(7, 5, 6),
                Asm.subw(8, 6, 5),
                Asm.sllw(9, 6, 5),
                Asm.srlw(10, 5, 6),
                Asm.sraw(11, 5, 6),
                Asm.addiw(12, 5, -1));
        run(8);
        assertEquals(0xFFFFFFFF80000001L, m.reg(7), "addw sign-extends its 32 bit result");
        assertEquals(0xFFFFFFFF80000001L, m.reg(8), "subw sign-extends too");
        assertEquals(1, m.reg(9), "sllw takes the shift amount from the low five bits, which are zero");
        assertEquals(0x40000000L, m.reg(10), "srlw treats the operand as unsigned 32 bit");
        assertEquals(0xFFFFFFFFC0000000L, m.reg(11), "sraw sign-extends");
        assertEquals(0x7FFFFFFFL, m.reg(12), "addiw wraps at 32 bits and sign-extends");
    }

    @Test
    void wordWidthShiftsIgnoreTheUpperHalf() {
        m.load(
                Asm.addi(5, 0, -1),
                Asm.slli(5, 5, 31),
                Asm.slliw(7, 5, 4),
                Asm.srliw(8, 5, 4),
                Asm.sraiw(9, 5, 4),
                Asm.slliw(10, 5, 32));
        run(6);
        // x5 = 0xFFFFFFFF80000000: a negative 32 bit value with a dirty upper half.
        assertEquals(0xFFFFFFFF80000000L, m.reg(5), "slli shifts the whole 64 bit register");
        assertEquals(0, m.reg(7), "slliw keeps only the low 32 bits, so bit 31 shifts out");
        assertEquals(0x08000000L, m.reg(8), "srliw shifts the low 32 bits, not the whole register");
        assertEquals(0xFFFFFFFFF8000000L, m.reg(9), "sraiw sign-extends before shifting");
        assertEquals(0, m.reg(10), "slliw discards the dirty upper half");
    }

    @Test
    void luiAndAuipc() {
        m.load(
                Asm.lui(5, 0xFFFFF),
                Asm.auipc(6, 0x1));
        run(2);
        assertEquals(0xFFFFFFFFFFFFF000L, m.reg(5), "lui sign-extends its 32 bit immediate");
        assertEquals(TestMachine.CODE + 4 + 0x1000, m.reg(6), "auipc is relative to its own pc");
    }

    // ---- memory ------------------------------------------------------------------------------

    @Test()
    void loadsAndStoresOfEveryWidth() {
        long address = TestMachine.DATA;
        loadUsing(address,
                Asm.addi(6, 0, 0x7F),
                Asm.sb(6, 5, 0),
                Asm.lbu(7, 5, 0),
                Asm.lb(8, 5, 0),
                // 0x1234 does not fit in a twelve bit immediate, so it needs lui plus addi.
                Asm.lui(9, 0x1),
                Asm.addi(9, 9, 0x234),
                Asm.sh(9, 5, 2),
                Asm.lhu(10, 5, 2),
                Asm.lh(11, 5, 2),
                Asm.lui(12, 0xDEADB),        // x12 = 0xDEADB000, bit 31 set
                Asm.sd(12, 5, 8),            // eight byte accesses need an aligned displacement
                Asm.lw(13, 5, 8),
                Asm.lwu(14, 5, 8),
                Asm.ld(15, 5, 8),
                Asm.ld(16, 5, 8));
        runTail(0);
        assertEquals(0x7F, m.reg(7));
        assertEquals(0x7F, m.reg(8), "0x7F is positive as a signed byte");
        assertEquals(0x1234, m.reg(10));
        assertEquals(0x1234, m.reg(11));
        // 0xDEADB000 has bit 31 set, so lw replicates it across the upper half and lwu does not.
        assertEquals(0xFFFFFFFFDEADB000L, m.reg(13), "lw sign-extends");
        assertEquals(0xDEADB000L, m.reg(14), "lwu zero-extends");
        assertEquals(0xFFFFFFFFDEADB000L, m.reg(15));
        assertEquals(0xFFFFFFFFDEADB000L, m.reg(16));
    }

    @Test
    void signExtensionOfNegativeByte() {
        long address = TestMachine.DATA;
        loadUsing(address,
                
                Asm.addi(6, 0, -1),
                Asm.sb(6, 5, 0),
                Asm.lb(7, 5, 0),
                Asm.lbu(8, 5, 0));
        runTail(0);
        assertEquals(-1, m.reg(7));
        assertEquals(255, m.reg(8));
    }

    @Test
    void signExtensionOfNegativeHalfword() {
        long address = TestMachine.DATA;
        loadUsing(address,
                
                Asm.addi(6, 0, -2),
                Asm.sh(6, 5, 0),
                Asm.lh(7, 5, 0),
                Asm.lhu(8, 5, 0));
        runTail(0);
        assertEquals(-2, m.reg(7));
        assertEquals(65534, m.reg(8));
    }

    // ---- control flow ------------------------------------------------------------------------

    @Test
    void branchesTakeAndFallThrough() {
        m.load(
                Asm.addi(5, 0, 1),
                Asm.addi(6, 0, 2),
                Asm.beq(5, 6, 12),
                Asm.addi(7, 0, 111),
                Asm.addi(8, 0, 222),
                Asm.addi(9, 0, 333),
                Asm.bne(5, 6, 8),
                Asm.addi(10, 0, 444));
        run(6);
        assertEquals(111, m.reg(7), "beq not taken falls through");
        assertEquals(222, m.reg(8), "the not-taken path keeps going");
        assertEquals(333, m.reg(9));
        assertEquals(0, m.reg(10), "bne taken, jumping over the trailing addi");
    }

    @Test
    void signedBranches() {
        m.load(
                Asm.addi(5, 0, -1),
                Asm.addi(6, 0, 1),
                Asm.blt(5, 6, 12),
                Asm.addi(7, 0, 111),
                Asm.addi(8, 0, 222),
                Asm.addi(9, 0, 333),
                Asm.bge(6, 5, 8),
                Asm.addi(10, 0, 444));
        run(6);
        assertEquals(0, m.reg(7), "-1 < 1 signed, so the branch is taken");
        assertEquals(0, m.reg(8));
        assertEquals(333, m.reg(9), "1 >= -1 signed");
        assertEquals(0, m.reg(10));
    }

    @Test
    void unsignedBranches() {
        m.load(
                Asm.lui(5, 0x80000),
                Asm.addi(6, 0, 1),
                Asm.bltu(6, 5, 12),
                Asm.addi(7, 0, 111),
                Asm.addi(8, 0, 222),
                Asm.addi(9, 0, 333),
                Asm.bgeu(5, 6, 8),
                Asm.addi(10, 0, 444));
        run(6);
        assertEquals(0, m.reg(7), "1 is below a huge value unsigned, so the branch is taken");
        assertEquals(0, m.reg(8));
        assertEquals(333, m.reg(9), "a huge value is at or above 1 unsigned, so also taken");
        assertEquals(0, m.reg(10));
    }

    @Test
    void backwardBranchesLoop() {
        // Decrement x1 from 10 to 0.
        m.load(
                Asm.addi(1, 0, 10),
                Asm.addi(1, 1, -1),
                Asm.bne(1, 0, -4));
        run(40);
        assertEquals(0, m.reg(1));
    }

    @Test()
    void jumpsAndLinkRegister() {
        m.load(
                Asm.addi(2, 0, 77),     // +0
                Asm.addi(3, 0, 88),     // +4
                Asm.jal(1, 12),         // +8   -> +20
                Asm.addi(4, 0, 111),    // +12  skipped
                Asm.addi(5, 0, 222),    // +16  skipped
                Asm.jalr(6, 1, 12),     // +20  -> CODE + 24
                Asm.addi(7, 0, 333),    // +24
                Asm.addi(8, 0, 444));   // +28
        run(6);
        assertEquals(77, m.reg(2));
        assertEquals(88, m.reg(3));
        assertEquals(TestMachine.CODE + 12, m.reg(1), "jal links to the next instruction");
        assertEquals(0, m.reg(4), "jumped over");
        assertEquals(0, m.reg(5), "jumped over");
        assertEquals(TestMachine.CODE + 24, m.reg(6), "jalr links to the next instruction");
        assertEquals(333, m.reg(7));
        assertEquals(444, m.reg(8));
    }

    @Test()
    void jalrClearsTheLowBitOfItsTarget() {
        // The target is deliberately odd: RISC-V only requires bit zero to be zero.
        int[] pointer = load64(5, TestMachine.CODE + 12 + 1);
        Asm.emit(m.memory, TestMachine.CODE, concat(pointer, new int[] { Asm.jalr(2, 5, 0) }));
        // Stop on the jalr: anything past it is zeros and would trap, moving the pc again.
        m.cpu.runBlock(pointer.length + 1);
        assertEquals(TestMachine.CODE + 12, m.cpu.pc(), "bit zero of the target is cleared");
    }

    @Test
    void zeroRegisterStaysZero() {
        m.load(
                Asm.addi(0, 0, 99),
                Asm.add(0, 5, 5),
                Asm.lui(0, 0x12345),
                Asm.addi(5, 0, 3),
                Asm.addi(1, 0, 1));
        run(5);
        assertEquals(0, m.reg(0), "x0 is hardwired to zero");
        assertEquals(3, m.reg(5), "execution continued past the writes to x0");
        assertEquals(1, m.reg(1));
    }

    // ---- atomics -----------------------------------------------------------------------------

    @Test
    void atomicLoadReservedStoreConditional() {
        long address = TestMachine.DATA;
        loadUsing(address,
                
                Asm.addi(6, 0, 5),
                Asm.sd(6, 5, 0),
                Asm.lrD(7, 5),
                Asm.addi(8, 0, 9),
                Asm.scD(9, 5, 8),
                Asm.ld(10, 5, 0));
        runTail(0);
        assertEquals(5, m.reg(7), "lr returns the loaded value");
        assertEquals(0, m.reg(9), "sc succeeds after a matching lr");
        assertEquals(9, m.reg(10));

        // A second sc with no outstanding reservation must fail and leave memory alone.
        m.loadAt(TestMachine.CODE,
                Asm.addi(8, 0, 11),
                Asm.scD(9, 5, 8),
                Asm.addi(1, 0, 1));
        m.cpu.setPc(TestMachine.CODE);
        m.cpu.runBlock(3);
        assertEquals(1, m.reg(9), "sc fails without a reservation");
    }

    @Test
    void aSecondStoreConditionalFailsAfterTheFirst() {
        loadUsing(TestMachine.DATA,
                Asm.addi(6, 0, 5),
                Asm.sd(6, 5, 0),
                Asm.lrD(7, 5),
                Asm.addi(8, 0, 9),
                Asm.scD(9, 5, 8),
                Asm.scD(10, 5, 8),
                Asm.ld(11, 5, 0));
        runTail(0);
        assertEquals(0, m.reg(9), "the first sc succeeds");
        assertEquals(1, m.reg(10), "the second fails, the reservation having been consumed");
        assertEquals(9, m.reg(11));
    }

    @Test
    void anOrdinaryStoreToTheReservedAddressBreaksTheReservation() {
        // Anything writing into the reserved granule retires the reservation, so a following
        // store-conditional has to report failure.
        loadUsing(TestMachine.DATA,
                Asm.addi(6, 0, 5),
                Asm.sd(6, 5, 0),
                Asm.lrD(7, 5),
                Asm.sd(0, 5, 0),
                Asm.addi(8, 0, 9),
                Asm.scD(9, 5, 8));
        runTail(0);
        assertEquals(1, m.reg(9), "sc fails after an intervening store to the same granule");
    }

    @Test
    void atomicReadModifyWrite() {
        long address = TestMachine.DATA;
        loadUsing(address,
                
                Asm.addi(6, 0, 10),
                Asm.sd(6, 5, 0),
                Asm.addi(7, 0, 3),
                Asm.amoaddD(8, 5, 7),
                Asm.ld(9, 5, 0));
        runTail(0);
        assertEquals(10, m.reg(8), "amoadd returns the original value");
        assertEquals(13, m.reg(9));
    }

    @Test
    void atomicSwapAndLogic() {
        long address = TestMachine.DATA;
        loadUsing(address,
                
                Asm.addi(6, 0, 0x0F),
                Asm.sd(6, 5, 0),
                Asm.addi(7, 0, 0xF0),
                Asm.amoswapD(8, 5, 7),
                Asm.ld(9, 5, 0),
                Asm.addi(7, 0, 0xF0),
                Asm.amoandD(10, 5, 7),
                Asm.ld(11, 5, 0));
        runTail(0);
        assertEquals(0x0F, m.reg(8), "amoswap returns the original");
        assertEquals(0xF0, m.reg(9));
        assertEquals(0xF0, m.reg(10));
        assertEquals(0xF0 & 0xF0, m.reg(11));
    }

    @Test
    void atomicMinAndMax() {
        long address = TestMachine.DATA;
        loadUsing(address,
                
                Asm.addi(6, 0, 7),
                Asm.sd(6, 5, 0),
                Asm.addi(7, 0, 9),
                Asm.amomaxD(8, 5, 7),
                Asm.ld(9, 5, 0),
                Asm.addi(7, 0, 1),
                Asm.amomaxuD(10, 5, 7),
                Asm.ld(11, 5, 0));
        runTail(0);
        assertEquals(7, m.reg(8), "amomax returns the original");
        assertEquals(9, m.reg(9));
        assertEquals(9, m.reg(10));
        assertEquals(9, m.reg(11));
    }

    // ---- compressed ---------------------------------------------------------------------------

    @Test()
    void compressedInstructions() {
        Asm.emit16(m.memory, TestMachine.CODE, new int[] {
                Asm.cLi(5, 3),
                Asm.cLi(6, 4),
                Asm.cMv(7, 6),
                Asm.cAdd(5, 5)
        });
        run(4);
        // All four instructions retire before anything is asserted, so only the final value of
        // each register is observable. c.add with rd == rs2 accumulates, which is how the
        // hardware disambiguates it from c.mv.
        assertEquals(6, m.reg(5), "3 doubled by c.add");
        assertEquals(4, m.reg(6));
        assertEquals(4, m.reg(7), "c.mv copies the source");
    }

    @Test()
    void compressedLuiAndAddi() {
        Asm.emit16(m.memory, TestMachine.CODE, new int[] {
                Asm.cLui(9, 0x2),        // x9 = 0x2000
                Asm.cAddi(9, -16)        // x9 = 0x1FF0
        });
        run(2);
        assertEquals(0x1FF0, m.reg(9));
    }

    @Test
    void compressedArithmeticShiftKeepsTheSign() {
        Asm.emit16(m.memory, TestMachine.CODE, new int[] {
                Asm.cLui(9, 0x2),
                Asm.cAddi(9, -16),
                Asm.cSrai(9, 4)          // 0x1FF0 >> 4
        });
        run(3);
        assertEquals(0x1FF, m.reg(9), "c.srai is an arithmetic shift");
    }

    @Test
    void compressedLogicalShiftAndAndi() {
        // c.li carries a six bit signed immediate, so the operand has to stay inside -32..31.
        Asm.emit16(m.memory, TestMachine.CODE, new int[] {
                Asm.cLi(8, 25),
                Asm.cSrli(8, 1),         // 12
                Asm.cSlli(8, 1),         // 24
                Asm.cAndi(8, 0xF)        // 8
        });
        run(4);
        assertEquals(8, m.reg(8));
    }

    @Test
    void compressedRegisterForms() {
        Asm.emit16(m.memory, TestMachine.CODE, new int[] {
                // c.li carries a six bit signed immediate, so both operands stay inside -32..31.
                Asm.cLi(8, 24),
                Asm.cLi(9, 3),
                Asm.cSub(8, 9),
                Asm.cXor(8, 9),
                Asm.cOr(8, 9),
                Asm.cAnd(8, 9)
        });
        run(6);
        // 24 = 0b011000, 3 = 0b000011
        // sub -> 0b010101, xor -> 0b010110, or -> 0b010111, and -> 0b000011
        assertEquals(0b000011, m.reg(8), "chained sub/xor/or/and");
    }

    @Test
    void compressedStackPointerAdjust() {
        int[] base = load64(2, 1000);
        m.load(base[0], base[1]);
        Asm.emit16(m.memory, TestMachine.CODE + 8, new int[] {
                Asm.cAddi16sp(32),
                Asm.cAddi16sp(-16)
        });
        run(4);
        assertEquals(1016, m.reg(2), "c.addi16sp applied twice");
    }

    @Test
    void compressedLoadStoreThroughStack() {
        // c.swsp / c.lwsp are sp relative word accesses. There is deliberately no 64 bit integer
        // compressed load or store: in RV64 those encodings are c.fsdsp and c.fldsp, which move
        // floating point registers. The doubleword round trip therefore uses the full width forms.
        int[] sp = load64(2, TestMachine.DATA + 0x10000);
        int[] value = concat(new int[] { Asm.lui(6, 0x1), Asm.addi(6, 6, 0xE1) }, sp);
        Asm.emit(m.memory, TestMachine.CODE, value);
        Asm.emit16(m.memory, TestMachine.CODE + value.length * 4, new int[] {
                Asm.cSwsp(6, 0),
                Asm.cLwsp(10, 0)
        });
        Asm.emit(m.memory, TestMachine.CODE + value.length * 4 + 4, new int[] {
                Asm.sd(6, 2, 0),
                Asm.ld(11, 2, 0)
        });
        run(12);
        assertEquals(4321, m.reg(10), "c.swsp then c.lwsp round trip");
        assertEquals(4321, m.reg(11), "sd then ld round trip through the same slot");
    }

    // ---- floating point --------------------------------------------------------------------------

    /** Turns the FP unit on; mstatus.FS starts at 0 (off) out of reset. */
    private void enableFloatingPoint() {
        long status = m.csr.mstatus();
        m.csr.rawWrite(CsrFile.MSTATUS, status | (1L << CsrFile.MSTATUS_FS_SHIFT));
    }

    /**
     * Materialises an arbitrary 64 bit constant.
     *
     * <p>{@link #load64} deliberately refuses anything that is not an address, because the
     * lui/addi/slli/srli sequence only ever builds a zero extended 32 bit value. A double bit
     * pattern needs the full 64 bits, so it is staged in memory and loaded from there.
     */
    private int[] constant64(int rd, long value) {
        // A fresh slot per call: these writes happen on the host while the program is being built,
        // so reusing one address would leave every load reading whichever value was written last.
        long scratch = TestMachine.DATA + 0x20000 + (scratchSlot++) * 64L;
        m.loadAt(scratch, (int) value, (int) (value >>> 32));
        return concat(load64(rd, scratch), new int[] { Asm.ld(rd, rd, 0) });
    }

    /**
     * Loads a double constant into an f register.
     *
     * <p>The constant goes through an integer register because {@code lui} cannot build 64 bits, and
     * then {@code fmv.d.x} moves it across. Both steps matter: the FP instructions read the f file,
     * so a value left sitting in an x register is simply not there.
     */
    private int[] loadFpDouble(int fp, double value) {
        final int scratch = 31;
        return concat(constant64(scratch, Double.doubleToRawLongBits(value)),
                new int[] { Asm.fmvdx(fp, scratch) });
    }

    /** Writes a single precision value into RAM as one little endian word. */
    private void storeSingle(long address, float value) {
        m.loadAt(address, Float.floatToRawIntBits(value));
    }

    /** Writes a double into RAM as two little endian words. */
    private void storeDouble(long address, double value) {
        long bits = Double.doubleToRawLongBits(value);
        m.loadAt(address, (int) bits, (int) (bits >>> 32));
    }

    @Test
    void floatingPointIsRefusedWhileTheUnitIsOff() {
        m.load(Asm.fld(1, 0, 0));
        m.cpu.setPc(TestMachine.CODE);
        m.cpu.step();
        assertEquals(2, m.csr.mcause(), "an FP instruction with mstatus.FS = 0 is illegal");
        assertEquals(0, m.cpu.fpState(), "and the unit stays off");
    }

    @Test
    void fldLoadsADoubleAndMarksTheUnitDirty() {
        storeDouble(TestMachine.DATA, 3.25d);
        enableFloatingPoint();
        int[] pointer = load64(5, TestMachine.DATA);
        Asm.emit(m.memory, TestMachine.CODE, concat(pointer, new int[] { Asm.fld(1, 5, 0) }));
        m.cpu.setPc(TestMachine.CODE);
        m.cpu.runBlock(pointer.length + 1);

        assertEquals(Double.doubleToRawLongBits(3.25d), m.cpu.f[1], "fld loaded the raw pattern");
        assertEquals(3, m.cpu.fpState(), "writing an f register makes mstatus.FS dirty");
        assertTrue((m.csr.mstatus() >>> CsrFile.MSTATUS_SD & 1) != 0, "mstatus.SD follows");
    }

    @Test
    void cFldspReadsTheStackWithoutTrapping() {
        // This is the instruction the kernel faulted on before F was implemented: the encoding at
        // entry+0xdc is c.ldsp, which has no integer meaning in RV64, only a floating point one.
        //
        // The 32 bit prefix and the compressed pair are emitted separately on purpose. Asm.emit
        // writes one int per four bytes, so a 16 bit instruction handed to it lands in the low half
        // of a word with zeros above and the hart then fetches the empty high half as its next
        // instruction. emit16 packs two halves per word, which is what gives the pair its four byte
        // home and lets the two byte program counter walk through it.
        enableFloatingPoint();
        Asm.emit(m.memory, TestMachine.CODE, new int[] {
                // sp = RAM_BASE, clear of the program. Every RAM address is above 0x7fffffff, so
                // lui sets bit 31 and sign extends; the shifts make it positive again.
                Asm.lui(2, 0x80000),
                Asm.slli(2, 2, 32),
                Asm.srli(2, 2, 32),
                Asm.addi(3, 0, 7),
                Asm.fcvtdw(1, 3)            // f1 = 7.0
        });
        Asm.emit16(m.memory, TestMachine.CODE + 20, new int[] {
                Asm.cFsdsp(1, 0),           // store f1 through sp
                Asm.cFldsp(4, 0)            // and read it back into f4
        });
        m.cpu.setPc(TestMachine.CODE);
        m.cpu.runBlock(7);

        assertEquals(7.0d, Double.longBitsToDouble(m.cpu.f[1]), "fcvt.d.w produced 7.0");
        assertEquals(7.0d, Double.longBitsToDouble(m.cpu.f[4]),
                "c.fsdsp then c.fldsp round trip");
        assertNotEquals(2, m.csr.mcause(),
                "c.fldsp did not raise an illegal instruction, which is what stopped the kernel");
    }

    @Test
    void doubleArithmeticProducesTheExpectedValues() {
        enableFloatingPoint();
        // fmv.d.x copies all 64 bits, so each constant is materialised as a full double word.
        Asm.emit(m.memory, TestMachine.CODE, concat(
                loadFpDouble(3, 1.0d),
                loadFpDouble(4, 2.0d),
                loadFpDouble(5, 16.0d),
                new int[] { Asm.faddd(10, 3, 4), Asm.fsubd(11, 5, 3), Asm.fmuld(12, 4, 5),
                        Asm.fdivd(13, 5, 4), Asm.fsqrtd(14, 5) }));
        m.cpu.setPc(TestMachine.CODE);
        m.cpu.runBlock(26);

        assertEquals(3.0d, Double.longBitsToDouble(m.cpu.f[10]), "fadd.d");
        assertEquals(15.0d, Double.longBitsToDouble(m.cpu.f[11]), "fsub.d");
        assertEquals(32.0d, Double.longBitsToDouble(m.cpu.f[12]), "fmul.d");
        assertEquals(8.0d, Double.longBitsToDouble(m.cpu.f[13]), "fdiv.d");
        assertEquals(4.0d, Double.longBitsToDouble(m.cpu.f[14]), "fsqrt.d");
        assertEquals(0, m.csr.fflags(), "exact operations raise no exception flags");
    }

    @Test
    void singlePrecisionIsNaNBoxed() {
        enableFloatingPoint();
        storeSingle(TestMachine.DATA, 1.5f);
        int[] pointer = load64(5, TestMachine.DATA);
        Asm.emit(m.memory, TestMachine.CODE, concat(pointer,
                new int[] { Asm.flw(3, 5, 0), Asm.fadds(10, 3, 3) }));
        m.cpu.setPc(TestMachine.CODE);
        m.cpu.runBlock(pointer.length + 2);

        long bits = m.cpu.f[10];
        assertEquals(0xFFFF_FFFFL, bits >>> 32, "the upper half is all ones, as NaN boxing requires");
        assertEquals(Float.floatToRawIntBits(3.0f), (int) bits, "and the low half is the value");
    }

    @Test
    void divisionByZeroRaisesDivideByZeroAndReturnsASignedZero() {
        enableFloatingPoint();
        Asm.emit(m.memory, TestMachine.CODE, concat(
                constant64(3, Double.doubleToRawLongBits(1.0d)),
                load64(4, Double.doubleToRawLongBits(0.0d)),
                new int[] { Asm.fdivd(10, 3, 4) }));
        m.cpu.setPc(TestMachine.CODE);
        m.cpu.runBlock(10);

        assertEquals(Double.doubleToRawLongBits(Double.POSITIVE_INFINITY),
                m.cpu.f[10], "1.0 / 0.0 is positive infinity");
        assertTrue((m.csr.fflags() & RiscVCpu.FFLAG_DZ) != 0, "divide by zero is flagged");
    }

    @Test
    void conversionsRoundTripThroughTheFloatingPointFile() {
        enableFloatingPoint();
        m.load(
                Asm.addi(3, 0, -1234),
                Asm.fcvtdw(10, 3),            // FCVT.D.W from x3
                Asm.fcvtdl(11, 10),           // FCVT.L.D back to a long
                Asm.fmvxd(12, 10));           // FMV.X.D reads the raw bits
        m.cpu.setPc(TestMachine.CODE);
        m.cpu.runBlock(4);

        assertEquals(-1234.0d, Double.longBitsToDouble(m.cpu.f[10]), "int to double");
        assertEquals(-1234L, m.reg(11), "double to long truncates back to the original");
        assertEquals(Double.doubleToRawLongBits(-1234.0d), m.reg(12), "fmv.x.d reads raw bits");
    }

    @Test
    void fcsrIsTheContainerForFflagsAndFrm() {
        assertEquals(0x003, CsrFile.FCSR);
        assertEquals(0x001, CsrFile.FFLAGS);
        assertEquals(0x002, CsrFile.FRM);

        m.csr.write(CsrFile.FCSR, 0x2D);
        assertEquals(0x0D, m.csr.read(CsrFile.FFLAGS), "fflags is the low five bits of fcsr");
        assertEquals(1, m.csr.read(CsrFile.FRM), "frm is bits 7:5 of fcsr");

        m.csr.write(CsrFile.FFLAGS, 0x1F);
        assertEquals(0x1F, m.csr.read(CsrFile.FFLAGS), "writing fflags updates fcsr in place");
        assertEquals(1, m.csr.frm(), "and leaves frm alone");
    }

    @Test()
    void compressedPrimeRegisterLoadStore() {
        // c.sw / c.lw name their operands through the prime-register fields, which can only
        // reach x8..x15, so both operands have to live there.
        int[] value = load64(14, 4321);
        int[] base = load64(9, TestMachine.DATA);
        Asm.emit(m.memory, TestMachine.CODE, concat(value, base));
        Asm.emit16(m.memory, TestMachine.CODE + 24, new int[] {
                Asm.cSw(14, 9, 0),
                Asm.cLw(15, 9, 0)
        });
        run(8);
        assertEquals(4321, m.reg(15), "c.sw and c.lw round trip through a prime register");
    }

    @Test()
    void compressedBranchesAndJumps() {
        Asm.emit16(m.memory, TestMachine.CODE, new int[] {
                Asm.cLi(5, 0),        // +0
                Asm.cJ(4),            // +2  -> +6, skipping the next instruction
                Asm.cLi(5, 99),       // +4  skipped
                Asm.cLi(6, 7),        // +6
                Asm.cBeqz(5, 4),      // +8  -> +12, skipping one more
                Asm.cLi(7, 88),       // +10 skipped
                Asm.cLi(8, 1)         // +12
        });
        run(5);
        assertEquals(0, m.reg(5), "c.j skipped the next instruction");
        assertEquals(7, m.reg(6));
        assertEquals(0, m.reg(7), "c.beqz was taken and skipped the instruction after it");
        assertEquals(1, m.reg(8));
    }

    @Test()
    void compressedBranchNotTakenFallsThrough() {
        Asm.emit16(m.memory, TestMachine.CODE, new int[] {
                Asm.cLi(8, 1),         // +0
                Asm.cBeqz(8, 6),       // +2  not taken, x8 is non-zero
                Asm.cLi(9, 7),         // +4
                Asm.cLi(10, 8),        // +6
                Asm.cBnez(8, 4),       // +8  taken, -> +12
                Asm.cLi(11, 9),        // +10 skipped
                Asm.cLi(12, 5)         // +12
        });
        run(6);
        assertEquals(7, m.reg(9), "c.beqz not taken falls through");
        assertEquals(8, m.reg(10));
        assertEquals(0, m.reg(11), "c.bnez was taken");
        assertEquals(5, m.reg(12));
    }

    @Test()
    void compressedJumpRegister() {
        // CODE + 20 is the third compressed instruction below, so the pointer needs the four
        // instruction form of li and the compressed part starts at CODE + 16.
        int[] target = load64(3, TestMachine.CODE + 20);
        Asm.emit(m.memory, TestMachine.CODE, target);
        Asm.emit16(m.memory, TestMachine.CODE + 16, new int[] {
                Asm.cJr(3),              // +16
                Asm.cLi(5, 99),         // +18 skipped
                Asm.cLi(6, 20)          // +20
        });
        run(6);
        assertEquals(20, m.reg(6), "c.jr reached the target");
        assertEquals(0, m.reg(5), "the instruction between was skipped");
    }

    @Test
    void compressedEbreakTraps() {
        long handler = TestMachine.CODE + 0x500;
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        Asm.emit16(m.memory, TestMachine.CODE, new int[] { Asm.cEbreak() });
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(handler, m.cpu.pc());
        // C.EBREAK raises a breakpoint exception, exactly like the uncompressed EBREAK: it is not
        // an illegal instruction. This previously expected 2 and passed only because fetch()
        // returned the wrong instruction word for it.
        assertEquals(3, m.csr.mcause());
    }

    @Test
    void arithmeticShiftImmediateRejectsBit26() {
        long handler = TestMachine.CODE + 0x380;
        // SRAI has funct6 010000: bit 30 selects it, but bits 31 and 29:26 are still reserved.
        // Bit 26 was previously left unchecked, so this encoding was wrongly accepted.
        m.load(Asm.i((1 << 6) | 8, 5, 5, 6, 0x13));
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(handler, m.cpu.pc(), "SRAI with bit 26 set must be rejected");
        assertEquals(2, m.csr.mcause());
    }

    @Test
    void instructionStraddlingAPageBoundaryIsFetchedCorrectly() {
        // Put a 32-bit instruction so its first halfword sits in one page and its second in the
        // next. Fetching all eight bytes from the first page's physical address would return the
        // wrong upper half; the halves have to be translated independently.
        long page = TestMachine.CODE & ~0xFFFL;
        long straddle = page + 0xFFC;
        int word = Asm.addi(6, 0, 42);
        m.memory.write16(straddle, (short) (word & 0xFFFF));
        m.memory.write16(straddle + 2, (short) (word >>> 16));
        m.csr.rawWrite(CsrFile.MTVEC, TestMachine.CODE + 0x400);
        m.cpu.setPc(straddle);
        run(1);
        assertEquals(42, m.reg(6), "the upper halfword must come from the next page");
    }


    @Test
    void fetchSwitchesBetweenInstructionWidths() {
        Asm.emit16(m.memory, TestMachine.CODE, new int[] {
                Asm.cLi(5, 3),
                Asm.cLi(6, 4)
        });
        Asm.emit(m.memory, TestMachine.CODE + 4, new int[] {
                Asm.add(7, 5, 6),
                Asm.addi(8, 0, 77)
        });
        run(4);
        assertEquals(7, m.reg(7), "the 32 bit instruction after a compressed pair executed");
        assertEquals(77, m.reg(8));
    }

    // ---- traps and CSRs -------------------------------------------------------------------------

    @Test
    void ecallFromSupervisorTraps() {
        long handler = TestMachine.CODE + 0x200;
        m.load(Asm.ecall());
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(handler, m.cpu.pc(), "ecall goes to the machine trap vector");
        assertEquals(3, m.cpu.privilege(),
                "with nothing delegated an S-mode ecall is taken in M-mode");
        assertEquals(9, m.csr.mcause(), "environment call from S-mode is cause 9");
        assertEquals(TestMachine.CODE, m.csr.mepc());
    }

    @Test
    void ebreakTraps() {
        long handler = TestMachine.CODE + 0x200;
        m.load(Asm.ebreak());
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(handler, m.cpu.pc());
        assertEquals(3, m.csr.mcause());
    }

    @Test
    void delegatedTrapGoesToSupervisor() {
        long handler = TestMachine.CODE + 0x300;
        m.load(Asm.ecall());
        m.csr.rawWrite(CsrFile.MTVEC, 0L);
        m.csr.write(CsrFile.STVEC, handler);
        m.csr.setMedeleg(1L << 9);
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(handler, m.cpu.pc(), "a delegated ecall is taken in S-mode");
        assertEquals(1, m.cpu.privilege());
        assertEquals(9, m.csr.scause());
        assertEquals(TestMachine.CODE, m.csr.sepc());
    }

    @Test
    void vectoredModeUsesTheInterruptCause() {
        long handler = TestMachine.CODE + 0x300;
        m.csr.rawWrite(CsrFile.MTVEC, handler | 1L);
        m.load(Asm.ecall());
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(handler, m.cpu.pc(),
                "an exception in vectored mode still goes to the base address");
    }

    @Test
    void mretReturnsToSupervisor() {
        long handler = TestMachine.CODE + 0x200;
        m.load(Asm.ecall());
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        // A handler advances mepc past the ecall itself, exactly as a firmware trap epilogue does.
        m.loadAt(handler,
                Asm.addi(1, 0, 5),
                Asm.csrrs(2, CsrFile.MEPC, 0),
                Asm.addi(2, 2, 4),
                Asm.csrrw(0, CsrFile.MEPC, 2),
                Asm.mret());
        run(5);
        assertEquals(1, m.cpu.privilege(), "mret returns to the privilege saved in MPP");
        assertEquals(5, m.reg(1));
        assertEquals(TestMachine.CODE + 4, m.cpu.pc(), "mret resumes after the ecall");
    }

    @Test
    void sretReturnsFromSupervisor() {
        // A delegated trap lands in S-mode, so the vector it takes must come from stvec; mtvec is
        // the machine vector and the two no longer share storage.
        long handler = TestMachine.CODE + 0x300;
        m.csr.rawWrite(CsrFile.STVEC, handler);
        m.csr.setMedeleg(1L << 9);
        m.load(Asm.ecall());
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(1, m.cpu.privilege(), "the delegated ecall landed in S-mode");

        m.loadAt(handler, Asm.addi(1, 0, 9), Asm.sret());
        run(2);
        assertEquals(TestMachine.CODE, m.cpu.pc(), "sret returns to sepc, which still holds the ecall");
    }

    @Test
    void illegalInstructionReportsItsEncoding() {
        long handler = TestMachine.CODE + 0x300;
        m.load(0x0000_007F);
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(handler, m.cpu.pc());
        assertEquals(2, m.csr.mcause(), "illegal instruction is cause 2");
        assertEquals(0x7F, m.csr.mtval(), "the encoding is reported in mtval");
    }

    @Test
    void illegalShiftImmediateIsRejected() {
        long handler = TestMachine.CODE + 0x300;
        // SLLI with bits 25..31 set is not a legal RV64 shift immediate.
        m.load(Asm.i(0x40, 5, 1, 6, 0x13));
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(handler, m.cpu.pc());
        assertEquals(2, m.csr.mcause());
    }

    @Test()
    void misalignedLoadTraps() {
        long handler = TestMachine.CODE + 0x400;
        loadUsing(TestMachine.DATA, Asm.lw(6, 5, 1));
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        runUntil(handler, 32);
        assertEquals(handler, m.cpu.pc());
        assertEquals(4, m.csr.mcause(), "load address misaligned is cause 4");
        assertEquals(TestMachine.DATA + 1, m.csr.mtval());
        assertEquals(3, m.cpu.privilege(), "taken in M-mode when nothing is delegated");
    }

    @Test()
    void misalignedStoreTraps() {
        long handler = TestMachine.CODE + 0x400;
        loadUsing(TestMachine.DATA, Asm.sw(0, 5, 1));
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        // Stop at the handler: the memory beyond it is zeros, and running on would overwrite
        // mcause with a later illegal instruction.
        runUntil(handler, 32);
        assertEquals(handler, m.cpu.pc());
        assertEquals(6, m.csr.mcause(), "store address misaligned is cause 6");
        assertEquals(TestMachine.DATA + 1, m.csr.mtval());
    }

    @Test
    void misalignedInstructionAddressTraps() {
        long handler = TestMachine.CODE + 0x400;
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE + 1);
        run(1);
        assertEquals(handler, m.cpu.pc());
        assertEquals(0, m.csr.mcause(), "instruction address misaligned is cause 0");
        assertEquals(TestMachine.CODE + 1, m.csr.mtval());
    }

    @Test
    void accessFaultOnUnmappedAddress() {
        long handler = TestMachine.CODE + 0x400;
        loadUsing(0x10, Asm.ld(6, 5, 0));
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        runTail(0);
        assertEquals(handler, m.cpu.pc());
        assertEquals(5, m.csr.mcause(), "load access fault is cause 5");
    }

    @Test
    void pmpDeniesSupervisorAccessWhenNoEntryIsConfigured() {
        long handler = TestMachine.CODE + 0x400;
        m.csr.clearPmp();
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(handler, m.cpu.pc(), "instruction fetch itself faults with no PMP entries");
        assertEquals(1, m.csr.mcause(), "instruction access fault is cause 1");
    }

    // ---- CSRs -----------------------------------------------------------------------------------

    /** Machine-level CSRs are unreadable from S-mode, so these tests switch the hart first. */
    private void enterMachineMode() {
        m.cpu.setPrivilege(3);
    }

    @Test
    void sepcHasItsOwnSlot() {
        // Only sstatus, sie and sip are views. sepc, stvec and sscratch are registers in their own
        // right: folding sepc onto mepc gave the trap path a slot it shares with the machine one,
        // and the two wrote over each other.
        enterMachineMode();
        m.csr.setMepc(0x1234);
        assertEquals(0x1234, m.csr.mepc(), "mepc round-trips");
        assertEquals(0, m.csr.read(CsrFile.SEPC), "sepc does not mirror mepc");
        m.csr.write(CsrFile.SEPC, 0xABCD);
        assertEquals(0xABCD, m.csr.read(CsrFile.SEPC), "sepc holds what was written");
        assertEquals(0x1234, m.csr.mepc(), "and mepc is unchanged by it");
    }

    @Test
    void misaIsWarlWithHardwiredMxl() {
        enterMachineMode();
        long misa = m.csr.read(CsrFile.MISA);
        assertEquals(2L, (misa >>> 62) & 3, "MXL must report the widest supported XLEN");
        assertNotEquals(0, misa & (1L << 2), "the C extension is implemented");
        // CSRRCI misa, C must clear only that bit: MXL and every other extension survive.
        m.load(Asm.csrrci(0, CsrFile.MISA, 1 << 2));
        run(1);
        assertEquals(2L, (m.csr.read(CsrFile.MISA) >>> 62) & 3, "MXL is hardwired");
        assertEquals(0, m.csr.read(CsrFile.MISA) & (1L << 2), "the C bit is cleared");
        assertEquals(0xA112DL & ~(1 << 2), m.csr.read(CsrFile.MISA) & 0x7FFFFFFFFFFFL,
                "no other extension bit changed");
    }

    @Test
    void mretMasksMepcBitOneWhenCompressedIsDisabled() {
        enterMachineMode();
        // With C present a 2-byte aligned target is legal and only bit 0 is masked.
        m.csr.setMepc(0x1002);
        m.load(Asm.jalr(0, 0, 0));           // a placeholder so run(1) has something to execute
        m.csr.setMstatus(m.csr.mstatus() | (3L << CsrFile.MSTATUS_MPP_SHIFT));
        m.load(0x30200073);                  // mret
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(0x1002, m.cpu.pc(), "mret keeps mepc[1] when C is enabled");

        // Disable C: IALIGN becomes 32 and mepc[1] must be masked away.
        m.csr.write(CsrFile.MISA, m.csr.read(CsrFile.MISA) & ~(1L << 2));
        m.csr.setMepc(0x1002);
        m.load(0x30200073);
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(0x1000, m.cpu.pc(), "mret masks mepc[1] when IALIGN is 32");
    }

    @Test
    void csrInstructions() {
        enterMachineMode();
        m.load(
                Asm.lui(5, 0x140),
                Asm.csrrw(6, CsrFile.MSCRATCH, 5),
                Asm.csrrs(7, CsrFile.MSCRATCH, 0),
                Asm.addi(8, 0, 8),
                Asm.csrrs(7, CsrFile.MSCRATCH, 8),
                Asm.csrrs(9, CsrFile.MSCRATCH, 0),
                Asm.csrrc(10, CsrFile.MSCRATCH, 8),
                Asm.csrrs(11, CsrFile.MSCRATCH, 0));
        run(8);
        assertEquals(0, m.reg(6), "csrrw returns the old value, which starts at zero");
        assertEquals(0x140008, m.reg(9), "csrrs set the requested bits");
        assertEquals(0x140000, m.reg(11), "csrrc cleared them again");
    }

    @Test
    void csrImmediateForms() {
        enterMachineMode();
        m.load(
                Asm.csrrwi(5, CsrFile.MSCRATCH, 9),
                Asm.csrrs(6, CsrFile.MSCRATCH, 0),
                Asm.csrrsi(7, CsrFile.MSCRATCH, 1),
                Asm.csrrsi(8, CsrFile.MSCRATCH, 0),
                Asm.csrrci(9, CsrFile.MSCRATCH, 1),
                Asm.csrrsi(10, CsrFile.MSCRATCH, 0));
        run(6);
        assertEquals(9, m.reg(6));
        assertEquals(9, m.reg(8), "csrrsi with a zero field performs no write");
        assertEquals(8, m.reg(10), "csrrci clears one bit");
    }

    @Test
    void csrAddressesAreTheArchitecturalOnes() {
        // Asserted against literal addresses on purpose. The supervisor block was once modelled as
        // a contiguous 0x100..0x106 range, and every test in this file still passed, because they
        // all reached the addresses through the CsrFile constants: a wrong constant is self
        // consistent. Linux enters secondary_start_sbi by clearing sie then sip, so the wrong
        // numbers raised illegal-instruction on its second instruction and, with stvec still zero,
        // parked the hart at address 0 before any console output. Pinning the literals catches the
        // whole class.
        // Supervisor, RV64 privileged spec volume II.
        assertEquals(0x100, CsrFile.SSTATUS, "sstatus");
        assertEquals(0x104, CsrFile.SIE, "sie");
        assertEquals(0x105, CsrFile.STVEC, "stvec");
        assertEquals(0x106, CsrFile.SCNTREN, "scounteren");
        assertEquals(0x10A, CsrFile.SENV_CFG, "senvcfg");
        assertEquals(0x140, CsrFile.SSCRATCH, "sscratch");
        assertEquals(0x141, CsrFile.SEPC, "sepc");
        assertEquals(0x142, CsrFile.SCAUSE, "scause");
        assertEquals(0x143, CsrFile.STVAL, "stval");
        assertEquals(0x144, CsrFile.SIP, "sip");
        assertEquals(0x180, CsrFile.SATP, "satp");
        // Machine.
        assertEquals(0x300, CsrFile.MSTATUS, "mstatus");
        assertEquals(0x301, CsrFile.MISA, "misa");
        assertEquals(0x302, CsrFile.MEDELEG, "medeleg");
        assertEquals(0x303, CsrFile.MIDELEG, "mideleg");
        assertEquals(0x304, CsrFile.MIE, "mie");
        assertEquals(0x305, CsrFile.MTVEC, "mtvec");
        assertEquals(0x306, CsrFile.MCOUNTEREN, "mcounteren");
        assertEquals(0x310, CsrFile.MSTATUSH, "mstatush");
        assertEquals(0x340, CsrFile.MSCRATCH, "mscratch");
        assertEquals(0x341, CsrFile.MEPC, "mepc");
        assertEquals(0x342, CsrFile.MCAUSE, "mcause");
        assertEquals(0x343, CsrFile.MTVAL, "mtval");
        assertEquals(0x344, CsrFile.MIP, "mip");
        // Counters, unprivileged and machine.
        assertEquals(0xC00, CsrFile.CYCLE, "cycle");
        assertEquals(0xC01, CsrFile.TIME, "time");
        assertEquals(0xC02, CsrFile.INSTRET, "instret");
        assertEquals(0xC80, CsrFile.CYCLEH, "cycleh");
        assertEquals(0xC81, CsrFile.TIMEH, "timeh");
        assertEquals(0xC82, CsrFile.INSTRETH, "instreth");
        assertEquals(0xB00, CsrFile.MCYCLE, "mcycle");
        assertEquals(0xB02, CsrFile.MINSTRET, "minstret");
        assertEquals(0xB80, CsrFile.MCYCLEH, "mcycleh");
        assertEquals(0xB82, CsrFile.MINSTRETH, "minstreth");
        // Identification.
        assertEquals(0xF11, CsrFile.MVENDORID, "mvendorid");
        assertEquals(0xF12, CsrFile.MARCHID, "marchid");
        assertEquals(0xF13, CsrFile.MIMPID, "mimpid");
        assertEquals(0xF14, CsrFile.MHARTID, "mhartid");
    }

    @Test
    void supervisorCsrsRespondAtTheirLiteralAddresses() {
        // The literal table above only pins the constants. This proves the emulator implements the
        // architectural address *and* that it is backed by the right machine CSR, so a correct
        // constant wired to the wrong register cannot pass. sscratch is checked from S-mode (that
        // is how Linux reaches it) and the backing store is then read from M-mode, because the
        // machine CSRs above 0x300 are not readable from S-mode.
        m.cpu.setPrivilege(1);
        long handler = TestMachine.CODE + 0x400;
        m.load(
                Asm.i(0x140, 8, 1, 0, 0x73),    // csrw sscratch, s0
                Asm.addi(11, 0, 11));            // must still be reachable
        m.cpu.reg(8, 0x2A);
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        run(2);
        assertNotEquals(handler, m.cpu.pc(),
                "csrw sscratch at literal 0x140 from S-mode must not trap");
        assertEquals(11, m.reg(11), "execution continued past the literal-address access");

        // sscratch is its own register, so a write to it must not appear in mscratch either: a
        // guest that saves its tp there must not overwrite the machine one, and vice versa.
        enterMachineMode();
        m.load(
                Asm.i(0x340, 0, 2, 9, 0x73),    // csrr s1, mscratch
                Asm.i(0x343, 0, 2, 10, 0x73));  // csrr a0, stval
        m.cpu.setPc(TestMachine.CODE);
        run(2);
        assertEquals(0, m.reg(9), "sscratch (0x140) is not backed by mscratch (0x340)");
        assertEquals(0, m.reg(10), "sscratch must not be backed by stval (0x343)");
    }

    @Test
    void clearingSieAndSipFromSupervisorModeDoesNotTrap() {
        m.cpu.setPrivilege(1);
        long handler = TestMachine.CODE + 0x400;
        m.load(
                Asm.csrrw(0, CsrFile.SIE, 0),   // exactly what head.S does first
                Asm.csrrw(0, CsrFile.SIP, 0),   // and second
                Asm.addi(10, 0, 7));
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        run(3);
        assertNotEquals(handler, m.cpu.pc(),
                "csrw sie then csrw sip from S-mode must not raise illegal-instruction");
        assertEquals(7, m.reg(10), "both CSR writes were accepted and execution continued");
    }

    @Test
    void csrrwiWithAZeroImmediateStillWrites() {
        enterMachineMode();
        m.csr.rawWrite(CsrFile.MSCRATCH, 0x1234);
        m.load(
                Asm.csrrwi(5, CsrFile.MSCRATCH, 0),
                Asm.csrrs(6, CsrFile.MSCRATCH, 0));
        run(2);
        assertEquals(0x1234, m.reg(5), "csrrwi returns the old value");
        assertEquals(0, m.reg(6),
                "csrrwi must write zimm even when zimm is zero; the rs1!=0 guard suppressed it,"
                        + " which left satp enabled and trapped Linux on the next instruction fetch");
    }

    @Test
    void cSdspStoresAnIntegerRegisterWithoutFloatingPoint() {
        // C.SDSP (funct3=111, quadrant 2) is an integer store; C.FSDSP (funct3=101) is the FP one.
        // C.SDSP used to call requireFp and write f[rs2]. head.S runs "csrc sstatus, SR_FS"
        // immediately, clearing FS, so every C.SDSP then trapped and the hart parked in
        // .Lsecondary_park deep inside the SBI handoff.
        Asm.emit(m.memory, TestMachine.CODE, new int[] {
                Asm.lui(2, 0x80000),
                Asm.slli(2, 2, 32),
                Asm.srli(2, 2, 32),          // sp = RAM_BASE
                Asm.addi(8, 0, 42),          // s0 = 42
                Asm.addi(9, 2, 64)           // t0 = sp + 64
        });
        Asm.emit16(m.memory, TestMachine.CODE + 20, new int[] {
                Asm.cSdsp(8, 64),            // store s0 through sp
                Asm.cAddi(0, 0)              // c.nop, so the pair occupies a whole word
        });
        Asm.emit(m.memory, TestMachine.CODE + 24, new int[] {
                Asm.ld(10, 9, 0)             // t0 = [sp + 64]
        });
        m.cpu.setPc(TestMachine.CODE);
        m.cpu.runBlock(8);
        assertEquals(42, m.reg(10), "c.sdsp stored the integer register through sp");
        assertNotEquals(2, m.csr.mcause(),
                "c.sdsp must not require floating point; requireFp trapped once head.S cleared FS");
    }

    @Test
    void writingAReadOnlyCsrIsIllegal() {
        enterMachineMode();
        long handler = TestMachine.CODE + 0x400;
        m.load(Asm.i(CsrFile.CYCLE, 5, 1, 6, 0x73)); // csrrw x6, cycle, x5
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(handler, m.cpu.pc());
        assertEquals(2, m.csr.mcause(), "writing a read-only CSR is an illegal instruction");
    }

    @Test
    void accessingAnUnimplementedCsrIsIllegal() {
        enterMachineMode();
        long handler = TestMachine.CODE + 0x400;
        m.load(Asm.csrrs(5, 0x7C0, 0));
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(handler, m.cpu.pc());
        assertEquals(2, m.csr.mcause());
    }

    @Test
    void misaAdvertisesTheImplementedExtensions() {
        enterMachineMode();
        m.load(Asm.csrrs(5, CsrFile.MISA, 0));
        run(1);
        long misa = m.reg(5);
        // The extension bit positions are fixed by the specification and are not in alphabetical
        // order: A is 0, C is 2, I is 8, M is 12, S is 17, U is 19, V is 20. An earlier version of
        // this test read S as bit 18 and U as bit 20, which happened to agree with a mistyped
        // MISA_VALUE and so passed while advertising the wrong extensions.
        assertEquals(2, misa >>> 62, "MXL is 2 for XLEN 64");
        assertTrue((misa & (1L << 0)) != 0, "A");
        assertTrue((misa & (1L << 2)) != 0, "C");
        assertTrue((misa & (1L << 8)) != 0, "I");
        assertTrue((misa & (1L << 12)) != 0, "M");
        assertTrue((misa & (1L << 17)) != 0, "S");
        assertTrue((misa & (1L << 19)) != 0, "U");
        // Claiming an extension the decoder does not implement makes the guest emit instructions
        // that trap as illegal, so these must stay clear.
        assertTrue((misa & (1L << 5)) != 0, "F, the floating point opcodes are decoded");
        assertTrue((misa & (1L << 3)) != 0, "D, single precision is implemented alongside it");
        assertFalse((misa & (1L << 20)) != 0, "V is not implemented and must not be advertised");
    }
    @Test
    void supervisorScratchAndTvecHaveTheirOwnStorage() {
        // Only sstatus, sie and sip are views of machine registers. sscratch and stvec are separate
        // registers, so a guest must not be able to reach mscratch or mtvec through them: writing
        // stvec used to land in mtvec, and a guest trap vector then replaced the machine one.
        long mtvecBefore = m.csr.rawRead(CsrFile.MTVEC);
        long mscratchBefore = m.csr.rawRead(CsrFile.MSCRATCH);
        m.load(
                Asm.lui(5, 0x40),
                Asm.csrrw(6, CsrFile.SSCRATCH, 5),
                Asm.csrrw(7, CsrFile.STVEC, 5),
                Asm.csrrs(8, CsrFile.SSCRATCH, 0),
                Asm.csrrs(9, CsrFile.STVEC, 0));
        run(5);
        assertEquals(0x40000, m.reg(8), "sscratch round-trips");
        assertEquals(0x40000, m.reg(9), "stvec round-trips");
        assertEquals(mscratchBefore, m.csr.rawRead(CsrFile.MSCRATCH),
                "mscratch is untouched by a write to sscratch");
        assertEquals(mtvecBefore, m.csr.rawRead(CsrFile.MTVEC),
                "mtvec is untouched by a write to stvec");
    }

    @Test
    void sstatusIsAMaskedViewOfMstatus() {
        enterMachineMode();
        m.csr.setMstatus(0);
        m.load(
                Asm.addi(5, 0, -1),
                Asm.csrrs(6, CsrFile.MSTATUS, 5),
                Asm.csrrs(7, CsrFile.SSTATUS, 0));
        run(3);
        long mstatus = m.reg(6);
        long sstatus = m.reg(7);
        assertTrue((mstatus & (1L << CsrFile.MSTATUS_MIE)) != 0, "MIE is writable in mstatus");
        assertEquals(0, sstatus & (1L << CsrFile.MSTATUS_MIE),
                "MIE is not exposed through sstatus");
    }

    @Test()
    void countersAdvance() {
        enterMachineMode();
        m.load(
                Asm.csrrs(5, CsrFile.INSTRET, 0),    // +0
                Asm.addi(1, 0, 0),                  // +4
                Asm.addi(2, 0, 200),                // +8
                Asm.addi(1, 1, 1),                  // +12 loop body
                Asm.bltu(1, 2, -4),                 // +16 back to +12
                Asm.csrrs(6, CsrFile.INSTRET, 0),   // +20
                Asm.jal(0, 0));                     // +24 park on a self loop
        // 200 iterations is far more than the interrupt poll interval, so the counter is
        // guaranteed to have been folded in between the two reads.
        for (int i = 0; i < 100 && m.reg(6) == 0; i++) {
            run(64);
        }
        assertEquals(200, m.reg(1), "the loop ran");
        assertTrue(m.reg(6) > m.reg(5), "instret counts retired instructions");
    }

    @Test
    void counterAccessIsGatedByMcounteren() {
        long handler = TestMachine.CODE + 0x400;
        m.csr.rawWrite(CsrFile.MCOUNTEREN, 0L);
        m.load(Asm.csrrs(5, CsrFile.INSTRET, 0));
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(handler, m.cpu.pc(),
                "reading instret from S-mode without mcounteren is illegal");
    }

    @Test
    void fenceAndFenceIExecuteWithoutEffect() {
        m.load(
                Asm.fence(),
                Asm.fenceI(),
                Asm.addi(1, 0, 1));
        run(3);
        assertEquals(1, m.reg(1));
    }

    @Test
    void wfiIsAccepted() {
        m.load(Asm.wfi(), Asm.addi(1, 0, 1));
        run(2);
        assertEquals(1, m.reg(1));
    }

    @Test
    void sfenceVmaIsAccepted() {
        m.load(Asm.sfenceVma(0, 0), Asm.addi(1, 0, 1));
        run(2);
        assertEquals(1, m.reg(1));
    }

    @Test
    void privilegedInstructionsRequireMachineMode() {
        long handler = TestMachine.CODE + 0x400;
        m.load(Asm.mret());
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.cpu.setPc(TestMachine.CODE);
        run(1);
        assertEquals(handler, m.cpu.pc(), "mret from S-mode is illegal");
        assertEquals(2, m.csr.mcause());
    }

    // ---- throughput -----------------------------------------------------------------------------

    @Test()
    void aLongLoopTerminatesAndIsFast() {
        // for (x1 = 0; x1 < target; x1++) x3 += x1;
        int target = 1_000_000;
        int hi = target >>> 12;
        m.load(
                Asm.addi(1, 0, 0),                    // +0
                Asm.lui(2, hi),                        // +4
                Asm.addi(2, 2, target - (hi << 12)),   // +8
                Asm.add(3, 3, 1),                      // +12 loop body
                Asm.addi(1, 1, 1),                     // +16
                Asm.bltu(1, 2, -8),                    // +20 back to +12
                Asm.jal(0, 0));                        // +24 park on a self loop
        long start = System.nanoTime();
        while (m.reg(1) != target) {
            m.cpu.runBlock(50_000);
        }
        long elapsed = System.nanoTime() - start;
        assertEquals(target, m.reg(1), "the loop ran to completion");
        assertEquals(1, m.cpu.privilege(), "and never trapped");
        double mips = (target * 5.0) / (elapsed / 1e6) / 1000.0;
        assertTrue(mips > 5.0, "interpreter should exceed 5 MIPS, measured " + mips);
    }

    @Test
    void trapsLeaveTheMachineConsistent() {
        List<Long> causes = new ArrayList<>();
        long handler = TestMachine.CODE + 0x800;
        m.csr.rawWrite(CsrFile.MTVEC, handler);
        m.load(Asm.ecall());
        for (int i = 0; i < 5; i++) {
            m.cpu.setPrivilege(1);
            m.cpu.setPc(TestMachine.CODE);
            m.cpu.runBlock(1);
            causes.add(m.csr.mcause());
        }
        for (long cause : causes) {
            assertEquals(9, cause);
        }
        assertEquals(handler, m.cpu.pc());
    }

    @Test
    void trapExceptionCarriesCauseAndValue() {
        TrapException ex = new TrapException(TrapException.LOAD_PAGE_FAULT, 0x1234L);
        assertEquals(13, ex.cause);
        assertEquals(0x1234L, ex.value);
        assertEquals(0, ex.getStackTrace().length,
                "trap exceptions carry no stack trace, which matters on the hot path");
    }

    @Test
    void interruptCausesCarryTheHighBit() {
        TrapException ex = new TrapException(TrapException.INTERRUPT_BIT | 5, 0);
        assertTrue(ex.isInterrupt());
        assertEquals(5, ex.cause & ~TrapException.INTERRUPT_BIT);
    }
}