# core

Two CPUs and two instruction sets, designed from scratch in
[SpinalHDL](https://spinalhdl.github.io/SpinalDoc-RTD/), verified in simulation
against reference models with Verilator as the backend.

| | **CORE-32** | **Axiom-64** |
| --- | --- | --- |
| Width | 32-bit | 64-bit |
| Registers | 16 general | 32 general, 8 predicate |
| Implementation | `C1`, fixed five stage | `A1`, five stage, fully plugin-composed |
| Memory | Fixed latency, no backpressure | Fixed latency, backpressure-ready pipeline |
| Structure | One component per block | Every part is a plugin |
| Spec | [docs/isa.md](docs/isa.md) | [docs/axiom/isa.md](docs/axiom/isa.md) |
| Microarchitecture | [docs/microarchitecture.md](docs/microarchitecture.md) | [docs/axiom/microarchitecture.md](docs/axiom/microarchitecture.md) |

CORE-32 came first and is complete and frozen. Axiom-64 is the current work;
its plan is in [docs/axiom/roadmap.md](docs/axiom/roadmap.md).

## Quick start

Requires a JDK, sbt, and Verilator.

```sh
sbt test                              # the whole verification suite, both architectures
sbt "Test/runMain axiom.AxiomDemo"    # assemble, disassemble and run an Axiom-64 program
sbt "Test/runMain core.Demo"          # the same for CORE-32
sbt "runMain axiom.GenerateAxiomSoc"  # emit generated/AxiomSoc.v
make help                             # the rest of the targets
```

---

# Axiom-64

A 64-bit load/store architecture. Thirty-two general registers, eight predicate
registers, fixed 32-bit instructions, no condition codes, no delay slots, and
no prefix or compressed encodings.

Its design was argued out rather than inherited, and the decisions that cost
something are marked as such in the spec. Three worth stating here:

**Five-bit register fields.** Sixty-four registers would cost three more bits on
a three-operand instruction, which is exactly what makes the load and store
pair forms unencodable in 32 bits.

**Predicate registers, but not predication.** Compares write `p0` to `p7` and
only select and branch read them. There is deliberately no predicate field on
general arithmetic: a false-predicated instruction still consumes a rename slot,
a predicated write implicitly reads its own destination, and the encoding cost
lands on every instruction in the program. Spending four bits there would take a
load's reach from 64 KiB down to 4 KiB.

**A 16-bit move with a two-bit shift.** Any 64-bit constant costs at most four
instructions, so no prefix instruction is needed and nothing in the
architecture is wider than 32 bits.

```
 bit  31        26 25    21 20    16 15    11 10                     0
     +------------+--------+--------+--------+-----------------------+
     |   opcode   |   rd   |   rn   |   rm   |      sub-fields       |
     +------------+--------+--------+--------+-----------------------+
```

Field positions never move, and the opcode map is structured so decode stays
flat: `opcode(10 downto 6)` is already the ALU function code, `opcode(3)`
separates loads from stores within the memory class, and there is no secondary
function field anywhere.

The memory model is weak and other-multi-copy-atomic, with acquire and release
on dedicated zero-displacement encodings rather than two bits taxed onto every
load and store. Far-atomics are mandatory. See spec sections 3 and 4.

## Everything is a plugin

The top-level component owns three things: the interface, a parameter database,
and a plugin host. The pipeline, the register file, every execution unit and
the memory itself all arrive as plugins.

```scala
def base: Seq[Hostable] = Seq(
  new PipelinePlugin, new PcPlugin, new FetchPlugin, new DecoderPlugin,
  new RegFilePlugin, new PredicateFilePlugin, new AluPlugin, new CmpPlugin,
  new SelectPlugin, new BranchPlugin, new LsuPlugin, new SystemPlugin,
  new TrapPlugin, new TcmPlugin)
```

Plugins agree on things three ways and no others, all from
`spinal.lib.misc`:

- **Payloads** (`misc.pipeline`) are typed keys, not signals. Two plugins agree
  on a value without either knowing the other exists, and a value is only
  carried through the stages that read it.
- **Services** are plain Scala traits looked up by type. The ALU does not know a
  register file exists; it knows something accepts a result. An execution plugin
  *claims* a set of opcodes and gets back a one-hot select, and anything nobody
  claims is an illegal instruction.
- **The database** (`misc.database`) holds elaboration parameters as blocking
  keys, so plugins need not be constructed in dependency order.

Ordering is three Fiber phases: `during setup` registers services and claims,
`during build` creates hardware, and `during patch` connects the stages once
everybody has finished using them.

Using `CtrlLink` for each stage is what buys backpressure, and the load/store
unit uses all three arbitration primitives for three different reasons:
`haltWhen` for the interlock and for an atomic's second pass, `duplicateWhen`
for a pair, which really is two operations because it writes two registers, and
`throwWhen` for the branch shadow and for traps.

---

# CORE-32

The earlier architecture: 32-bit, sixteen registers, four-bit register fields
buying an 18-bit immediate on two-operand instructions. Complete and frozen.
`C1` is a conventional five stage pipeline with hand-written stage registers,
full forwarding and a single-cycle load-use interlock.

```scala
val program = Assembler() { a => import a._
  li(a1, 24)
  movi(t0, 0); movi(t1, 1)
  label("loop")
  beqz(a1, "done")
  add(t2, t0, t1); mov(t0, t1); mov(t1, t2)
  addi(a1, a1, -1)
  jmp("loop")
  label("done")
  mov(a0, t0)
  halt()
}
```

---

# Verification

Every test that runs a program runs it twice: once on the RTL under Verilator,
once on an interpreter written separately from the specification. The two are
compared on all registers, all predicates, the stop status, the stopping PC, the
retired instruction count, and every memory word the test asks about.

Agreement between the two only proves they read the specification the same way,
so directed tests add hand-computed expectations, and the whole-program tests
check against oracles computed independently in Scala, including a CRC32 checked
against `java.util.zip.CRC32`.

Random programs terminate by construction, because every branch and jump goes
forward, and never trap, because every address is masked into a scratch region
with the alignment its access size needs in every addressing mode.

When a random program does mismatch, the harness first compares the executed
instruction streams, which localises any control flow bug exactly, and if those
agree it bisects the program to find the shortest prefix whose final state
already disagrees. That turns "one of these programs is wrong" into "this
instruction, at this address". It found four real bugs in A1, listed in the
roadmap.

To capture waveforms, set `CORE_WAVE=1` or `AXIOM_WAVE=1`; FST files land in
`simWorkspace/`.

## Layout

```
hw/spinal/core/      CORE-32 RTL, encoding and assembler
hw/spinal/axiom/     Axiom-64 encoding, assembler and top level
hw/spinal/axiom/plugins/   every part of the A1 core
hw/test/scala/core/  CORE-32 reference model, harness and suites
hw/test/scala/axiom/ Axiom-64 reference model, harness and suites
docs/                CORE-32 specification and microarchitecture
docs/axiom/          Axiom-64 specification, microarchitecture and roadmap
```

## Status

CORE-32 is finished. Axiom-64 implements the whole Base profile: arithmetic
including the 32-bit forms, constant formation, compares and select, branches,
every memory form including pairs, the three addressing modes, ordered accesses,
far-atomics, fences and traps. There is no privileged mode, no divide, no tile
unit, and both memory ports are still fixed latency. See
[docs/axiom/roadmap.md](docs/axiom/roadmap.md).
