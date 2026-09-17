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
| M5 Stallable memory | Done |
| M5b First level caches | Done |
| M7 Divide | Done |
| Performance, measured | Done |
| M6 Privilege and traps | Next |
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

Measured first against a memory that charged its full latency for every
doubleword, this table said shorter was always better, by a wide margin: a long
line was simply more full-price accesses. That was a fact about the model. The
memory pipelines now, so a refill costs the latency once plus a cycle a word,
and the same measurement says:

| Line | Tight loop | Sweep |
| --- | --- | --- |
| 16 B | 643 | 16,435 |
| 32 B | 649 | 16,544 |
| 64 B | 644 | 16,792 |
| 128 B | 660 | 16,448 |

Flat, which is the honest answer for a memory that pipelines but does not burst:
a longer line costs a little more per miss and takes a few more misses away, and
the two nearly cancel. A memory that bursts would tip it towards longer lines,
so this is still the parameter to re-measure against a real one before picking a
number, but it is no longer measuring the wrong thing.

### What they cost, and a lesson about believing the first number

The same system with and without them, on an LFE5U-45F. Single seeds, so the
frequencies are inside the noise band and none of the differences between them
is a result; the area is the point.

The first measurement said the caches cost sixteen block RAMs where the
arithmetic said four, and the explanation written down for it was that a
sixty-four bit word with byte enables cannot pack densely. That was true about
the part and wrong about the design, because nothing had asked whether the
design needed byte enables. Three things did not:

| | LUT4 | DP16KD | Distributed RAM |
| --- | --- | --- | --- |
| no caches, as first written | 9,732 | 48 | 192 |
| no caches, after | 9,734 | **32** | 192 |
| 4 kB + 4 kB, as first written | 11,381 | 64 | 192 |
| 4 kB + 4 kB, after | 11,494 | **36** | 208 |

**A third read port replicates the whole array.** The debug access had its own
port on the memory behind, which reads better and costs three times the memory:
an ECP5 block RAM has two ports, so a third read makes yosys build three
copies. Sharing it with a channel that is idle whenever the debug access is
active costs nothing and saves sixteen.

**A mask that is a signal is byte enables even when it is always all ones.**
The instruction cache only ever writes whole refill words, but it went through
the same masked write as the data cache, so it was mapped as nine-bit slices
for a granularity it never used.

**The data cache needs sub-word writes and not byte enables.** Every command
already reads the array on the cycle it is accepted, a store included, and the
lookup cycle is one later, so the old word is on hand and the store can be
merged and written full width. Nothing can have touched it in between, because
a store already refuses the next command for exactly that cycle.

Between them the cached system went from sixty-four block RAMs to thirty-six,
and the caches themselves from sixteen to four. Nothing about the part changed.

**It also withdrew a recommendation.** The sizing sweep said to spend the
budget on the data cache, and the area measurement appeared to agree: one
kilobyte of instruction cache with four of data saved eight block RAMs over
four and four. After the fixes both cost thirty-six, because the saving had
been the instruction cache's unnecessary byte enables rather than its size.
The sizing result stands — the data cache is what the workload needs — but
there is no longer an area reason to shrink the instruction cache.

The general lesson is worth more than the twenty-eight block RAMs. A synthesis
number is a measurement of the design *as written*, and the first explanation
that fits it will usually be about the part, because that is the part of the
system that feels fixed. It is worth asking what the RTL asked for before
concluding anything about what the silicon can do.

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

## M7 Divide — done

Divide and remainder, signed and unsigned, in full and 32-bit widths: eight
forms under one opcode, with bit 0 selecting unsigned, bit 1 the remainder and
bit 2 the 32-bit form.

**It took a primary opcode rather than four of the ALU register form's
remaining sub-function codes.** Eight forms do not fit in four codes, and
dropping the 32-bit forms to make them fit would put every C `int` division
behind a pair of shifts, which is the cost the W forms exist to avoid
everywhere else.

### The two results that are decisions rather than consequences

**Division by zero does not trap.** The quotient is all ones and the remainder
is the dividend. Nothing else in this architecture traps on an arithmetic
result — an addition that overflows does not — and making division the
exception buys a trap cause, a second way for an instruction to fail late, and
an argument every compiler has to have with the hardware.

**Signed overflow does not trap either.** The one case is the most negative
value divided by minus one, whose true quotient is one larger than the format
holds. The quotient is the dividend unchanged and the remainder is zero, which
is the two's complement answer modulo 2^64 and so what every other overflowing
operation here already returns.

Both match RV64M deliberately. A compiler back end already knows these two
cases and already knows not to guard them; choosing different answers would put
a test in front of every division to buy nothing. They are defined once, in
`Isa.divide`, which the reference model, the unit tests and the documentation
all read, so none of them can drift from the others.

### The divider

Restoring, one bit per cycle, sixty-five cycles for any operands. Radix-4 or
SRT would quarter or halve that and cost a quotient-digit selection table, a
redundant representation and a much harder correctness argument; on a core that
is routing bound and where division is rare, that is the wrong trade.

Both operands are made non-negative on the way in and the signs applied on the
way out, so there is one unsigned iteration rather than four signed ones. The
remainder takes the sign of the dividend, which is what keeps
`(a / b) * b + (a % b) == a` true for negative operands.

**It holds the execute stage rather than declaring a late result.** This is the
first unit in the core that cannot say when it will be finished, and it is why
the stall protocol came first: no fixed number of stages would cover sixty-five
cycles. Holding execute stops everything behind it, which is the honest cost of
not building an out-of-order machine, and what it buys is that the result is
ordinary — available in execute like an ALU result, forwarded the same way,
with nothing else in the core needing to know that division is slow.

### What the tests caught

The divider is driven at its own ports before it goes near the pipeline, which
is the lesson from the caches. What that left for the core-level tests were
three things a component test cannot see: a plugin constructor that read a
database parameter before the database was in scope, a decoder that did not
know the new opcode writes a register and reads two, and the conformance tests
correctly objecting that a reserved opcode had become defined.

That last one is the check earning its keep. A reserved slot quietly becoming
defined changes the meaning of a binary that relied on it trapping, so the
count of reserved opcodes is asserted rather than assumed, and it went from
twenty-seven to twenty-six here on purpose.

## Performance, measured — done

The core computed the right answers at a known number of cycles, and nothing
said where those cycles went. That is the information needed to decide what to
optimise, so it was built first and everything after it was chosen by the
numbers rather than by a prior.

### Cycle accounting

A `PerfService` hands each plugin one counter for the one stall it causes:
fetch starvation, the register interlock, waiting for load data, waiting for the
bus to accept a command, the divider, redirects taken, and a refill in each
cache. They leave through one indexed port rather than one output each, because
the debug interface is a fixed shape shared by both top levels and a counter per
event would put the event list into it.

Two rules make the numbers mean something. A counter is claimed with no driver
and wants exactly one, so a counter claimed and left undriven is an elaboration
error rather than a zero that reads like a measurement. And a stall is counted
only on cycles the stage could otherwise have moved, so a cycle lost deeper in
the pipeline is charged once, to whoever caused it.

The first reading, on a tightly coupled memory, with five workloads shaped
around different ways of spending cycles:

| Workload | Cycles | Retired | IPC | Fetch | Interlock | Redirect |
| --- | --- | --- | --- | --- | --- | --- |
| straight-line | 1,008 | 706 | 0.70 | 1 | 0 | 99 |
| sum-of-squares | 236 | 168 | 0.71 | 1 | 40 | 21 |
| memcpy | 774 | 452 | 0.58 | 1 | 128 | 63 |
| dot-product | 485 | 259 | 0.53 | 1 | 128 | 31 |
| branchy | 904 | 483 | 0.53 | 1 | 128 | 96 |

The cycles no counter claims are the branch shadow, and there were three per
redirect: around thirty per cent of a loop of independent adds, which made it
the largest single cost in the core. The interlock was second. **Stores cost
nothing here**, which took the store buffer off the top of this roadmap:
write-through does cost a memory access per store, but on this memory nothing
in the pipeline was waiting for it. Behind a cache they did cost something, and
the section on the caches below says what that turned out to be.

### Predicting branches, in two steps

Decode already knows the target of a relative branch, and the sign of the
displacement is a good first guess at its direction: backwards is a loop,
forwards is an error check or an else branch. So decode redirects for a
backward conditional branch as it already did for an unconditional one, the
guess travels with the instruction, and execute redirects only when the two
disagree — to the target if it was taken after all, to the next instruction if
it was not. One wire, no table, and a taken loop branch costs one instruction
instead of three.

That rule is wrong every time for the other common shape: a forward branch that
is nearly always taken, which is what a filter, a bounds check or an error path
looks like. A workload built around one spent a quarter of its cycles in the
branch shadow, so the guess comes from **thirty-two two-bit counters indexed by
the program counter**, read in decode and moved one step towards what happened
in execute. No tag: two branches sharing an entry confuse each other, which
costs cycles and never correctness. Cold entries read weakly not taken, so a
loop is mispredicted once on the way in rather than once per iteration, and two
bits mean one exception does not flip a settled prediction. It is flip-flops
rather than a memory, because sixty-four registers is cheaper than a
distributed RAM whose initial value the part does not promise to keep.

### Folding a branch in fetch

Folded in decode, a taken branch costs one instruction rather than three, and
that one was the largest cost left: a hundred and one redirects and a hundred
and seven wasted cycles in a hundred-iteration loop, one per time round.

So the front end fetches from a table instead of adding four. Thirty-two
entries indexed by the program counter hold where the branch there went when
decode last folded it, read alongside the counter that says whether it goes. A
branch the table knows costs nothing at all: the instruction fetched after it
is the one at the target.

**The table has no tag and does not need one.** The front end acts before
anything has read the instruction, so its answer is a guess in every case, and
what makes it safe is that the guess travels with the instruction: decode knows
what the instruction is and where it really goes, and redirects whenever the
two differ. That covers a prediction made from an entry left by a different
address, a branch whose direction has changed, and an indirect jump, whose
target is a register and still resolves in execute. A tag would prevent one of
those three and cost more than all of them.

An unconditional branch has its counter driven to the top rather than nudged.
It has no direction to learn and execute never resolves one for it, so left to
the counters it would sit at the cold value and never be folded in fetch.

### Redirecting when the branch knows, not when it may leave

A redirect has to happen exactly once per branch, and the obvious way to say
"once" was to fire on the cycle the branch left its stage. Leaving is the wrong
event to wait for: whether a stage may move is the whole arbitration network,
every halt in every stage below it, and the measured critical path of the core
was a stall in the load/store unit travelling back through that network into
the program counter. A register saying this branch has already redirected says
"once" without asking about readiness, and the front end starts fetching the
target while the stall that was holding the branch is still being served.

Three things had to be right, and each broke something first:

- **Decode has to know its instruction arrived.** Its instruction payload is
  wired straight to the fetch unit's response buffer, so a stalled decode with
  an empty buffer holds a valid transaction and the previous instruction's
  bits. Folding a branch out of those threw away the fetch in progress every
  time; behind a cache the front end never got a line in, at twenty times the
  cycles.
- **The instruction that asks for a redirect is not part of the shadow it
  creates.** From the next cycle its own fetch generation is one behind and the
  compare that kills the shadow would kill it too, taking a link register write
  with it.
- **A bubble never leaves.** Clearing the already-redirected flag only on
  departure leaves it set while the stage holds nothing, and the next real
  branch to arrive had its redirect swallowed. That one was caught by the
  random program cosimulation, on two conditional branches four bytes apart.

### Collecting a store's data a stage late

A store's data is the one operand nothing computes with: read in the read stage,
handed to the bus in the memory stage two stages later. So a store may start
while the instruction producing its data is still in execute and collect the
value in memory, where that producer has reached writeback. The interlock lets
it past for that producer and no other, because one further ahead will have
committed and gone by then.

It is not a second bypass network. One source, one comparator, one multiplexer,
because nothing overtakes anything: the only instruction that can be writing a
register a memory-stage instruction read is the one exactly one stage ahead.

### What it came to, on a tightly coupled memory

| Workload | Before | After | IPC |
| --- | --- | --- | --- |
| straight-line | 1,008 | 717 | 0.70 → 0.98 |
| sum-of-squares | 236 | 217 | 0.71 → 0.77 |
| memcpy | 774 | 464 | 0.58 → 0.97 |
| dot-product | 485 | 366 | 0.53 → 0.71 |
| branchy | 904 | 631 | 0.53 → 0.77 |
| filter | — | 690 | 0.76 |

Two of them now retire an instruction every cycle but three. What is left is
dependencies rather than the pipeline: dot-product's hundred and twenty-eight
interlock cycles are a load feeding a multiply and a multiply feeding an add,
both of them real, and the same load-use pair is what branchy and filter are
waiting on. Filter was written for this work and has no before.

### Three things behind the caches

**The memory pipelines.** A line refill asked for one word, waited the full
latency, then asked for the next, so a four word line cost four times the
latency. That is not what a memory with a fixed access time does, and it is
what made the line-size measurements above wrong. It now takes a command every
cycle and answers in order, and the refill engine asks for the whole line while
the first answer is still on its way. The address travels down the pipeline
rather than the data, because an address is twelve bits and a word is
sixty-four.

**Writes are posted.** A write used to hold the memory for a full read latency
and refuse everything meanwhile, so a copy loop spent a hundred and ten of its
nine hundred cycles waiting for the bus to take a store. Nothing about a store
needs that: a controller with a write queue takes a posted write in a cycle. The
one write that cannot go straight in is one to a word some read already on its
way back is about to sample, and a comparison against the addresses in flight
catches exactly that case, so a loop that reads one array and writes another
never waits.

**The word that missed is asked for first.** Both refill pointers start at the
missing word and wrap round the line, so the answer the core is waiting for is
the first one back rather than the one at the end of the line. The rest arrives
behind it.

Behind 4 kB caches over a memory of latency eight:

| Workload | Before | After |
| --- | --- | --- |
| straight-line | 1,084 | 743 |
| sum-of-squares | 348 | 250 |
| memcpy | 1,480 | 708 |
| dot-product | 1,169 | 600 |
| branchy | 1,571 | 816 |

**A caveat the counters made visible.** "Stores cost nothing" is true on a
tightly coupled memory and was not true behind a write-through cache, where
they cost a tenth of a copy loop until the memory model stopped charging a full
latency for each one. The store buffer this roadmap used to list as the next
thing to build is still not the answer — the memory was — but the reason it
looked unnecessary was narrower than the first reading suggested.

### What is left, by the numbers

The counters say where the remaining cycles are, and they are mostly no longer
in the pipeline's own behaviour:

- **The load-use interlock**, a hundred and twenty-eight cycles of a four
  hundred cycle dot product. A load's data arrives in writeback because its
  command is issued in memory, so an instruction reading it one behind waits
  two cycles. Issuing the command from execute would make it one, at the price
  of putting the address adder in front of the memory and moving the atomic and
  pair sequencing a stage earlier. That is a load/store unit rewrite and it
  should be measured against the frequency it costs before anybody believes in
  it.
- **Cache misses**, which dominate everything behind a cache and are what
  associativity, a second level, or prefetching would address.
- **The multiply result** was the third, and turned out to be free: the adder
  tree that finishes it sits in the memory stage, so the value exists there and
  was simply being offered a stage later than it existed. Offering it from
  memory took a dot product's interlock from a hundred and twenty-eight cycles
  to ninety-six. The worry is that the tree ends up in front of the forwarding
  multiplexer, which is what splitting the multiplier across two stages was
  meant to avoid. It has to fit inside a cycle either way; what the multiplexer
  adds on top of it is measured in the table below, and if the multiplier ever
  turns up in the critical path this is the first thing to take back out.

Neither of the two that are left is a point fix, and neither is worth doing
without a frequency measurement beside the cycle count.

### What it cost on the part

Four seeds each at a 200 MHz target on an LFE5U-45F, out of context, the core
with its buses brought out. The baseline is the same core at the commit this
work started from, generated and placed the same way, because the 58.7 MHz in
the frequency section above belongs to a core with no caches and no divider and
is not the thing to compare against.

| | fmax, 4 seeds | range | LUT4 | Flip-flops |
| --- | --- | --- | --- | --- |
| before | 44.1 | 40.6 to 45.4 | 10,740 | 3,347 |
| after | 49.0 | 46.4 to 53.9 | 11,870 | 3,647 |
| after, counters left out | 48.6 | 47.5 to 49.3 | 11,360 | 3,417 |

Ten per cent more logic and, if anything, slightly more clock. Taken with the
cycle counts, memcpy does the same work in about 1.6 times fewer seconds.

**The counters are free in time and cost about five hundred LUT4.** They were
not free when first built: the critical path went from a stall signal in one
plugin, across the part, into a thirty-two bit carry chain, and the whole core
measured at 42 MHz. Sampling the event into a register beside the counter fixed
it, which is the general lesson — instrumentation that reads a signal from
somewhere else should register it before doing anything with it — and the
parameter to leave them out stays, because five hundred LUT4 is real on a small
part.

**The critical path is now the load/store unit reaching the program counter**,
four nanoseconds of logic and seventeen of routing, in every seed of every
variant. That is the arbitration chain: a stall in the memory stage propagating
back through the pipeline's ready network into the branch redirect. It is the
next thing to restructure, and being eighty per cent routing it wants a
structural change rather than a shallower expression.

### Three bugs that needed a memory able to answer and accept at once

All three were latent and unreachable while the memory refused a command on the
cycle it answered one.

**Fetch and the load/store unit forgot a command accepted on the cycle an
answer arrived.** Both cleared their outstanding-response flag on the answer and
set it on acceptance, in that order, so the acceptance was lost. Two commands
then went out where one was tracked, the answers stopped lining up with the
transactions waiting for them, and every instruction after that point was
decoded against the wrong address. Acceptance has to win over the answer.

**Fetch armed its orphan from that same flag**, which does not read back until
the cycle after acceptance, so a transaction thrown on the cycle it arrived left
an answer nobody was going to drop.

**The cache's test bench sampled the command before the clock edge**, which
reads the previous cycle's value. That was invisible while the cache held a
command up until it was answered, and hid half a refill's commands the moment it
stopped.

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
