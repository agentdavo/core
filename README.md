# core

A 32-bit CPU and its instruction set, designed from scratch in
[SpinalHDL](https://spinalhdl.github.io/SpinalDoc-RTD/).

Two things live here:

- **CORE-32**, an instruction set architecture. Not a RISC-V or MIPS variant:
  its own encoding, its own register file, its own opcode map. Specified in
  [docs/isa.md](docs/isa.md).
- **C1**, the first implementation of it. A five stage in-order pipeline with
  full forwarding, a single-cycle load-use interlock, and branches resolved in
  execute. Described in [docs/microarchitecture.md](docs/microarchitecture.md).

Everything is verified in simulation against a reference model, with Verilator
as the simulation backend through SpinalSim.

## Quick start

Requires a JDK, sbt, and Verilator.

```sh
sbt test                            # run the whole verification suite
sbt "Test/runMain core.Demo"        # assemble, disassemble and run a program
sbt "runMain core.GenerateCoreSoc"  # emit generated/CoreSoc.v
make help                           # the rest of the targets
```

`Demo` prints the program it assembled, runs it on the core under Verilator, and
reports where it stopped and what the registers held:

```
00000000: 68800018    movi a1, 24
00000004: 69400000    movi t0, 0
...
stopped:  halt at 0x00000030
retired:  151 instructions in 204 cycles (0.74 per cycle)
memory:   word 512 = 46368 (fibonacci 24 = 46368)
```

## What CORE-32 looks like

Sixteen registers, fixed 32-bit instructions, load/store, no condition codes,
no delay slots. Four-bit register fields are the central design decision: they
cost some register pressure and buy a lot of encoding room, so a two-operand
instruction still has an 18-bit immediate and a one-operand instruction has a
22-bit one. Most constants and most stack offsets fit in a single instruction.

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

The opcode map is structured so decode stays flat. Two bits select the class and
the low nibble selects the operation, and within the arithmetic classes that low
nibble *is* the ALU function code: `ADD` is `0x00` and `ADDI` is `0x10`, `SAR`
is `0x07` and `SARI` is `0x17`. The decoder wires `opcode(3 downto 0)` straight
into the ALU without translating it. Likewise `opcode(3)` separates loads from
stores, and `opcode(2 downto 0)` is already the branch condition.

## Writing programs

Programs are written in a Scala assembler, so test code gets loops and constants
from the host language without a separate toolchain having to exist first.

```scala
val program = Assembler() { a => import a._
  li(a1, 24)
  movi(t0, 0)
  movi(t1, 1)
  label("loop")
  beqz(a1, "done")
  add(t2, t0, t1)
  mov(t0, t1)
  mov(t1, t2)
  addi(a1, a1, -1)
  jmp("loop")
  label("done")
  mov(a0, t0)
  halt()
}
```

`Assembler.listing(program)` renders it back as an annotated disassembly.

## Layout

```
build.sbt                     sbt build, Scala 2.13 and SpinalHDL 1.15
hw/spinal/core/
  Isa.scala                   the encoding, shared by RTL, assembler and model
  Assembler.scala             two-pass assembler with labels
  CoreConfig.scala            configuration and the two bus definitions
  Decoder.scala               combinational instruction decoder
  Alu.scala                   integer ALU and the shared multiplier
  Core.scala                  the C1 five stage pipeline
  CoreSoc.scala               core plus tightly coupled memory, the sim target
  Generate.scala              elaboration entry points
hw/test/scala/core/
  CoreEmu.scala               reference model, written from the specification
  Sim.scala                   Verilator-backed simulation harness
  CoreSpec.scala              the co-simulation comparison every test goes through
  RandomProgram.scala         random program generator
  *Spec.scala                 the test suites
docs/isa.md                   the architecture
docs/microarchitecture.md     how C1 implements it
```

## Verification

Every test that runs a program runs it twice: once on the RTL under Verilator
and once on `CoreEmu`, a plain interpreter written separately from the
specification. The two are compared on all sixteen registers, the stop status,
the stopping PC, the number of instructions retired, and every memory word the
test asks about.

Agreement between the two only proves they read the specification the same way,
so directed tests add hand-computed expectations on top, and the whole-program
tests check against oracles computed independently in Scala, including a CRC32
checked against `java.util.zip.CRC32`.

| Suite | What it covers |
| --- | --- |
| `AssemblerSpec` | Encoding and the structure of the opcode map. No hardware |
| `IsaSpec` | Every instruction, with hand-computed results |
| `HazardSpec` | Forwarding at every distance, the interlock, the branch shadow |
| `TrapSpec` | Illegal opcodes and misaligned accesses |
| `ProgramSpec` | Sorting, memcpy, strlen, recursion, CRC32, a jump table |
| `CosimSpec` | 170 random programs compared against the reference model |
| `ConfigSpec` | Building without a multiplier, and a non-zero reset vector |

Random programs are generated so they always terminate, because every branch and
jump goes forward, and never trap, because every address is masked into a
scratch region with the alignment its access size needs. Everything else is left
free, which is what puts pressure on the forwarding paths.

To capture waveforms, set `CORE_WAVE=1`; FST files land in `simWorkspace/`.

```sh
CORE_WAVE=1 sbt "testOnly core.HazardSpec"
```

## Status

The integer instruction set is complete and implemented. There is no privileged
mode, no trap handler, no divide instruction, and both buses are fixed-latency
tightly coupled memories with no backpressure. See the end of
[docs/microarchitecture.md](docs/microarchitecture.md) for what comes next.
