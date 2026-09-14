# Axiom-64 Instruction Set Architecture

Version 0.1, Base profile.

Axiom-64 is a 64-bit load/store architecture. It is not derived from any
existing instruction set. The decisions below were argued out rather than
inherited, and the ones that cost something are marked as such.

Everything here is the Base profile. Compute profiles add the tile unit and
are not specified yet; see `roadmap.md`.

---

## 1. Programmer's model

### 1.1 General registers

Thirty-two 64-bit registers, `x0` through `x31`. `x0` reads as zero and
discards writes.

Five-bit register fields are the load-bearing decision. Sixty-four registers
would cost three more bits on a three-operand instruction, which is exactly
what makes the load and store pair forms unencodable in 32 bits. Thirty-two is
also past the knee of the spill-traffic curve, which is where AArch64, RV64 and
POWER all landed independently.

| Register | ABI name | Role | Preserved across a call |
| --- | --- | --- | --- |
| `x0` | `zero` | Hardwired zero | n/a |
| `x1` to `x7` | `a0` to `a6` | Arguments and return values | No |
| `x8` to `x17` | `t0` to `t9` | Temporaries | No |
| `x18` to `x29` | `s0` to `s11` | Saved | Yes |
| `x30` | `lr` | Link register, written by `BL` | No |
| `x31` | `sp` | Stack pointer, by convention | Yes |

The stack grows downward and `sp` is kept 16-byte aligned at public interfaces.

### 1.2 Predicate registers

Eight 1-bit registers, `p0` through `p7`. All eight are writable; there is no
hardwired-true predicate, because unconditional branches have their own opcodes
and do not need one.

Predicates are written only by the compare instructions and read only by `SEL`
and by `BP`. There is deliberately no predicate field on general arithmetic and
no predicated load or store. The reasoning is in section 6.

### 1.3 Program counter

`pc` is 64 bits, always a multiple of four, and is not addressable as a general
register. It is readable only through `ADDPC`, `BL` and `JALR`.

### 1.4 Memory

Byte addressed, little endian, 64-bit address space. Halfword, word and
doubleword accesses must be naturally aligned; a misaligned access raises a
trap rather than splitting or rotating. Byte accesses are always aligned.

---

## 2. Instruction encoding

Every instruction is exactly 32 bits, and field positions never move.

```
 bit  31        26 25    21 20    16 15    11 10                     0
     +------------+--------+--------+--------+-----------------------+
     |   opcode   |   rd   |   rn   |   rm   |      sub-fields       |
     +------------+--------+--------+--------+-----------------------+
```

There is no prefix instruction and no compressed encoding. A 32-bit fetch group
of N bytes always contains exactly N/4 instructions, and decoding slot `k` never
depends on slot `k-1`.

The formats are:

```
 R     | opcode |   rd   |   rn   |   rm   | fn(5) |    (zero)     |
 SHIFT | opcode |   rd   |   rn   |    shamt(6)    | fn(3) | (zero)|
 I     | opcode |   rd   |   rn   |            imm16              |
 MOV   | opcode |   rd   | (zero) | s(2) |         imm16          |
 PC    | opcode |   rd   |               imm21                    |
 CMP   | opcode |(z)| pd |   rn   |   rm   | cc(4) |    (zero)     |
 CMPI  | opcode |(z)| pd |   rn   |  cc(4) |         imm12         |
 SEL   | opcode |   rd   |   rn   |   rm   | p(3) | inv |  (zero)  |
 MEM   | opcode |   rt   |   rn   | mode(2)|          imm14        |
 PAIR  | opcode |  rt1   |   rn   |  rt2   | mode(2) |    imm9     |
 BR    | opcode |                  off26                          |
 BP    | opcode | p(3)|inv|                off22                   |
 ORD   | opcode |   rt   |   rn   | sz(2) | ord(2) |    (zero)     |
 ATOM  | opcode |   rd   |   rn   |   rs   | fn(4) |ord|sz| (zero) |
```

Immediates are sign extended unless stated otherwise. `imm16` in the move
instructions is a bit pattern and is not extended at all. Memory displacements
are scaled by the access size; branch displacements are scaled by four.

### 2.1 Opcode map

Six-bit primary opcode, sixty-four slots, of which thirty-seven are defined.

| Opcode | Mnemonic | Format | Operation |
| --- | --- | --- | --- |
| `0x00` | ALU register | R | `rd = rn fn rm`, function in `fn` |
| `0x01` | shift immediate | SHIFT | `rd = rn shifted by shamt` |
| `0x02` | `ADDI` | I | `rd = rn + sext(imm16)` |
| `0x03` | `ANDI` | I | `rd = rn & sext(imm16)` |
| `0x04` | `ORI` | I | `rd = rn \| sext(imm16)` |
| `0x05` | `XORI` | I | `rd = rn ^ sext(imm16)` |
| `0x06` | `SLTI` | I | `rd = (rn < sext(imm16)) ? 1 : 0`, signed |
| `0x07` | `SLTUI` | I | as above, unsigned comparison |
| `0x08` | `MOVZ` | MOV | `rd = imm16 << (16 * s)` |
| `0x09` | `MOVN` | MOV | `rd = ~(imm16 << (16 * s))` |
| `0x0a` | `MOVK` | MOV | replace bits `16*s+15:16*s` of `rd`, keep the rest |
| `0x0b` | `ADDPC` | PC | `rd = pc + sext(imm21)` |
| `0x0c` | `CMP.cc` | CMP | `pd = rn cc rm` |
| `0x0d` | `CMPI.cc` | CMPI | `pd = rn cc sext(imm12)` |
| `0x0e` | `SEL` | SEL | `rd = (p ^ inv) ? rn : rm` |
| `0x10` to `0x16` | loads | MEM | see 2.3 |
| `0x18` to `0x1b` | stores | MEM | see 2.3 |
| `0x1c` | `LDP` | PAIR | load two doublewords |
| `0x1d` | `STP` | PAIR | store two doublewords |
| `0x20` | `B` | BR | `pc = pc + sext(off26) * 4` |
| `0x21` | `BL` | BR | `lr = pc + 4`, then branch |
| `0x22` | `BP` | BP | branch if `p ^ inv` |
| `0x23` | `JALR` | I | `rd = pc + 4; pc = (rn + sext(imm16)) & ~3` |
| `0x30` | ordered load | ORD | see section 5 |
| `0x31` | ordered store | ORD | see section 5 |
| `0x32` | atomic | ATOM | see section 5 |
| `0x33` | `FENCE` | | see section 5 |
| `0x34` | `SYSTEM` | | `HALT` is function 0 |

All other primary opcodes, and all undefined sub-functions inside a defined
opcode, raise an illegal instruction trap. Reserved space traps rather than
aliasing, so that adding an instruction later cannot silently change the
meaning of an old binary.

### 2.2 ALU functions

`fn` occupies `instr(10 downto 6)` of opcode `0x00`.

| `fn` | Name | Operation |
| --- | --- | --- |
| `0x00` to `0x07` | `ADD` `SUB` `AND` `OR` `XOR` `ANDN` `ORN` `XNOR` | `ANDN` is `rn & ~rm` |
| `0x08` to `0x0b` | `SHL` `SHR` `SAR` `ROR` | shift amount is `rm(5 downto 0)` |
| `0x0c` `0x0d` | `SLT` `SLTU` | set to 1 or 0 |
| `0x0e` to `0x10` | `MUL` `MULH` `MULHU` | low 64 bits, and the two high halves |
| `0x11` to `0x13` | `ADDW` `SUBW` `MULW` | 32-bit result, sign extended to 64 |
| `0x14` to `0x17` | `SHLW` `SHRW` `SARW` `RORW` | 32-bit, shift amount `rm(4 downto 0)`, sign extended |
| `0x18` to `0x1b` | `MIN` `MAX` `MINU` `MAXU` | |

The `W` forms exist because a 64-bit machine without them makes every C `int`
expression cost a pair of shifts. There is no divide; it needs a multi-cycle
unit and belongs with the first implementation that can stall on one.

The shift-immediate opcode `0x01` carries a 6-bit amount and a 3-bit function
selecting `SHL`, `SHR`, `SAR`, `ROR` and their four `W` forms.

### 2.3 Loads and stores

The effective address is `rn + sext(imm14) * size`, so the displacement reaches
8192 elements either way. For a doubleword that is 64 KiB.

| Opcode | Mnemonic | Result |
| --- | --- | --- |
| `0x10` `0x11` | `LDB` `LDBU` | byte, sign or zero extended |
| `0x12` `0x13` | `LDH` `LDHU` | halfword |
| `0x14` `0x15` | `LDW` `LDWU` | word |
| `0x16` | `LDD` | doubleword |
| `0x18` to `0x1b` | `STB` `STH` `STW` `STD` | data comes from the `rd` field |

The two-bit `mode` field selects the addressing mode:

| `mode` | Name | Address | Base afterwards |
| --- | --- | --- | --- |
| `0` | offset | `rn + imm` | unchanged |
| `1` | pre-index | `rn + imm` | the computed address |
| `2` | post-index | `rn` | `rn + imm` |
| `3` | reserved | | |

`LDP` and `STP` move two doublewords at `address` and `address + 8`, with a
9-bit displacement scaled by eight and the same three addressing modes.

Pre-index, post-index and `LDP` each write two architectural registers. That is
not free: it costs a second register file write port, and on an out-of-order
implementation two rename allocations. The density is worth it for prologues,
epilogues and array loops, but the claim that these avoid all cracking is false
and is not made here.

When a pair or an indexed access names the same register twice, so that two
architectural writes collide, the result is architecturally undefined and the
assembler rejects it. That covers a data register equal to the base register of
an indexed access, and `LDP` naming the same register for both halves.

### 2.4 Compares, select and branches

Compare instructions write a predicate and nothing else. Condition codes are
`EQ` `NE` `LT` `GE` `LTU` `GEU` `LE` `GT` `LEU` `GTU`, numbered 0 to 9. `LE` and
`GT` earn their own codes because once an immediate is involved you cannot get
them by swapping operands.

`SEL rd, rn, rm, p` writes `rn` when the predicate matches the sense bit and
`rm` otherwise. It is an explicit three-source operation, which is the whole
point: the dependency on the old value is visible in the encoding instead of
being hidden in the rename logic.

`BP` branches on a predicate and has 22 bits of displacement, reaching 8 MiB.
`B` and `BL` are unconditional with 26 bits, reaching 128 MiB. `JALR` computes
its target from a register and clears the low two bits rather than trapping, so
returning through a tagged pointer is safe.

There are no delay slots and no condition code register.

---

## 3. Memory consistency

Axiom-64 is weakly ordered. Plain loads and stores may be reordered freely.

**Other-multi-copy atomicity.** A store becomes visible to all threads other
than the one that issued it at a single instant, so no two observers can
disagree about whether a store has happened. The issuing thread may observe its
own store earlier, through store buffer forwarding, and that exception is what
keeps store buffers legal. This property is mandatory. It is the difference
between a model that can be taught and one that cannot.

**Address dependency ordering.** If the address of a load is computed from the
result of an earlier load, the two are ordered. A store that follows a branch
whose condition depends on a load is also ordered after that load. A *load*
after such a branch is **not** ordered, because it may be speculated.

The hardware guarantee alone is not usable. A compiler that proves the loaded
value may replace the dependent load with a speculated one and the chain
evaporates, which is why `memory_order_consume` has never been implemented.
Any ABI claiming to use dependency ordering must also specify what preserves
the chain at source level. That contract is not yet written.

---

## 4. Ordered accesses and atomics

Ordered accesses have their own opcodes with a register base and **no
displacement**. Spending two bits on every load and store in the program to
serve the small fraction that need ordering would cost four times the reach on
all of them, and ordered accesses address a lock or a flag through a plain
pointer anyway.

| Instruction | Ordering |
| --- | --- |
| `LDx.AQ` | acquire, RCpc, matching C++ `memory_order_acquire` |
| `LDx.SEQ` | sequentially consistent |
| `STx.RL` | release, RCpc |
| `STx.SEQ` | sequentially consistent |

Ordered loads narrower than a doubleword **zero** extend. There is no signed
form, because these address a lock, a flag or a reference count, where the
value is either a bit pattern or a non-negative count. A signed atomic load of
a narrow signed quantity costs one extra sign-extending shift pair, which is
the right place to pay for a rare case.

Acquire and release are RCpc rather than RCsc, because RCsc over-synchronizes
an ordinary acquire; that is why ARM added `LDAPR` alongside `LDAR`. The
separate sequentially consistent forms carry the stronger guarantee where C++
actually needs it.

**Atomics.** `SWP`, `LDADD`, `LDAND`, `LDOR`, `LDXOR`, `CAS`, `LDMIN`, `LDMAX`,
`LDMINU`, `LDMAXU`, on words or doublewords, each carrying a two-bit ordering
annotation of plain, acquire, release or both. The architecture says nothing
about where an atomic executes. Far execution wins under contention and loses
badly without it, so that is an implementation's choice to make dynamically.

`CAS rd, rn, rs` compares `rd` against the doubleword or word at the address in
`rn`, stores `rs` there if they match, and returns the old memory value in `rd`.
The operand order follows the ATOM format: destination, address base, source.

A word-sized atomic sign extends the old value it returns, following the same
convention as the `W` arithmetic in section 2.2, so that an atomic on a 32-bit
signed counter compares correctly afterwards. A misaligned atomic raises
`MISALIGNED_LOAD`, because a read-modify-write reads first.

**Fences.** Annotations cannot express store-load ordering, so a standalone
fence exists: `FENCE` for the full barrier that `seq_cst` and `smp_mb` need,
plus acquire, release and tile variants.

**TSO mode.** An implementation may provide a mode in which its memory
operations are totally store ordered. It is per-thread state that privileged
software switches, not a global or per-guest control, and writing it implies a
full fence. On implementations that are always totally store ordered the
control reads back as set, so software can discover the state uniformly. TSO is
nearly free on a small in-order core and costs load-ordering speculation on a
large one; it is kept because binary translation of foreign code depends on it.

---

## 5. Why there is no general predication

AArch32 put a four-bit condition field on nearly every instruction and AArch64
removed it, keeping only conditional select, conditional compare and
conditional branch. Axiom-64 follows, for three reasons.

A false-predicated instruction still consumes a rename slot and a reorder
buffer entry, because the predicate is not known at rename. A predicated
register write must preserve the old value when false, so the instruction
implicitly reads its own destination and a two-source operation becomes four.
And the encoding cost falls on every instruction in the program:

| Instruction form | Bits left for the displacement | Reach at 8-byte scaling |
| --- | --- | --- |
| Load, predicated | 10 | 4 KiB |
| Load, unpredicated | 14 | 64 KiB |
| Load pair, predicated | 5 | 128 B |
| Load pair, unpredicated | 9 | 2 KiB |

Predicate registers are kept, because independent condition chains are worth
having and they let a short select avoid a branch. Universal predication is
not.

---

## 6. Traps

Version 0.1 has no privileged mode and no handler. The implementation detects
the following, stops, and reports the cause and the offending program counter.

| Cause | Condition |
| --- | --- |
| `NONE` | Running, or stopped by `HALT` |
| `ILLEGAL` | Undefined opcode or undefined sub-function |
| `MISALIGNED_FETCH` | Reserved; `JALR` masks its target so this cannot occur yet |
| `MISALIGNED_LOAD` | Load address not naturally aligned |
| `MISALIGNED_STORE` | Store address not naturally aligned |

A trapping or halting instruction does not commit. Instructions ahead of it in
the pipeline do.

---

## 7. Assembler aliases

| Alias | Expands to |
| --- | --- |
| `NOP` | `ADD x0, x0, x0` |
| `MOV rd, rn` | `ADD rd, rn, x0` |
| `NEG rd, rn` | `SUB rd, x0, rn` |
| `NOT rd, rn` | `XORI rd, rn, -1` |
| `RET` | `JALR x0, lr, 0` |
| `BR rn` | `JALR x0, rn, 0` |
| `LI rd, K` | `MOVZ` or `MOVN`, then up to three `MOVK` |
| `LA rd, label` | Always four instructions, so labels never shift |

`LI` picks the shortest sequence: a single `MOVZ` when only one 16-bit lane is
non-zero, a single `MOVN` when only one lane differs from all ones, and
otherwise a `MOVZ` or `MOVN` seed followed by `MOVK` for each remaining lane.
Any 64-bit constant costs at most four instructions, which is why no prefix
instruction is needed and why nothing in this architecture is wider than 32
bits.
