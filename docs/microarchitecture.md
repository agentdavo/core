# C1: the first CORE-32 implementation

C1 is a five stage, in-order, single-issue pipeline. It is the reference
implementation of CORE-32 and the design the tests run against.

The target is one instruction per cycle on straight-line code, with the only
stalls coming from a load feeding the instruction directly after it and from
taken branches. There is no branch predictor, no cache, and no privileged mode.

---

## 1. Pipeline

```
        +--------+   +--------+   +---------+   +--------+   +-----------+
  pc -->| fetch  |-->| decode |-->| execute |-->| memory |-->| writeback |
        +--------+   +--------+   +---------+   +--------+   +-----------+
             ^            |            |             |             |
             |            |            v             |             |
             +------------|--------- redirect        |             |
                          |            |             |             |
                          |            +-- forward --+-------------+
                          |                          |             |
                          +---- interlock            +-- forward --+
```

| Stage | Work |
| --- | --- |
| fetch | Present `pc` to the instruction bus; the word arrives next cycle |
| decode | Decode, read two registers, detect the load-use hazard |
| execute | ALU, address generation, branch resolution, trap detection |
| memory | Issue the data bus access |
| writeback | Select the load data or the ALU result and write the register file |

All the pipeline registers are in `Core.scala` as one block, and each stage is
a SpinalHDL `Area` that drives the registers it owns.

## 2. Fetch

`fetch.pc` is presented to the instruction bus every cycle and advances by four.
Instruction memory is synchronous, so the word for the address issued in one
cycle is available to decode in the next, and `idPc` is `fetch.pc` delayed by
one register to match.

Holding an instruction across a stall needs no shadow register. The bus contract
says the memory keeps its output stable while `enable` is low, which is what a
block RAM with a read enable does anyway, so freezing `fetch.pc` and dropping
`enable` freezes the instruction in decode as a side effect.

## 3. Decode

Decoding is flat, which is the payoff from the opcode map in docs/isa.md: two
bits select the class, the low nibble is already the ALU function code,
`opcode(3)` separates load from store, and `opcode(2 downto 0)` is already the
branch condition. There is no secondary function field and no translation table.

Register port one always reads the `ra` field. Port two reads `rb` for
three-operand instructions and branches, and the `rd` field for stores and for
MOVHI. Two ports are enough for every instruction in the architecture.

## 4. Hazards

### Forwarding

The register file is read combinationally in decode and written in writeback, so
a consumer may be one, two or three instructions behind its producer. Each
distance is covered in a different place:

| Producer is | It sits in | Covered by |
| --- | --- | --- |
| one instruction ahead | memory | forward into execute from the memory stage |
| two ahead | writeback | forward into execute from the writeback stage |
| three ahead | leaving writeback | write-first bypass in the register file read |

The third case is the one that is easy to miss. That instruction writes the
register file at the end of the cycle its consumer is reading in, so execute
never gets a chance to forward it; the bypass in `regRead` handles it instead.

Anything four or more instructions ahead has already landed in the register file.

### The load-use interlock

A load one instruction ahead of its consumer is the single case forwarding
cannot cover: the data is still coming out of memory when execute needs it.
Decode detects it and holds, injecting one bubble into execute. That converts
the case into a two-ahead dependency, which forwarding does cover.

The interlock consults the `usesRs1` and `usesRs2` bits from the decoder rather
than just comparing register numbers, so an instruction whose `ra` field is
really immediate bits, as in MOVI, does not stall on a coincidence.

The interlock and a branch redirect can never both fire in the same cycle: the
interlock only fires when a load is in execute, and a load is not a branch.

### Branches

The front end always predicts not taken. Branches resolve in execute against
forwarded operands, so a comparison can immediately follow the instruction that
produced its operands. A taken branch kills the two instructions behind it —
the one in decode and the one arriving from the instruction bus — and redirects
`pc`. That is a fixed two-cycle penalty, paid only when a branch is taken.

## 5. Execute

One ALU serves every arithmetic instruction. The three multiply results share a
single 33x33 signed multiplier: only MULH needs signed operands, MULHU needs
unsigned ones, and the low half that MUL returns is the same either way, so one
sign-extension mux covers all three.

Loads, stores and indirect jumps all force the ALU function to ADD and take
their second operand from the immediate, which makes the ALU the address adder
as well and means there is no separate address path.

The value written back is the ALU result, except for the five instructions that
need something else: CALL and CALLR write `pc + 4`, MOVI writes the immediate,
ADDPC writes `pc` plus the immediate, and MOVHI splices the immediate over the
top of the value it read from `rd`.

## 6. Memory and writeback

Byte and halfword accesses are done with lane selection rather than a shifter.
A store replicates its data across the word and drives a byte mask derived from
the low address bits; a load picks the lane in writeback and extends it
according to the opcode.

Misaligned accesses are caught in execute, where the address is computed. The
access never reaches the memory stage, so a misaligned store cannot corrupt the
word it would have straddled.

## 7. Stopping

There is no trap handler yet. Execute detects an illegal opcode or a misaligned
access, records the cause and the offending PC, and stops the machine; HALT
takes the same path with a cause of NONE. Instructions already past execute
drain and commit normally, so the state visible afterwards is precisely the
state after the last instruction before the one that stopped.

## 8. Buses

Both buses are tightly coupled fixed-latency memories with no backpressure: an
access issued in one cycle completes in the next, and the pipeline never waits
on memory.

This is the largest simplification in C1 and the first thing that should change.
A stallable bus means decode and execute have to be able to hold for an
arbitrary number of cycles, which is a real change to the control logic rather
than an addition to it.

## 9. What is not here

In rough order of what would be worth adding next:

- a stallable bus, so caches or a real interconnect can be attached
- traps with a handler, a supervisor mode, and control registers; the reserved
  opcode space in class `10` is sized for this
- divide and remainder, which need a multi-cycle unit and therefore the
  stall protocol above
- branch prediction, which is only worth it once the memory system can stall
  and the two-cycle penalty stops being the dominant cost
