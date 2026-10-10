# Smoke payload for LinuxBootRunner.
# Runs in S-mode at 0x80200000 with a0 = hart id, a1 = device tree address and sp set.
# It exercises the same paths a real kernel boot would: the NS16550A console, a virtio-mmio
# register read, and then parks forever. No MMU mode switching, no interrupts, no ecall.

    .option norvc                 # keep the encoding 32-bit so the smoke test is easy to reason about
    .section .text.init
    .globl _start

    .equ UART_BASE,   0x10000000
    .equ UART_THR,    0
    .equ UART_LSR,    5
    .equ VIRTIO_BLK,  0x10001000

_start:
    mv      s0, a0                 # hart id
    mv      s1, a1                 # device tree address

    la      a0, banner
    call    print_string

    la      a0, tag_hart
    call    print_string
    mv      a0, s0
    call    print_hex64

    la      a0, tag_dtb
    call    print_string
    mv      a0, s1
    call    print_hex64

    # Read the virtio-mmio MagicValue register, which is the first thing a real kernel pokes too.
    # The result goes in a callee-saved register because the print routines clobber t0-t6, which is
    # what silently corrupted the value in the first version of this payload.
    li      t0, VIRTIO_BLK
    lw      s2, 0(t0)              # MagicValue, 0x74726976 "virt"
    li      s3, 0x74726976

    la      a0, tag_magic
    call    print_string
    mv      a0, s2
    call    print_hex64
    la      a0, newline
    call    print_string

    beq     s2, s3, magic_ok
    la      a0, magic_bad
    call    print_string
    j       done
magic_ok:
    la      a0, magic_ok_text
    call    print_string

done:
    la      a0, prompt
    call    print_string
    la      a0, newline
    call    print_string
1:  j       1b                     # park; the runner stops the machine on the prompt

# a0 = character. Spins on the line status register until the holding register is free.
putchar:
    li      t0, UART_BASE
    addi    t1, t0, UART_LSR
1:  lbu     t2, 0(t1)
    andi    t2, t2, 0x20           # THRE
    beqz    t2, 1b
    sb      a0, UART_THR(t0)
    ret

# a0 = NUL terminated string. ra is saved because putchar is reached with a call, and jal would
# otherwise overwrite the return address this function still needs.
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

# a0 = 64 bit value, printed as 16 hex digits without any libc. a0 is preserved across putchar
# because it is the value being printed.
print_hex64:
    addi    sp, sp, -16
    sd      ra, 0(sp)
    sd      a0, 8(sp)
    li      t4, 60
1:  srl     t5, a0, t4
    andi    t5, t5, 0xf
    li      t6, 10
    blt     t5, t6, 2f
    addi    t5, t5, 'a' - 10
    j       3f
2:  addi    t5, t5, '0'
3:  mv      a0, t5
    call    putchar
    ld      a0, 8(sp)
    addi    t4, t4, -4
    bgez    t4, 1b
    ld      ra, 0(sp)
    addi    sp, sp, 16
    ret

    .section .rodata
banner:      .asciz "\n"
tag_hart:    .asciz "hart id    = 0x"
tag_dtb:     .asciz "\ndevice tree = 0x"
tag_magic:   .asciz "\nvirtio magic = 0x"
magic_ok_text: .asciz " (matches the virtio-mmio specification)\n"
magic_bad:   .asciz " (WRONG: the transport did not answer)\n"
prompt:      .asciz "# "
newline:     .asciz "\n"
