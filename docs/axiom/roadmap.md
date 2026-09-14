# Axiom-64 implementation roadmap

The order of work, and why it is that order. Each milestone has an exit
criterion that is a test run, not a judgement.

Two rules hold throughout, and they are what keep the thing honest:

1. **Every architectural change lands in three places at once**: the
   specification, the encoding contract, and the reference model. The RTL comes
   after. If the spec is ambiguous the RTL cannot be written, which is the
   point.
2. **Nothing is claimed that is not tested.** A configuration flag nobody
   exercises is a flag that does not work.

---

## Status

| Milestone | State |
| --- | --- |
| M0 Encoding contract and specification | Done |
| M1 Assembler and reference model | Done |
| M2 Plugin skeleton | Done |
| M3 Base profile execution | Done |
| M4 Verification | Done |
| M5 Stallable memory | Next |
| M6 Privilege and traps | Planned |
| M7 Divide | Planned |
| M8 Compute profile and the tile unit | Planned |
| M9 Superscalar | Planned |

---

## M0 Encoding contract and specification — done

`Isa.scala` and `docs/axiom/isa.md`. One place where the encoding is defined,
with no SpinalHDL dependency, so the RTL decoder, the assembler and the
reference model cannot drift apart.

**Exit:** the encoding round-trips through its own extractors, and exactly the
documented opcodes and sub-functions are legal.

## M1 Assembler and reference model — done

A two-pass assembler with labels, and an interpreter written from the
specification rather than from the RTL, in a deliberately different style so a
misreading is unlikely to appear identically in both.

**Exit:** `AssemblerSpec`, 40 tests, no hardware involved.

This milestone found six real gaps in the specification: an opcode count that
did not match its own table, unspecified sign extension for narrow ordered
loads and for word atomics, an operand order that contradicted the encoding,
and two under-specified register aliasing cases. All six were fixed in the spec
before any RTL existed, which is the cheapest place to fix them.

## M2 Plugin skeleton — done

`PipelinePlugin`, `PcPlugin`, `FetchPlugin`, `DecoderPlugin`, `RegFilePlugin`,
and the three mechanisms plugins use to agree on things. See
`microarchitecture.md`.

**Exit:** the core elaborates to Verilog with no pruned signals.

## M3 Base profile execution — done

Arithmetic, constants, compares, select, branches, every memory form including
the pairs, the ordered accesses, the atomics, and the traps.

**Exit:** a real program runs and halts.

## M4 Verification — done

Randomized co-simulation against the reference model, comparing all thirty-two
registers, all eight predicates, the stop status and PC, the retired count and
the whole scratch region. Programs terminate by construction because every
branch goes forward, and never trap because every address is masked into an
aligned scratch region in every addressing mode.

**Exit:** 125 random programs match exactly, plus the directed suites.

Four real bugs came out of this, and they are worth recording because each one
is a category rather than a typo:

| Bug | Category |
| --- | --- |
| A pair counted as two retired instructions | A multi-transaction instruction needs a "last beat" marker |
| A byte mask shifted by a bit offset | Two different units for the same quantity |
| A consumer after an atomic read a stale value | A multi-cycle producer breaks a one-cycle interlock |
| A stalled consumer lost a forwarded value when its producer retired | Capturing operands before the stall point is unsound |

The last one is the interesting one. It is invisible to directed testing unless
you happen to write a program where a producer commits while its consumer is
held by unrelated backpressure. The fix, reading the register file in execute
rather than decode, removed the whole class rather than the instance.

---

## Measured on an ECP5

`synth/` runs yosys and nextpnr out of context and attributes every primitive
and every critical path segment back to the plugin that created it. Attribution
is only possible because SpinalHDL keeps plugin names on its signals, which is
also why the RTL uses named `Area`s and `setCompositeName` rather than bare
`setName`: a bare name drops the prefix every report depends on.

Everything below is an LFE5U-45F at a 100 MHz target, measured rather than
estimated, one change at a time.

| Step | Change | LUT4 | Routed fmax |
| --- | --- | --- | --- |
| 0 | baseline | 34,129 | 13.4 |
| 1 | register file as banked distributed RAM | 13,375 | 21.98 |
| 2 | one funnel shifter instead of eight, naming fixes | 12,999 | |
| 3 | multiply split across execute and memory | 12,817 | 28.60 |
| 4 | predicate false path removed, one-hot multiplexers | 14,530 | 29.79 |

**Step 1** was the big one. Thirty-two 64-bit registers in flip-flops with three
asynchronous read ports become three 32-to-1 multiplexers 64 bits wide, which
measured at forty per cent of the core. Distributed RAM has one write port and
the file needs two, so it uses one bank per write port plus a live value table.

**Step 2** replaced eight shifters with one funnel shifter. `{hi, lo} >> n`,
keeping the low half: choosing what goes in `hi` and `lo` turns one right shift
into a left shift, an arithmetic shift or a rotate, and placing a 32-bit operand
in the right half of `lo` covers the W forms too.

**Step 3** was the largest frequency win. A 64 by 64 multiply is sixteen DSP
blocks and a four-level adder tree, and it measured at 23.8 ns of a 45.5 ns
path: half the cycle, for one instruction. It is now four 33 by 33 partial
products in execute and the sum in memory, which costs the result one cycle and
nothing else, because the register file interlock already handles a producer
whose value is not ready when execute wants it. A multiply became a load.

**Step 4** found a false path, which is the kind of thing the flow is for. The
predicate file forwarded from the execute stage, and the only instruction at
execute is the reader itself, so that forward could only ever feed an
instruction its own result. Dead logic, but not free: it chained the compare
unit's 64-bit comparison straight into the select unit's read, 14 ns of a 35 ns
path.

The one-hot multiplexers in the same step did not pay: 13 per cent more area for
4 per cent more frequency, because yosys did not merge the mask and OR pairs as
hoped. Measured and kept only where the depth is on the critical path.

### The remaining path, and a parameter instead of an argument

After step 4 the path is a block RAM read feeding the multiplier's partial
products through the writeback-to-execute forward. Forwarding a load's result
out of writeback chains the memory read, the result multiplexer and the
forwarding multiplexer onto the front of whatever the consumer does.

Turning that forward off costs a second interlock cycle on a load-use pair. How
much that costs depends entirely on the code:

| Workload | Forward on | Forward off | Cost |
| --- | --- | --- | --- |
| Random programs | 0.85 IPC | 0.85 IPC | under 1% |
| A loop with a load feeding an add | 0.72 IPC | 0.66 IPC | 8.6% |

The random figure is the misleading one, and finding that out was worth the
measurement: the generator put address arithmetic in front of every access, so
producers and consumers almost never landed next to each other. The generator
now emits explicit dependent pairs, which both fixes the measurement and covers
a hazard shape it was missing.

`FORWARD_LATE_FROM_WRITEBACK` is therefore a parameter rather than a decision,
and both settings are measured rather than argued about.

### What is left

The floor for a 64-bit core on this part is the adder. A 64-bit carry chain on
an ECP5 is around thirty CCU2C in series, so a single-cycle 64-bit add is most
of a 10 ns cycle on its own. Reaching 100 MHz means the ALU gets a stage to
itself and nothing else shares it, which is a pipeline split rather than the
tidying done so far. That is why the next milestone is the memory protocol and
not more of this.

## M5 Stallable memory — next

Today both memory ports have a fixed one-cycle latency and no backpressure.
That is the largest simplification in A1 and the first thing that should
change, because everything after it depends on being able to wait.

**Work:**
- give `MemoryService` a ready signal and a response valid
- fetch: hold the program counter and tolerate a late instruction
- load/store: `haltWhen` on an outstanding response rather than assuming one
  cycle
- a `CachePlugin` behind the same service, so the tightly coupled memory and
  the cache are interchangeable

**Why first:** the control links already carry backpressure, so the pipeline
does not change. What changes is that the two plugins that touch memory stop
assuming. Divide, caches and any real interconnect all wait on this.

**Exit:** the random co-simulation passes unchanged with a memory plugin that
inserts random stalls. That is the test that matters, and it is cheap because
the harness already exists.

## M6 Privilege and traps

The three-tier model from the design: host, guest, user, with nested paging.

**Work:** a trap handler and a vector, control registers, the privilege state
machine, an MMU plugin, and the context identifier that the tile unit will
later need.

**Open questions to settle before writing code:**
- where a trusted execution environment lives, since vendors will otherwise
  bolt one on and reintroduce the fragmentation profiles exist to prevent
- whether nested virtualisation is in scope for the first privileged profile
- the ASID recycling protocol, which is a correctness and security requirement
  rather than an optimisation

## M7 Divide

Deliberately after M5. A multi-cycle divider needs the stall protocol, and
adding it before the memory system can stall would mean building that protocol
twice.

**Exit:** random co-simulation with divide in the instruction mix, including
division by zero and the signed overflow case, whose results the spec must
define first.

## M8 Compute profile and the tile unit

The largest piece, and the one with the most unresolved design.

**Settled by the design discussion:**
- tile state is saved lazily, never eagerly, with ownership tracked by an
  opaque context identifier qualified by a virtual machine identifier
- the unit quiesces on a privilege transition, stopping issue and draining
  faultable traffic without saving state, so exceptions stay precise
- save and restore are separate restartable instructions, with their progress
  in an architecturally visible register, or the save buffer is pinned so they
  cannot fault at all
- tile geometry is configurable rather than frozen, and the block-scaled dot
  product is parameterised rather than committed to one numeric format

**Still open:**
- whether tile accesses are coherent and multi-copy atomic, and when another
  thread observes them. The intra-thread ordering rule is written down; the
  cross-thread one is not, and it is correctness-visible
- the interaction between in-flight tile traffic and TLB maintenance from
  another thread
- what bounds the quiesce, since a tile load missing to memory sets the
  worst-case interrupt latency and the real-time claim depends on it

**These are specification questions, not implementation questions.** They come
before any RTL, by rule 1.

## M9 Superscalar

The flat encoding and the absence of condition codes were justified partly by
how cheaply they scale to a wide decoder. That claim is currently untested. A
two-wide in-order version is the cheapest way to test it, and the pipeline API
already supports multiple lanes through payload sub-keys.

**Exit:** two-wide issue passing the same random co-simulation, with a measured
instructions-per-cycle improvement on the existing program suite.

---

## What the verification flow looks like from here

Every milestone reuses the same three layers, which is why adding to the
architecture stays cheap:

1. **Pure Scala tests** on the encoding and the assembler. No hardware, so they
   run in under a second and catch specification mistakes first.
2. **Directed tests** with hand-computed expectations. These are the only layer
   that can catch the model and the hardware sharing a misreading.
3. **Randomized co-simulation.** The generator gains a case per instruction
   class; the comparison never changes.

The failure path matters as much as the tests. A co-simulation mismatch first
compares the executed instruction streams, which localises any control flow bug
exactly, and if those agree it bisects the program to find the shortest prefix
whose final state already disagrees. That turns "125 random programs and one of
them is wrong" into "this instruction, at this address".
