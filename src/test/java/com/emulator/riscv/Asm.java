package com.emulator.riscv;

/**
 * Minimal RV64 assembler used by the emulator tests.
 *
 * <p>Only the encodings the tests need are implemented, but they are implemented against the
 * specification's bit layouts rather than by convenience, so a test that passes here is evidence
 * about the real encoding and not about the test.
 */
public final class Asm {

    private Asm() {
    }

    public static int r(int funct7, int rs2, int rs1, int funct3, int rd, int opcode) {
        return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode;
    }

    public static int i(int imm, int rs1, int funct3, int rd, int opcode) {
        return (imm << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode;
    }

    public static int s(int imm, int rs2, int rs1, int funct3, int opcode) {
        return ((imm >> 5) & 0x7F) << 25 | (rs2 << 20) | (rs1 << 15) | (funct3 << 12)
                | (imm & 0x1F) << 7 | opcode;
    }

    public static int b(int imm, int rs2, int rs1, int funct3, int opcode) {
        int high = ((imm >> 12) & 1) << 31 | ((imm >> 5) & 0x3F) << 25;
        int low = ((imm >> 1) & 0xF) << 8 | ((imm >> 11) & 1) << 7;
        return high | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | low | opcode;
    }

    public static int u(int imm20, int rd, int opcode) {
        return (imm20 << 12) | (rd << 7) | opcode;
    }

    public static int j(int imm, int rd, int opcode) {
        int high = ((imm >> 20) & 1) << 31 | ((imm >> 1) & 0x3FF) << 21
                | ((imm >> 11) & 1) << 20 | ((imm >> 12) & 0xFF) << 12;
        return high | (rd << 7) | opcode;
    }

    // ---- instruction helpers --------------------------------------------------------------------

    public static int addi(int rd, int rs1, int imm) {
        return i(imm, rs1, 0, rd, 0x13);
    }

    public static int slti(int rd, int rs1, int imm) {
        return i(imm, rs1, 2, rd, 0x13);
    }

    public static int sltiu(int rd, int rs1, int imm) {
        return i(imm, rs1, 3, rd, 0x13);
    }

    public static int xori(int rd, int rs1, int imm) {
        return i(imm, rs1, 4, rd, 0x13);
    }

    public static int ori(int rd, int rs1, int imm) {
        return i(imm, rs1, 6, rd, 0x13);
    }

    public static int andi(int rd, int rs1, int imm) {
        return i(imm, rs1, 7, rd, 0x13);
    }

    public static int slli(int rd, int rs1, int shamt) {
        return i(shamt, rs1, 1, rd, 0x13);
    }

    public static int srli(int rd, int rs1, int shamt) {
        return i(shamt, rs1, 5, rd, 0x13);
    }

    public static int srai(int rd, int rs1, int shamt) {
        return i(0x400 | shamt, rs1, 5, rd, 0x13);
    }

    public static int add(int rd, int rs1, int rs2) {
        return r(0, rs2, rs1, 0, rd, 0x33);
    }

    public static int sub(int rd, int rs1, int rs2) {
        return r(0x20, rs2, rs1, 0, rd, 0x33);
    }

    public static int sll(int rd, int rs1, int rs2) {
        return r(0, rs2, rs1, 1, rd, 0x33);
    }

    public static int slt(int rd, int rs1, int rs2) {
        return r(0, rs2, rs1, 2, rd, 0x33);
    }

    public static int sltu(int rd, int rs1, int rs2) {
        return r(0, rs2, rs1, 3, rd, 0x33);
    }

    public static int xor(int rd, int rs1, int rs2) {
        return r(0, rs2, rs1, 4, rd, 0x33);
    }

    public static int srl(int rd, int rs1, int rs2) {
        return r(0, rs2, rs1, 5, rd, 0x33);
    }

    public static int sra(int rd, int rs1, int rs2) {
        return r(0x20, rs2, rs1, 5, rd, 0x33);
    }

    public static int or(int rd, int rs1, int rs2) {
        return r(0, rs2, rs1, 6, rd, 0x33);
    }

    public static int and(int rd, int rs1, int rs2) {
        return r(0, rs2, rs1, 7, rd, 0x33);
    }

    public static int mul(int rd, int rs1, int rs2) {
        return r(1, rs2, rs1, 0, rd, 0x33);
    }

    public static int mulh(int rd, int rs1, int rs2) {
        return r(1, rs2, rs1, 1, rd, 0x33);
    }

    public static int mulhu(int rd, int rs1, int rs2) {
        return r(1, rs2, rs1, 3, rd, 0x33);
    }

    public static int div(int rd, int rs1, int rs2) {
        return r(1, rs2, rs1, 4, rd, 0x33);
    }

    public static int divu(int rd, int rs1, int rs2) {
        return r(1, rs2, rs1, 5, rd, 0x33);
    }

    public static int rem(int rd, int rs1, int rs2) {
        return r(1, rs2, rs1, 6, rd, 0x33);
    }

    public static int remu(int rd, int rs1, int rs2) {
        return r(1, rs2, rs1, 7, rd, 0x33);
    }

    public static int addiw(int rd, int rs1, int imm) {
        return i(imm, rs1, 0, rd, 0x1B);
    }

    public static int slliw(int rd, int rs1, int shamt) {
        return i(shamt, rs1, 1, rd, 0x1B);
    }

    public static int srliw(int rd, int rs1, int shamt) {
        return i(shamt, rs1, 5, rd, 0x1B);
    }

    public static int sraiw(int rd, int rs1, int shamt) {
        return i(0x400 | shamt, rs1, 5, rd, 0x1B);
    }

    public static int addw(int rd, int rs1, int rs2) {
        return r(0, rs2, rs1, 0, rd, 0x3B);
    }

    public static int subw(int rd, int rs1, int rs2) {
        return r(0x20, rs2, rs1, 0, rd, 0x3B);
    }

    public static int sllw(int rd, int rs1, int rs2) {
        return r(0, rs2, rs1, 1, rd, 0x3B);
    }

    public static int srlw(int rd, int rs1, int rs2) {
        return r(0, rs2, rs1, 5, rd, 0x3B);
    }

    public static int sraw(int rd, int rs1, int rs2) {
        return r(0x20, rs2, rs1, 5, rd, 0x3B);
    }

    public static int lb(int rd, int rs1, int imm) {
        return i(imm, rs1, 0, rd, 0x03);
    }

    public static int lh(int rd, int rs1, int imm) {
        return i(imm, rs1, 1, rd, 0x03);
    }

    public static int lw(int rd, int rs1, int imm) {
        return i(imm, rs1, 2, rd, 0x03);
    }

    public static int ld(int rd, int rs1, int imm) {
        return i(imm, rs1, 3, rd, 0x03);
    }

    public static int lbu(int rd, int rs1, int imm) {
        return i(imm, rs1, 4, rd, 0x03);
    }

    public static int lhu(int rd, int rs1, int imm) {
        return i(imm, rs1, 5, rd, 0x03);
    }

    public static int lwu(int rd, int rs1, int imm) {
        return i(imm, rs1, 6, rd, 0x03);
    }

    public static int sb(int rs2, int rs1, int imm) {
        return s(imm, rs2, rs1, 0, 0x23);
    }

    public static int sh(int rs2, int rs1, int imm) {
        return s(imm, rs2, rs1, 1, 0x23);
    }

    public static int sw(int rs2, int rs1, int imm) {
        return s(imm, rs2, rs1, 2, 0x23);
    }

    public static int sd(int rs2, int rs1, int imm) {
        return s(imm, rs2, rs1, 3, 0x23);
    }

    public static int beq(int rs1, int rs2, int imm) {
        return b(imm, rs2, rs1, 0, 0x63);
    }

    public static int bne(int rs1, int rs2, int imm) {
        return b(imm, rs2, rs1, 1, 0x63);
    }

    public static int blt(int rs1, int rs2, int imm) {
        return b(imm, rs2, rs1, 4, 0x63);
    }

    public static int bge(int rs1, int rs2, int imm) {
        return b(imm, rs2, rs1, 5, 0x63);
    }

    public static int bltu(int rs1, int rs2, int imm) {
        return b(imm, rs2, rs1, 6, 0x63);
    }

    public static int bgeu(int rs1, int rs2, int imm) {
        return b(imm, rs2, rs1, 7, 0x63);
    }

    public static int jalr(int rd, int rs1, int imm) {
        return i(imm, rs1, 0, rd, 0x67);
    }

    public static int lui(int rd, int imm20) {
        return u(imm20, rd, 0x37);
    }

    public static int auipc(int rd, int imm20) {
        return u(imm20, rd, 0x17);
    }

    public static int jal(int rd, int imm) {
        return j(imm, rd, 0x6F);
    }

    public static int ecall() {
        return i(0, 0, 0, 0, 0x73);
    }

    public static int ebreak() {
        return i(1, 0, 0, 0, 0x73);
    }

    public static int csrrw(int rd, int csr, int rs1) {
        return i(csr, rs1, 1, rd, 0x73);
    }

    public static int csrrs(int rd, int csr, int rs1) {
        return i(csr, rs1, 2, rd, 0x73);
    }

    public static int csrrc(int rd, int csr, int rs1) {
        return i(csr, rs1, 3, rd, 0x73);
    }

    public static int csrrwi(int rd, int csr, int uimm) {
        return i(csr, uimm, 5, rd, 0x73);
    }

    public static int csrrsi(int rd, int csr, int uimm) {
        return i(csr, uimm, 6, rd, 0x73);
    }

    public static int csrrci(int rd, int csr, int uimm) {
        return i(csr, uimm, 7, rd, 0x73);
    }

    public static int mret() {
        return i(0x302, 0, 0, 0, 0x73);
    }

    public static int sret() {
        return i(0x102, 0, 0, 0, 0x73);
    }

    public static int wfi() {
        return i(0x105, 0, 0, 0, 0x73);
    }

    public static int fence() {
        return i(0x0FF, 0, 0, 0, 0x0F);
    }

    public static int fenceI() {
        return i(0, 0, 1, 0, 0x0F);
    }

    /** SFENCE.VMA carries its operands in the rs1 and rs2 fields, not in the immediate. */
    public static int sfenceVma(int rs1, int rs2) {
        return ((rs2 & 0x1F) << 20) | ((rs1 & 0x1F) << 15) | 0x12000073;
    }

    /*
     * AMO encoding: bits 31:27 hold a five bit funct5, bits 26:25 are aq and rl. The generic
     * r() helper places a seven bit funct7 at bits 31:25, so every funct5 has to be shifted left
     * by two before it is handed over - otherwise AMOSWAP decodes as AMOADD and LR/SC become
     * arithmetic operations on x0.
     */
    private static final int AMO_AMOADD = 0x00;
    private static final int AMO_AMOSWAP = 0x01;
    private static final int AMO_LR = 0x02;
    private static final int AMO_SC = 0x03;
    private static final int AMO_AMOXOR = 0x04;
    private static final int AMO_AMOOR = 0x08;
    private static final int AMO_AMOAND = 0x0C;
    private static final int AMO_AMOMIN = 0x10;
    private static final int AMO_AMOMAX = 0x14;
    private static final int AMO_AMOMINU = 0x18;
    private static final int AMO_AMOMAXU = 0x1C;

    /** Builds an AMO word; {@code funct5} is shifted into bits 31:27. */
    private static int amo(int funct5, int rs2, int rs1, int rd, int funct3) {
        return r(funct5 << 2, rs2, rs1, funct3, rd, 0x2F);
    }

    public static int amoaddW(int rd, int rs1, int rs2) {
        return amo(AMO_AMOADD, rs2, rs1, rd, 2);
    }

    public static int amoaddD(int rd, int rs1, int rs2) {
        return amo(AMO_AMOADD, rs2, rs1, rd, 3);
    }

    public static int amoswapW(int rd, int rs1, int rs2) {
        return amo(AMO_AMOSWAP, rs2, rs1, rd, 2);
    }

    public static int amoswapD(int rd, int rs1, int rs2) {
        return amo(AMO_AMOSWAP, rs2, rs1, rd, 3);
    }

    public static int amoxorD(int rd, int rs1, int rs2) {
        return amo(AMO_AMOXOR, rs2, rs1, rd, 3);
    }

    public static int amoorD(int rd, int rs1, int rs2) {
        return amo(AMO_AMOOR, rs2, rs1, rd, 3);
    }

    public static int amoandD(int rd, int rs1, int rs2) {
        return amo(AMO_AMOAND, rs2, rs1, rd, 3);
    }

    public static int amominD(int rd, int rs1, int rs2) {
        return amo(AMO_AMOMIN, rs2, rs1, rd, 3);
    }

    public static int amomaxD(int rd, int rs1, int rs2) {
        return amo(AMO_AMOMAX, rs2, rs1, rd, 3);
    }

    public static int amominuD(int rd, int rs1, int rs2) {
        return amo(AMO_AMOMINU, rs2, rs1, rd, 3);
    }

    public static int amomaxuD(int rd, int rs1, int rs2) {
        return amo(AMO_AMOMAXU, rs2, rs1, rd, 3);
    }

    /** {@code lr.w rd, (rs1)}; rs2 must be x0. */
    public static int lrW(int rd, int rs1) {
        return amo(AMO_LR, 0, rs1, rd, 2);
    }

    /** {@code lr.d rd, (rs1)}; rs2 must be x0. */
    public static int lrD(int rd, int rs1) {
        return amo(AMO_LR, 0, rs1, rd, 3);
    }

    /** {@code sc.w rd, rs2, (rs1)}. */
    public static int scW(int rd, int rs1, int rs2) {
        return amo(AMO_SC, rs2, rs1, rd, 2);
    }

    /** {@code sc.d rd, rs2, (rs1)}. */
    public static int scD(int rd, int rs1, int rs2) {
        return amo(AMO_SC, rs2, rs1, rd, 3);
    }

    // ---- compressed ------------------------------------------------------------------------------
    // Each entry below is written straight from the RVC encoding tables in the ISA manual; the
    // immediate field placement differs per format and is easy to get backwards.

    private static int q1(int funct3, int rest) {
        return (funct3 << 13) | (rest & 0x1FFF) | 1;
    }

    private static int q0(int funct3, int rest) {
        return (funct3 << 13) | (rest & 0x0FFF);
    }

    private static int q2(int funct3, int rest) {
        return (funct3 << 13) | (rest & 0x1FFF) | 2;
    }

    /** {@code c.addi4spn rd', sp, nzuimm}: imm[5:4|9:6|2|3] -> inst[12:11|10:7|6|5]. */
    public static int cAddi4spn(int rdPrime, int nzuimm) {
        int imm = nzuimm & 0xFF;
        int rest = ((imm >>> 4) & 3) << 11
                | ((imm >>> 6) & 0xF) << 7
                | ((imm >>> 2) & 1) << 6
                | ((imm >>> 3) & 1) << 5
                | (((rdPrime - 8) & 7) << 2);
        return q0(0, rest);
    }

    /** {@code c.lw rd', rs1', imm}: imm[5:3] -> inst[12:10], imm[2] -> inst[6], imm[6] -> inst[5]. */
    public static int cLw(int rdPrime, int rs1Prime, int imm) {
        int rest = (((imm >>> 3) & 7) << 10)
                | (((imm >>> 6) & 1) << 5)
                | (((imm >>> 2) & 1) << 6)
                | (((rs1Prime - 8) & 7) << 7)
                | (((rdPrime - 8) & 7) << 2);
        return q0(2, rest);
    }

    /** {@code c.ld rd', rs1', imm}: imm[5:3] -> inst[12:10], imm[7:6] -> inst[6:5]. */
    public static int cLd(int rdPrime, int rs1Prime, int imm) {
        int rest = (((imm >>> 3) & 7) << 10)
                | (((imm >>> 6) & 3) << 5)
                | (((rs1Prime - 8) & 7) << 7)
                | (((rdPrime - 8) & 7) << 2);
        return q0(3, rest);
    }

    /** {@code c.sw rs2', rs1', imm}. */
    public static int cSw(int rs2Prime, int rs1Prime, int imm) {
        int rest = (((imm >>> 3) & 7) << 10)
                | (((imm >>> 6) & 1) << 5)
                | (((imm >>> 2) & 1) << 6)
                | (((rs1Prime - 8) & 7) << 7)
                | (((rs2Prime - 8) & 7) << 2);
        return q0(6, rest);
    }

    /** {@code c.sd rs2', rs1', imm}. */
    public static int cSd(int rs2Prime, int rs1Prime, int imm) {
        int rest = (((imm >>> 3) & 7) << 10)
                | (((imm >>> 6) & 3) << 5)
                | (((rs1Prime - 8) & 7) << 7)
                | (((rs2Prime - 8) & 7) << 2);
        return q0(7, rest);
    }

    /** {@code c.nop} / {@code c.addi rd, imm}: imm[5] -> inst[12], imm[4:0] -> inst[6:2]. */
    public static int cAddi(int rd, int imm) {
        int rest = (((imm >>> 5) & 1) << 12) | ((imm & 0x1F) << 2) | ((rd & 0x1F) << 7);
        return q1(0, rest);
    }

    /** {@code c.li rd, imm}. */
    public static int cLi(int rd, int imm) {
        int rest = (((imm >>> 5) & 1) << 12) | ((imm & 0x1F) << 2) | ((rd & 0x1F) << 7);
        return q1(2, rest);
    }

    /** {@code c.addi16sp sp, nzimm}: imm[9] -> inst[12], imm[4|6|8:7|5] -> inst[6:2]. */
    public static int cAddi16sp(int nzimm) {
        int imm = nzimm & 0x3FF;
        int rest = (((imm >>> 9) & 1) << 12)
                | (((imm >>> 4) & 1) << 6)
                | (((imm >>> 6) & 1) << 5)
                | (((imm >>> 7) & 3) << 3)
                | (((imm >>> 5) & 1) << 2)
                | (2 << 7);
        return q1(3, rest);
    }

    /**
     * {@code c.lui rd, nzimm}, where {@code nzimm} is the six bit immediate that lands in bits
     * 31:12 of the destination. Its bit 5 goes to inst[12] and bits 4:0 to inst[6:2].
     */
    public static int cLui(int rd, int nzimm) {
        int imm = nzimm & 0x3F;
        int rest = (((imm >>> 5) & 1) << 12) | ((imm & 0x1F) << 2) | ((rd & 0x1F) << 7);
        return q1(3, rest);
    }

    /** {@code c.srli rd', shamt}: shamt[5] -> inst[12], shamt[4:0] -> inst[6:2]. */
    public static int cSrli(int rdPrime, int shamt) {
        int rest = (((shamt >>> 5) & 1) << 12) | ((shamt & 0x1F) << 2) | (((rdPrime - 8) & 7) << 7);
        return q1(4, rest);
    }

    /** {@code c.srai rd', shamt}. */
    public static int cSrai(int rdPrime, int shamt) {
        int rest = (1 << 10) | (((shamt >>> 5) & 1) << 12) | ((shamt & 0x1F) << 2)
                | (((rdPrime - 8) & 7) << 7);
        return q1(4, rest);
    }

    /** {@code c.andi rd', imm}. */
    public static int cAndi(int rdPrime, int imm) {
        int rest = (2 << 10) | (((imm >>> 5) & 1) << 12) | ((imm & 0x1F) << 2)
                | (((rdPrime - 8) & 7) << 7);
        return q1(4, rest);
    }

    /** {@code c.sub rd', rs2'}: funct2 = 00. */
    public static int cSub(int rdPrime, int rs2Prime) {
        int rest = (3 << 10) | (((rs2Prime - 8) & 7) << 2) | (((rdPrime - 8) & 7) << 7);
        return q1(4, rest);
    }

    /** {@code c.xor rd', rs2'}: funct2 = 01. */
    public static int cXor(int rdPrime, int rs2Prime) {
        int rest = (3 << 10) | (1 << 5) | (((rs2Prime - 8) & 7) << 2) | (((rdPrime - 8) & 7) << 7);
        return q1(4, rest);
    }

    /** {@code c.or rd', rs2'}: funct2 = 10. */
    public static int cOr(int rdPrime, int rs2Prime) {
        int rest = (3 << 10) | (2 << 5) | (((rs2Prime - 8) & 7) << 2) | (((rdPrime - 8) & 7) << 7);
        return q1(4, rest);
    }

    /** {@code c.and rd', rs2'}: funct2 = 11. */
    public static int cAnd(int rdPrime, int rs2Prime) {
        int rest = (3 << 10) | (3 << 5) | (((rs2Prime - 8) & 7) << 2) | (((rdPrime - 8) & 7) << 7);
        return q1(4, rest);
    }

    /** {@code c.beqz rs1', imm}: imm[8] -> inst[12], imm[4:3] -> inst[11:10], rest -> inst[6:2]. */
    public static int cBeqz(int rs1Prime, int imm) {
        return q1(6, cBranchRest(rs1Prime, imm));
    }

    /** {@code c.bnez rs1', imm}. */
    public static int cBnez(int rs1Prime, int imm) {
        return q1(7, cBranchRest(rs1Prime, imm));
    }

    private static int cBranchRest(int rs1Prime, int imm) {
        return (((imm >>> 8) & 1) << 12)
                | (((imm >>> 3) & 3) << 10)
                | (((imm >>> 6) & 3) << 5)
                | (((imm >>> 1) & 3) << 3)
                | (((imm >>> 5) & 1) << 2)
                | (((rs1Prime - 8) & 7) << 7);
    }

    /** {@code c.j imm}: imm[11] -> inst[12], imm[4] -> inst[11], imm[9:8] -> inst[10:9], rest -> inst[8:2]. */
    public static int cJ(int imm) {
        return q1(5, cJumpRest(imm));
    }

    private static int cJumpRest(int imm) {
        return (((imm >>> 11) & 1) << 12)
                | (((imm >>> 4) & 1) << 11)
                | (((imm >>> 8) & 3) << 9)
                | (((imm >>> 10) & 1) << 8)
                | (((imm >>> 6) & 1) << 7)
                | (((imm >>> 7) & 1) << 6)
                | (((imm >>> 1) & 7) << 3)
                | (((imm >>> 5) & 1) << 2);
    }

    /** {@code c.slli rd, shamt}. */
    public static int cSlli(int rd, int shamt) {
        int rest = (((shamt >>> 5) & 1) << 12) | ((shamt & 0x1F) << 2) | ((rd & 0x1F) << 7);
        return q2(0, rest);
    }

    /** {@code c.lwsp rd, imm}: inst[12]=imm[5], inst[11:7]=rd, inst[6:4]=imm[4:2], inst[3:2]=imm[7:6]. */
    public static int cLwsp(int rd, int imm) {
        int rest = (((imm >>> 5) & 1) << 12)
                | (((imm >>> 2) & 7) << 4)
                | (((imm >>> 6) & 3) << 2)
                | ((rd & 0x1F) << 7);
        return q2(2, rest);
    }

    /** {@code c.ldsp rd, imm}. */
    public static int cLdsp(int rd, int imm) {
        int rest = (((imm >>> 5) & 1) << 12)
                | (((imm >>> 3) & 3) << 5)
                | (((imm >>> 6) & 7) << 2)
                | ((rd & 0x1F) << 7);
        return q2(3, rest);
    }

    /** {@code c.jr rs1}: inst[12] must be zero, since bit 12 selects C.MV instead. */
    public static int cJr(int rs1) {
        return q2(4, (rs1 & 0x1F) << 7);
    }

    /** {@code c.mv rd, rs2}: bit 12 is clear for c.jr, c.mv and c.ebreak. */
    public static int cMv(int rd, int rs2) {
        return q2(4, ((rs2 & 0x1F) << 2) | ((rd & 0x1F) << 7));
    }

    /** {@code c.ebreak}: bit 12 set with rd == 0 and rs2 == 0, which assembles to 0x9002. */
    public static int cEbreak() {
        return q2(4, 1 << 12);
    }

    /** {@code c.jalr rs1}: bit 12 set and rs2 (inst[6:2]) clear. */
    public static int cJalr(int rs1) {
        return q2(4, (1 << 12) | ((rs1 & 0x1F) << 7));
    }

    /**
     * {@code c.add rd, rs2}: bit 12 set, which is what separates it from c.mv.
     *
     * <p>{@code c.mv} and {@code c.add} share one encoding. The hardware tells them apart by
     * comparing the operand fields: when {@code rd == rs2} it adds, otherwise it moves.
     */
    public static int cAdd(int rd, int rs2) {
        return q2(4, (1 << 12) | ((rs2 & 0x1F) << 2) | ((rd & 0x1F) << 7));
    }

    /** {@code c.swsp rs2, imm}: inst[12:9]=imm[5:2], inst[8:7]=imm[7:6], inst[6:2]=rs2. */
    public static int cSwsp(int rs2, int imm) {
        int rest = (((imm >>> 2) & 0xF) << 9)
                | (((imm >>> 6) & 3) << 7)
                | ((rs2 & 0x1F) << 2);
        return q2(6, rest);
    }

    /** {@code c.sdsp rs2, imm}: inst[12:10]=imm[5:3], inst[9:7]=imm[8:6]. */
    public static int cSdsp(int rs2, int imm) {
        int rest = (((imm >>> 3) & 7) << 10) | (((imm >>> 6) & 7) << 7) | ((rs2 & 0x1F) << 2);
        return q2(7, rest);
    }


    // ---- floating point ---------------------------------------------------------------------------------

    /**
     * Builds an OP-FP word.
     *
     * <p>{@code funct7} occupies inst[31:25] and its low bit <em>is</em> the format bit: clear for
     * single precision, set for double. It is therefore part of the operand rather than something
     * OR-ed in afterwards, which would corrupt the field.
     */
    private static int opFp(int funct7, int rm, int rs2, int rs1, int rd) {
        return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (rm << 12) | (rd << 7) | 0x53;
    }

    /** {@code fadd.s} / {@code fadd.d}. */
    public static int fadds(int rd, int rs1, int rs2) {
        return opFp(0x00, 7, rs2, rs1, rd);
    }

    public static int faddd(int rd, int rs1, int rs2) {
        return opFp(0x01, 7, rs2, rs1, rd);
    }

    public static int fsubs(int rd, int rs1, int rs2) {
        return opFp(0x04, 7, rs2, rs1, rd);
    }

    public static int fsubd(int rd, int rs1, int rs2) {
        return opFp(0x05, 7, rs2, rs1, rd);
    }

    public static int fmuls(int rd, int rs1, int rs2) {
        return opFp(0x08, 7, rs2, rs1, rd);
    }

    public static int fmuld(int rd, int rs1, int rs2) {
        return opFp(0x09, 7, rs2, rs1, rd);
    }

    public static int fdivs(int rd, int rs1, int rs2) {
        return opFp(0x0C, 7, rs2, rs1, rd);
    }

    public static int fdivd(int rd, int rs1, int rs2) {
        return opFp(0x0D, 7, rs2, rs1, rd);
    }

    public static int fsqrts(int rd, int rs1) {
        return opFp(0x2C, 7, 0, rs1, rd);
    }

    public static int fsqrtd(int rd, int rs1) {
        return opFp(0x2D, 7, 0, rs1, rd);
    }

    /** {@code fmv.x.d} / {@code fmv.x.w}: move raw bits into an integer register. */
    public static int fmvxd(int rd, int rs1) {
        return opFp(0x71, 0, 0, rs1, rd);
    }

    public static int fmvxw(int rd, int rs1) {
        return opFp(0x70, 0, 0, rs1, rd);
    }

    /** {@code fmv.d.x} / {@code fmv.w.x}: move integer bits into an f register. */
    public static int fmvdx(int rd, int rs1) {
        return opFp(0x79, 0, 0, rs1, rd);
    }

    public static int fmvxwToF(int rd, int rs1) {
        return opFp(0x78, 0, 0, rs1, rd);
    }

    /** {@code fcvt.d.l}: long to double, {@code rs2 = 2}. */
    public static int fcvtld(int rd, int rs1) {
        return opFp(0x69, 7, 2, rs1, rd);
    }

    /** {@code fcvt.d.w}: int to double, {@code rs2 = 0}. */
    public static int fcvtdw(int rd, int rs1) {
        return opFp(0x69, 7, 0, rs1, rd);
    }

    /** {@code fcvt.l.d}: double to long, {@code rs2 = 2}. */
    public static int fcvtdl(int rd, int rs1) {
        return opFp(0x61, 7, 2, rs1, rd);
    }

    /** {@code fclass.d}. */
    public static int fclassd(int rd, int rs1) {
        return opFp(0x71, 1, 0, rs1, rd);
    }

    public static int fleD(int rd, int rs1, int rs2) {
        return opFp(0x55, 0, rs2, rs1, rd);
    }

    public static int fltD(int rd, int rs1, int rs2) {
        return opFp(0x55, 1, rs2, rs1, rd);
    }

    public static int feqD(int rd, int rs1, int rs2) {
        return opFp(0x55, 2, rs2, rs1, rd);
    }

    /** {@code fld rd, imm(rs1)}. */
    public static int fld(int rd, int rs1, int imm) {
        return i(imm, rs1, 3, rd, 0x07);
    }

    /** {@code flw rd, imm(rs1)}. */
    public static int flw(int rd, int rs1, int imm) {
        return i(imm, rs1, 2, rd, 0x07);
    }

    /** {@code fsd rs2, imm(rs1)}. */
    public static int fsd(int rs2, int rs1, int imm) {
        return s(imm, rs2, rs1, 3, 0x27);
    }

    /** {@code fsw rs2, imm(rs1)}. */
    public static int fsw(int rs2, int rs1, int imm) {
        return s(imm, rs2, rs1, 2, 0x27);
    }

    /** {@code c.fld rd', imm(rs1')}. */
    public static int cFld(int rdPrime, int rs1Prime, int imm) {
        return q0(1, (((imm >>> 3) & 7) << 10) | (((imm >>> 6) & 3) << 5)
                | (((imm >>> 2) & 1) << 6) | (((rs1Prime - 8) & 7) << 7) | (((rdPrime - 8) & 7) << 2));
    }

    /** {@code c.fsd rs2', imm(rs1')}. */
    public static int cFsd(int rs2Prime, int rs1Prime, int imm) {
        return q0(5, (((imm >>> 3) & 7) << 10) | (((imm >>> 6) & 3) << 5)
                | (((imm >>> 2) & 1) << 6) | (((rs1Prime - 8) & 7) << 7) | (((rs2Prime - 8) & 7) << 2));
    }

    /** {@code c.fldsp rd, imm(sp)}: this is the encoding the kernel faulted on at entry+0xdc. */
    public static int cFldsp(int rd, int imm) {
        return q2(1, (((imm >>> 5) & 1) << 12) | (((imm >>> 3) & 3) << 5)
                | (((imm >>> 6) & 7) << 2) | ((rd & 0x1F) << 7));
    }

    /** {@code c.fsdsp rs2, imm(sp)}. */
    public static int cFsdsp(int rs2, int imm) {
        return q2(5, (((imm >>> 3) & 7) << 10) | (((imm >>> 6) & 7) << 7) | ((rs2 & 0x1F) << 2));
    }

    // ---- helpers -----------------------------------------------------------------------------

    public static void emit(com.emulator.riscv.bus.Memory memory, long address, int[] words) {
        byte[] bytes = new byte[words.length * 4];
        for (int i = 0; i < words.length; i++) {
            int word = words[i];
            bytes[i * 4] = (byte) word;
            bytes[i * 4 + 1] = (byte) (word >>> 8);
            bytes[i * 4 + 2] = (byte) (word >>> 16);
            bytes[i * 4 + 3] = (byte) (word >>> 24);
        }
        memory.loadImage(bytes, 0, bytes.length, address);
    }

    /** Emits 16 bit instructions, packing two per word. */
    public static void emit16(com.emulator.riscv.bus.Memory memory, long address, int[] halves) {
        byte[] bytes = new byte[(halves.length + 1) / 2 * 4];
        for (int i = 0; i < halves.length; i++) {
            int half = halves[i] & 0xFFFF;
            bytes[i * 2] = (byte) half;
            bytes[i * 2 + 1] = (byte) (half >>> 8);
        }
        memory.loadImage(bytes, 0, bytes.length, address);
    }
}