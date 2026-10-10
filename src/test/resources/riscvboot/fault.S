# Fault payload: proves the runner's diagnostics catch a machine level trap.
# Prints a banner, then reads an address that is neither RAM nor a mapped device, which the
# interpreter turns into a load access fault. With medeleg clear the trap reaches M-mode, and a
# healthy Linux boot never does that.

    .option norvc
    .section .text.init
    .globl _start
    .equ UART_BASE,   0x10000000
    .equ UART_THR,    0
    .equ UART_LSR,    5
    .equ BAD_ADDRESS, 0x40000000

_start:
    la      a0, banner
    call    print_string
    la      a0, about_to_fault
    call    print_string
    li      t0, BAD_ADDRESS
    lw      t1, 0(t0)              # deliberate load access fault
1:  j       1b

putchar:
    li      t0, UART_BASE
    addi    t1, t0, UART_LSR
1:  lbu     t2, 0(t1)
    andi    t2, t2, 0x20
    beqz    t2, 1b
    sb      a0, UART_THR(t0)
    ret

print_string:
    addi    sp, sp, -16
    sd      ra, 0(sp)
    mv      t3, a0
1:  lbu     a0, 0(t3)
    beqz    a0, 2f
    call    putchar
    addi    t3, t3, 1
    j       1b
2:  ld      ra, 0(sp)
    addi    sp, sp, 16
    ret

    .section .rodata
banner:        .asciz "\nfault payload: starting\n"
about_to_fault: .asciz "reading unmapped 0x40000000 on purpose\n"
