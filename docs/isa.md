# CORE-32 Instruction Set Architecture

Version 0.1 (draft)

CORE-32 is a 32-bit load/store register machine designed from scratch for this
project. It is not a derivative of RISC-V, MIPS, or ARM, although it borrows the
good ideas those architectures share: fixed-width instructions, a hardwired zero
register, and a small orthogonal integer operation set.

The guiding constraint is that a straightforward in-order pipeline should be able
to decode an instruction by looking at a handful of fixed bit positions, without
any secondary function-code lookup.

---

## 1. Programmer's model

### 1.1 Registers

Sixteen 32-bit general purpose registers, `r0` through `r15`.

Four-bit register fields are the central design decision of CORE-32. They cost a
little register pressure and buy a great deal of instruction encoding space: a
three-operand instruction spends only 12 bits on operands, which leaves room for
an 18-bit immediate on two-operand forms and a 22-bit immediate on one-operand
forms. Most constants and most stack frame offsets therefore fit in a single
instruction.

`r0` reads as zero and discards writes. This gives the assembler free `nop`,
register-to-register move, and unconditional-compare idioms without dedicated
opcodes.

| Register | ABI name | Role | Preserved across a call |
| --- | --- | --- | --- |
| `r0` | `zero` | Hardwired zero | n/a |
| `r1` | `a0` | Argument 0, return value | No |
| `r2` | `a1` | Argument 1 | No |
| `r3` | `a2` | Argument 2 | No |
| `r4` | `a3` | Argument 3 | No |
| `r5` | `t0` | Temporary | No |
| `r6` | `t1` | Temporary | No |
| `r7` | `t2` | Temporary | No |
| `r8` | `s0` | Saved | Yes |
| `r9` | `s1` | Saved | Yes |
| `r10` | `s2` | Saved | Yes |
| `r11` | `s3` | Saved | Yes |
| `r12` | `gp` | Global pointer | Yes |
| `r13` | `fp` | Frame pointer | Yes |
| `r14` | `sp` | Stack pointer | Yes |
| `r15` | `lr` | Link register | No |

The stack grows downward and `sp` is kept 4-byte aligned.

### 1.2 Program counter

`pc` is a separate 32-bit register, not addressable as a general register. It is
always a multiple of 4; instructions are 4 bytes wide and 4-byte aligned. `pc`
is readable only through `ADDPC`, `CALL`, and `CALLR`.

### 1.3 Memory

Byte addressed, little endian, 32-bit address space. Halfword accesses must be
2-byte aligned and word accesses 4-byte aligned; a misaligned access raises a
`MISALIGNED` trap rather than silently rotating or splitting.

### 1.4 Reset state

On reset `pc` takes the implementation-defined reset vector (`0x00000000` in the
C1 core) and all general registers are undefined except `r0`.

---

## 2. Instruction encoding

Every instruction is exactly 32 bits. The primary opcode is always
`instr[31:26]`, and register fields, when present, are always at the same bit
positions. Decode never depends on a secondary function field.

```
 bit  31        26 25    22 21    18 17    14 13                     0
     +------------+--------+--------+--------+-----------------------+
 RRR |   opcode   |   rd   |   ra   |   rb   |        (zero)         |
     +------------+--------+--------+--------+-----------------------+
 RRI |   opcode   |   rd   |   ra   |         imm18                  |
     +------------+--------+--------+--------------------------------+
 RI  |   opcode   |   rd   |                imm22                    |
     +------------+--------+------------------------------------------+
 BR  |   opcode   | (zero) |   ra   |   rb   |        off14          |
     +------------+--------+--------+--------+-----------------------+
 J   |   opcode   |                     off26                        |
     +------------+--------------------------------------------------+
```

* `imm18` and `imm22` are sign extended to 32 bits.
* `off14` and `off26` are sign extended and then shifted left by 2, so branch
  reach is +/- 32 KiB and jump reach is +/- 128 MiB from the delay-free `pc`.
* Shift instructions use only the low five bits of the shift operand.

### 2.1 Opcode map

The opcode field is structured. `op[5:4]` selects the instruction class and
`op[3:0]` selects the operation within it, so the class decode is two bits.

| `op[5:4]` | Class | Format |
| --- | --- | --- |
| `00` | Register ALU | RRR |
| `01` | Immediate ALU and constant formation | RRI, RI |
| `10` | Load, store, and system | RRI |
| `11` | Control transfer | BR, J, RRI |

Within class `00` and class `01` the low four bits of the opcode are the same
ALU function code. `ADD` is `0x00` and `ADDI` is `0x10`; `SAR` is `0x07` and
`SARI` is `0x17`. The ALU function input is literally `op[3:0]` regardless of
whether the second operand comes from a register or an immediate, which removes
an entire mux from the decoder.

Three slots break the mirror because the multiply instructions have no immediate
form; `0x1A`, `0x1B`, and `0x1C` are reused for constant formation instead.

#### Class `00` — register ALU (RRR)

| Opcode | Mnemonic | Operation |
| --- | --- | --- |
| `0x00` | `ADD rd, ra, rb` | `rd = ra + rb` |
| `0x01` | `SUB rd, ra, rb` | `rd = ra - rb` |
| `0x02` | `AND rd, ra, rb` | `rd = ra & rb` |
| `0x03` | `OR rd, ra, rb` | `rd = ra \| rb` |
| `0x04` | `XOR rd, ra, rb` | `rd = ra ^ rb` |
| `0x05` | `SHL rd, ra, rb` | `rd = ra << rb[4:0]` |
| `0x06` | `SHR rd, ra, rb` | `rd = ra >> rb[4:0]`, zero fill |
| `0x07` | `SAR rd, ra, rb` | `rd = ra >> rb[4:0]`, sign fill |
| `0x08` | `SLT rd, ra, rb` | `rd = (ra < rb) ? 1 : 0`, signed |
| `0x09` | `SLTU rd, ra, rb` | `rd = (ra < rb) ? 1 : 0`, unsigned |
| `0x0A` | `MUL rd, ra, rb` | `rd = (ra * rb)[31:0]` |
| `0x0B` | `MULH rd, ra, rb` | `rd = (ra * rb)[63:32]`, signed x signed |
| `0x0C` | `MULHU rd, ra, rb` | `rd = (ra * rb)[63:32]`, unsigned x unsigned |
| `0x0D` | `SEQ rd, ra, rb` | `rd = (ra == rb) ? 1 : 0` |
| `0x0E` | `SNE rd, ra, rb` | `rd = (ra != rb) ? 1 : 0` |
| `0x0F` | `ROR rd, ra, rb` | `rd = ra` rotated right by `rb[4:0]` |

There is no divide instruction. Division is a multi-cycle operation that would
force either a stall protocol or a long critical path into the first
implementation, so it is left to a future extension.

#### Class `01` — immediate ALU and constant formation

The RRI entries compute `rd = ra op sext(imm18)`. Immediates are sign extended
for the logical operations as well as the arithmetic ones, so `ANDI rd, ra, -1`
is expressible and only one immediate path exists in hardware.

| Opcode | Mnemonic | Format | Operation |
| --- | --- | --- | --- |
| `0x10` | `ADDI rd, ra, imm18` | RRI | `rd = ra + sext(imm18)` |
| `0x11` | reserved | | There is no `SUBI`; negate the immediate |
| `0x12` | `ANDI rd, ra, imm18` | RRI | `rd = ra & sext(imm18)` |
| `0x13` | `ORI rd, ra, imm18` | RRI | `rd = ra \| sext(imm18)` |
| `0x14` | `XORI rd, ra, imm18` | RRI | `rd = ra ^ sext(imm18)` |
| `0x15` | `SHLI rd, ra, imm18` | RRI | `rd = ra << imm18[4:0]` |
| `0x16` | `SHRI rd, ra, imm18` | RRI | `rd = ra >> imm18[4:0]`, zero fill |
| `0x17` | `SARI rd, ra, imm18` | RRI | `rd = ra >> imm18[4:0]`, sign fill |
| `0x18` | `SLTI rd, ra, imm18` | RRI | signed set-less-than against immediate |
| `0x19` | `SLTUI rd, ra, imm18` | RRI | unsigned set-less-than against immediate |
| `0x1A` | `MOVI rd, imm22` | RI | `rd = sext(imm22)` |
| `0x1B` | `MOVHI rd, imm22` | RI | `rd = (imm22 << 10) \| (rd & 0x3FF)` |
| `0x1C` | `ADDPC rd, imm22` | RI | `rd = pc + sext(imm22)` |
| `0x1D` | `SEQI rd, ra, imm18` | RRI | `rd = (ra == sext(imm18)) ? 1 : 0` |
| `0x1E` | `SNEI rd, ra, imm18` | RRI | `rd = (ra != sext(imm18)) ? 1 : 0` |
| `0x1F` | `RORI rd, ra, imm18` | RRI | `rd = ra` rotated right by `imm18[4:0]` |

`SLTUI` compares against the sign-extended immediate reinterpreted as unsigned,
which is what makes `SLTUI rd, ra, -1` a useful "is not all ones" test.

##### Building arbitrary 32-bit constants

`MOVI` alone covers every constant in `[-2097152, 2097151]`, which is the
overwhelming majority of real immediates. Anything wider takes exactly two
instructions, because `MOVHI` replaces the top 22 bits and preserves the low 10:

```
MOVI  rd, K[9:0]        ; rd = K[9:0]          (always non-negative, no sign bleed)
MOVHI rd, K[31:10]      ; rd = K
```

`MOVHI` reads `rd` as well as writing it. This is the only instruction in
CORE-32 where the `rd` field is both a source and a destination.

#### Class `10` — load, store, and system (RRI)

The effective address is `ra + sext(imm18)`, giving a +/- 128 KiB displacement,
enough to reach any reasonable stack frame or global in one instruction.

Within this class `op[3]` selects store versus load and `op[2:0]` selects the
access size and extension.

| Opcode | Mnemonic | Operation |
| --- | --- | --- |
| `0x20` | `LDW rd, [ra + imm18]` | `rd = mem32[ea]` |
| `0x21` | `LDH rd, [ra + imm18]` | `rd = sext(mem16[ea])` |
| `0x22` | `LDHU rd, [ra + imm18]` | `rd = zext(mem16[ea])` |
| `0x23` | `LDB rd, [ra + imm18]` | `rd = sext(mem8[ea])` |
| `0x24` | `LDBU rd, [ra + imm18]` | `rd = zext(mem8[ea])` |
| `0x28` | `STW rd, [ra + imm18]` | `mem32[ea] = rd` |
| `0x29` | `STH rd, [ra + imm18]` | `mem16[ea] = rd[15:0]` |
| `0x2A` | `STB rd, [ra + imm18]` | `mem8[ea] = rd[7:0]` |
| `0x2F` | `HALT` | Stop the core |

Stores read their data from the `rd` field. Keeping the address base in `ra` for
both loads and stores means the register file needs only two read ports and the
address adder never has to choose between two source fields.

`HALT` stops instruction execution and raises the core's `halted` output. It
exists so that simulation and bring-up programs have a defined, observable way
to finish. Opcodes `0x25` to `0x27` and `0x2B` to `0x2E` are reserved for the
future privileged and control-register extension.

#### Class `11` — control transfer

Conditional branches use the BR format and compare two registers directly; there
is no condition code register, so a branch never has a hidden dependency on an
earlier arithmetic instruction.

| Opcode | Mnemonic | Format | Taken when |
| --- | --- | --- | --- |
| `0x30` | `BEQ ra, rb, label` | BR | `ra == rb` |
| `0x31` | `BNE ra, rb, label` | BR | `ra != rb` |
| `0x32` | `BLT ra, rb, label` | BR | `ra < rb`, signed |
| `0x33` | `BGE ra, rb, label` | BR | `ra >= rb`, signed |
| `0x34` | `BLTU ra, rb, label` | BR | `ra < rb`, unsigned |
| `0x35` | `BGEU ra, rb, label` | BR | `ra >= rb`, unsigned |

| Opcode | Mnemonic | Format | Operation |
| --- | --- | --- | --- |
| `0x38` | `JMP label` | J | `pc = pc + sext(off26) * 4` |
| `0x39` | `CALL label` | J | `lr = pc + 4; pc = pc + sext(off26) * 4` |
| `0x3A` | `JMPR ra, imm18` | RRI | `pc = ra + sext(imm18)` |
| `0x3B` | `CALLR rd, ra, imm18` | RRI | `rd = pc + 4; pc = ra + sext(imm18)` |

The branch target is computed relative to the branch's own `pc`, not `pc + 4`.
There are no delay slots. `CALL` writes `lr` implicitly, which keeps 26 bits of
displacement available; `CALLR` names its link register explicitly so that
nested or non-standard linkage is expressible.

The low two bits of a computed target from `JMPR` or `CALLR` are forced to zero
rather than trapping, which makes returning through a tagged pointer safe.

---

## 3. Assembler aliases

These are assembler conveniences, not distinct opcodes.

| Alias | Expands to |
| --- | --- |
| `NOP` | `ADD r0, r0, r0` |
| `MOV rd, ra` | `ADD rd, ra, r0` |
| `NEG rd, ra` | `SUB rd, r0, ra` |
| `NOT rd, ra` | `XORI rd, ra, -1` |
| `RET` | `JMPR lr, 0` |
| `BEQZ ra, label` | `BEQ ra, r0, label` |
| `BNEZ ra, label` | `BNE ra, r0, label` |
| `BGT ra, rb, label` | `BLT rb, ra, label` |
| `BLE ra, rb, label` | `BGE rb, ra, label` |
| `LI rd, K` | `MOVI`, or `MOVI` plus `MOVHI` when `K` does not fit |

---

## 4. Traps

Version 0.1 has no privileged mode and no trap handler. The core detects the
following conditions, stops, and reports a cause on its debug interface:

| Cause | Condition |
| --- | --- |
| `NONE` | Running, or stopped by `HALT` |
| `ILLEGAL` | The opcode is not defined above |
| `MISALIGNED_FETCH` | A computed branch or jump target is not 4-byte aligned |
| `MISALIGNED_LOAD` | A load's effective address is not naturally aligned |
| `MISALIGNED_STORE` | A store's effective address is not naturally aligned |

Because `JMPR` and `CALLR` clear the low two bits of their target,
`MISALIGNED_FETCH` cannot actually be raised by the current instruction set. It
is defined now so that the encoding of the cause field does not have to change
when indirect branches through arbitrary pointers are added.

Vectored traps, a supervisor mode, and control registers are the main planned
additions for version 0.2, and the reserved opcode space in class `10` is sized
to hold them.
