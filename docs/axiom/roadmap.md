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

One caveat, learned the hard way and recorded in the next section: these are
single placement seeds at a 100 MHz target, and the seed alone is worth around
eight per cent. Steps 1 and 3 are far larger than that and stand; the smaller
steps between them are indicative rather than established, and the section
after this one re-measures the end result properly.

| Step | Change | LUT4 | Routed fmax |
| --- | --- | --- | --- |
| 0 | baseline | 34,129 | 13.4 |
| 1 | register file as banked distributed RAM | 13,375 | 21.98 |
| 2 | one funnel shifter instead of eight, naming fixes | 12,999 | |
| 3 | multiply split across execute and memory | 12,817 | 28.60 |
| 4 | predicate false path removed, one-hot multiplexers | 14,530 | 29.79 |
| 5 | bare core rather than the SoC, one-hot reverted | 12,861 | 34.69 |
| 6 | register read given its own stage | 12,173 | 41.50 |
| 7 | unconditional branches folded in decode, multiplier operands direct | 12,285 | 42.00 |

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

**Step 6** is the one that needed a pipeline change rather than tidying. With
the register read, the forwarding network and the ALU all in execute, the path
ran distributed RAM output, forwarding multiplexer, ALU, result multiplexer in
series. Splitting the read into its own stage gives each half roughly half the
path. The read and the forwarding move together: correcting a captured operand
in a later stage is the bug described above, not an option.

On its own the split was a wash. The clock went up by 19.6 per cent and the
demo program went from 233 cycles to 275, an 18 per cent loss, because the
extra stage costs a cycle of branch shadow and a cycle of load-use interlock.

**Step 7** paid for it. An unconditional relative branch has its target in
decode — program counter plus displacement, no register involved — so it
redirects from there and kills one instruction instead of three. That brought
the demo back to 235 cycles, two more than the five-stage core, at a fifth more
clock: about 20 per cent more work per second overall.

Making the split correct also required two things the shallower pipeline had
been hiding, both of which are described in the microarchitecture document: a
producer's result does not necessarily exist in the stage the producer sits in,
and a load pair cannot name its second destination until its second memory pass
exists. Both were found by randomized co-simulation within minutes of the split
compiling, which is the return on having built the reference model first.

The critical path is now 87 per cent inside the ALU, in its second-operand and
result multiplexers, with the register file down to 7 per cent. That is a
better problem to have than the one in step 0, and it is the next thing to
attack.

### Chasing frequency, and how to measure it

Steps 0 to 7 were logic: find the deep thing, make it shallower. Past step 7
that stopped working, and the first thing that had to change was not the RTL
but the method.

**One placement seed is not a measurement.** The same design and the same flow
measured 54.8 to 60.1 MHz across five seeds, a spread of eight per cent. Three
conclusions in this document were drawn from single runs and all three were
wrong: that removing the debug read port was worth ten per cent, that `abc9`
was worth twenty-four, and that a narrower bypass network was worth trying.
Everything below is four or five seeds, reported as a mean with its range, and
nothing inside the noise is called a result.

**Ask for a target you cannot reach.** nextpnr places against the constraint,
so asking for 100 MHz and getting 49 is a different experiment from asking for
200 and getting 57. These are all at 200 MHz on an LFE5U-45F, out of context.

Measured like for like, start of the work against the end:

| | fmax, mean of 4 seeds | range | LUT4 | Demo cycles |
| --- | --- | --- | --- | --- |
| before | 34.0 | 32.9 to 34.9 | 10,544 | 233 |
| after | 58.7 | 56.9 to 61.0 | 8,644 | 235 |

Seventy-three per cent more clock for one per cent more cycles, and eighteen
per cent less logic. What produced it, in order of size:

**The register read got its own stage** and the second operand select moved
into it. With the read, the forwarding and the ALU in one stage the path ran
RAM output, forwarding multiplexer, ALU, result multiplexer in series. The
operand select in particular cost four nanoseconds before the adder even
started, most of it routing a one-bit control signal to sixty-four lanes.

**The multiplier was shaped to the part.** A MULT18X18D multiplies eighteen
bits by eighteen in one cell; ask for a 33 by 33 product and yosys builds a
cascade of four with adders between them, measured at 3.9 ns inside the DSP
and 2.6 routing between its halves. Sixteen-bit limbs plus a sign bit fit one
cell, so the same sixteen cells now do the work without cascading.

**The shifter was split across two stages.** A 128-bit funnel shift by a
seven-bit distance is seven multiplexer levels, as deep as the adder beside it.
Execute does the top three bits of the distance and memory the remaining four,
carrying seventy-nine bits between them rather than a hundred and twenty-eight.
A shift became a late result, like a load, which is affordable because shifts
are rarer than adds. Together with the DSP work this took the ALU from 3,902
LUT4 to 2,425 and the design from 12,450 to 8,596.

**The trap decode came out of the redirect path.** Once the datapath stopped
dominating, the critical path left it entirely and became a contract between
plugins: instruction, trap cause, the throws it drives, every stage's validity,
whether the branch is firing, the redirect, the fetch generation. Eight of its
seventeen nanoseconds were deciding whether an instruction traps, in front of a
branch that cannot trap. Asking a stage's downstream side whether an
instruction is leaving pulls in every reason it might be cancelled; asking the
upstream side asks only about backpressure, which is the actual question.

### What did not work, and why it is worth writing down

**Module boundaries do not exist.** The whole core elaborates to one flat
Verilog module, so there is no synthesis boundary to lose optimisation across.

**Logic depth is not the problem.** Collapsing a nineteen-case result
multiplexer into five classes bought 0.1 per cent, because `abc9` was already
restructuring that tree better than the hand did. It is kept for the area.

**The part is not too big.** At 33 per cent utilisation on a 45k it looked like
the placer had room to spread out. On a 25k at roughly sixty it measured the
same.

**A narrower bypass network is worse.** Removing base register forwarding
halves the multiplexer inputs on every operand and measured slower as well as
costing cycles. It stays a parameter, set the way the measurement says.

**Point fixes are finished.** The slack histogram says why. At 58 MHz several
hundred endpoints sit within 1.5 ns of the critical path, all the same shape:
register, distributed RAM or multiplexer, two or three more levels, register,
two thirds of it routing. Removing the worst path exposes the next one, which
is why taking eight nanoseconds out of the redirect path was worth one per
cent. Going meaningfully faster now means changing the whole front, not the
worst path.

### What 200 MHz would take

A 5.0 ns cycle, against measured cell delays from this netlist: 0.5 ns of
clock to output, 3.4 ns of carry chain for a 64-bit add, 0.2 ns of setup. That
is 4.1 ns before a single wire or multiplexer, and the adder measures 4.7 in
place.

**A single-cycle 64-bit add and 200 MHz are not compatible on an ECP5 -6.**
Not difficult: arithmetically unavailable. Reaching it means an adder split
across two stages, which makes every dependent instruction pay and changes the
assumption the rest of the microarchitecture is built on.

What is available, in increasing order of cost: a -8 part, worth fifteen to
twenty-five per cent and no engineering; removing a register file read port by
reading store data in memory rather than in the read stage, which attacks the
fan-out rather than the depth; and a nine or ten stage pipeline with a two
cycle ALU, which might reach 120 to 150 MHz for perhaps two thirds of the
instructions per cycle.

If the goal behind the number is throughput rather than clock, the two better
levers are both already on this roadmap: M9, since the flat encoding was
justified partly on how cheaply it widens, and caches, since at 58 MHz with a
one-cycle memory this core is not memory bound yet.

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

The frequency work has reached the point where the next step is architectural
rather than an optimisation, and that step is described above. It should wait
anyway: a deeper pipeline changes what the memory protocol has to tolerate, so
the protocol comes first.

## M5 Stallable memory — done

Both memory ports used to promise the word one cycle later and to hold it until
somebody read it. A tightly coupled memory can do both; a cache can do neither,
so everything after this milestone was waiting on it.

**The contract now.** A command is offered while `enable` is high and accepted
on a cycle where `enable` and `ready` are both high. An accepted read produces
exactly one response, later, marked by `rvalid`, and responses come back in
order. A write needs acceptance and nothing else, which keeps a store to a
tightly coupled memory at one cycle. Nothing is held: the core buffers each
response itself, one deep, because at most one command per port is outstanding.

That last part is by construction rather than by counting. A stage offers its
command only when the transaction holding it can move on, so a stalled pipeline
cannot run ahead of its own responses. In fetch that is also a correctness
requirement, not just a simplification: a transaction that sat in fetch holding
an accepted command would have its program counter moved under it by a
redirect, and would arrive at decode carrying the new counter, the new
generation, and the instruction from the old address.

**It costs nothing when the memory does not stall.** The demo program is 235
cycles either way.

**Three real bugs, all found by the stalling memory and all latent before it:**

| Bug | Shape |
| --- | --- |
| A branch executed the instruction it jumped over | A cancelled transaction does not fire, so it left its response in the buffer for the next one |
| A pair touched one address twice | Its second pass started when the pair arrived rather than when the first access left |
| A pair's base update went missing | Forwarding read a flag the pair clears on its first pass, so a consumer sampling then saw no base write |

The first is a distinction in the pipeline API worth knowing: `isFiring` is
`isReady && !isRemoved`, so a thrown transaction does not fire. Anything that
has to happen when a transaction *leaves*, rather than when it succeeds, wants
`isMoving`.

## M5b First level caches — done

A direct-mapped instruction cache and data cache behind the same
`MemoryService` the core already asked for, in front of a memory slow enough to
be worth caching. The core cannot tell: it asks for a port and gets one.

Direct mapped rather than set associative, which is a decision about the part
rather than about hit rates. A set associative cache needs a tag comparison per
way, a multiplexer on the data path behind it and a replacement policy to keep;
on a part where the core measures sixty per cent routing, all three land on the
wrong side of the trade.

Write-through with no allocation on a store miss: no dirty bits, no writeback
machine, no eviction. Every store goes to the memory behind, so a store is as
slow as that memory. A store buffer would hide that and is deliberately absent
until the cost is measured rather than assumed.

### What they are worth

Against the same memory with the caches taken out, on a loop that fits in the
instruction cache and rereads its data: **4.31 times**. A cache of zero bytes
passes the port straight through, which is what makes that the same memory
rather than a different one.

### How big, measured rather than guessed

Two workloads, one that fits in anything and one that does not, through a
memory that takes eight cycles. Cycles, lower is better:

| Geometry | Tight loop | Sweep |
| --- | --- | --- |
| no caches | 4,340 | 111,692 |
| 512 B each | 837 | 75,502 |
| 1 kB each | 837 | 75,502 |
| 2 kB each | 837 | 75,502 |
| 4 kB each | 837 | 21,038 |
| 8 kB each | 837 | 21,038 |
| tightly coupled memory | 724 | 18,556 |

Two things fall out. A working set that fits needs almost nothing, and past the
point where it fits, more is free but useless. And there is a knee: the sweep
touches more than two kilobytes, so nothing below four helps it, and four gets
within thirteen per cent of having no memory latency at all.

**The two caches are not worth the same.** Splitting a budget:

| Instruction | Data | Sweep |
| --- | --- | --- |
| 1 kB | 4 kB | 21,038 |
| 4 kB | 4 kB | 21,038 |
| 4 kB | 2 kB | 75,502 |
| 8 kB | 2 kB | 75,502 |
| 2 kB | 8 kB | 21,038 |

A kilobyte of instruction cache and four of data is as good as four and four;
eight and two is no better than four and two. The loops are small and the data
is not, so the data cache is where the block RAM belongs. That is worth knowing
before committing eight kilobytes evenly out of habit.

### Line size, and a caveat about the model

| Line | Tight loop | Sweep |
| --- | --- | --- |
| 16 B | 783 | 19,870 |
| 32 B | 837 | 21,038 |
| 64 B | 872 | 23,376 |
| 128 B | 1,016 | 23,342 |

Shorter is better here, and that result should not be believed outside this
model. The memory behind charges its full latency for every doubleword and
cannot burst, so a long line is simply more full-price accesses. Real memory
amortises a burst across a line and the curve turns over the other way. The
honest statement is that line size is the one parameter here whose measurement
is an artefact of the memory model, and it should be re-measured against a
bursting one before anybody picks a number from this table.

### Three bugs, all needing a memory that answers late

| Bug | Shape |
| --- | --- |
| A load's result was forwarded while the load still waited for memory | `availableAt` says which stage a value appears in; it cannot say whether it has appeared yet |
| The machine reported itself halted with an older instruction still in flight | Stopping is meant to be precise, and between the trap latching and the drain it was not |
| A redirect cancelling a transaction in decode before its instruction arrived hung the machine | The answer turned up with nobody to take it, the buffer filled, and fetch stopped asking |

The first is the interesting one, because it looks like the availability rule
from M5 and is not. That rule is about the pipeline and is fixed at
elaboration; this is about the cycle. Backpressure does not cover it either: a
stalled writeback still lets the read stage advance into an empty execute. A
producer can now declare a readiness signal, and the interlock holds a consumer
that would otherwise read a wire the memory has not driven yet.

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
