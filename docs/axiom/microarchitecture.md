# A1: the first Axiom-64 implementation

A1 is a six stage, in-order, single-issue pipeline implementing the Base
profile. Every part of it is a plugin.

This document is about the *structure*, because that is the part that differs
from an ordinary SpinalHDL core. The instruction semantics are in `isa.md`.

---

## 1. Everything is a plugin

The top-level component owns exactly three things: the external interface, a
parameter database, and a plugin host.

```scala
class AxiomSoc(...) extends Component {
  val io       = AxiomIo(log2Up(memWords))
  val database = new Database
  val host     = database on {
    AxiomParam.base(resetVector, memWords, withMultiplier, withAtomics)
    val created = new PluginHost()
    created.addService(new InterfaceService(io))
    created.asHostOf(plugins)
    created
  }
}
```

There is no pipeline in that component, no register file, no ALU and no memory.
All of it arrives as plugins:

| Plugin | Owns |
| --- | --- |
| `PipelinePlugin` | The six control links and the connections between them |
| `PcPlugin` | The program counter, redirects, and the branch shadow |
| `FetchPlugin` | Driving the instruction port |
| `DecoderPlugin` | Field extraction, hazard information, and routing |
| `RegFilePlugin` | Thirty-two registers, forwarding, the load-use interlock |
| `PredicateFilePlugin` | Eight predicates and their forwarding |
| `AluPlugin` | Arithmetic, shifts, the multiplier, constant formation |
| `CmpPlugin` | The ten condition codes |
| `SelectPlugin` | Conditional select |
| `BranchPlugin` | Branches, jumps, and the link register |
| `LsuPlugin` | Every memory access, including pairs and atomics |
| `SystemPlugin` | HALT and the fences |
| `TrapPlugin` | Stopping the machine, and the counters |
| `TcmPlugin` | The tightly coupled memory and the debug port |

A profile is a list of those, and nothing else:

```scala
def base: Seq[Hostable] = Seq(new PipelinePlugin, new PcPlugin, ...)
```

Adding an instruction to the architecture means adding a plugin to that list.
Removing the multiplier means passing a flag that one plugin reads. Replacing
the tightly coupled memory with a cache means writing a plugin that implements
`MemoryService` and swapping it in; fetch and the load/store unit ask for a
port rather than declaring one, so neither changes.

## 2. How plugins agree on anything

Three mechanisms, and no others.

### Payloads: agreeing on a value

A `Payload` is a typed key, not a signal. Hardware for it is created on the
first pipeline node that asks, and the links propagate it between the nodes
that use it. Two plugins can therefore agree on a value without either knowing
the other exists, and a value is only carried through the stages that read it.

```scala
val RESULT = Payload(Bits(AxiomParam.XLEN bits))   // in the ALU plugin
node(RESULT) := out                                // written in execute
```

The register file mux reads the same key in writeback. Nothing wired them
together.

### Services: agreeing on a protocol

A service is a plain Scala trait that a plugin implements and others look up by
type. The ALU does not know that a register file exists; it knows that
*something* accepts a result:

```scala
host[RegFileService].addResult(SEL, RESULT)
host[DecoderService].claim(SEL, opcodes, subFunctionLegal)
```

`claim` is the routing mechanism. Because the Axiom-64 encoding is flat, the
decoder already knows where every register field and immediate is; what it does
not know is which unit should execute a given opcode. A plugin claims a set of
primary opcodes and gets back a one-hot select payload. Anything no plugin
claims is an illegal instruction, which is how a build without a multiplier
comes to trap on a multiply through exactly the same path as a genuinely
undefined encoding.

### The database: agreeing on a parameter

`AxiomParam` is a set of `Database` keys rather than fields of a configuration
class. A plugin reads a parameter without the plugin that publishes it having
to be passed to its constructor. The keys are blocking, so reading one before
it is set suspends the reading fiber rather than failing, and plugins do not
have to be constructed in dependency order.

## 3. Elaboration order

The whole ordering contract is three phases, and no plugin needs to know which
other plugins exist or when they run.

| Phase | What belongs there |
| --- | --- |
| `during setup` | Register services, claim opcodes, request ports. No hardware |
| `during build` | Create hardware; read whatever setup registered |
| `during patch` | Reserved for `PipelinePlugin`, which connects the stages |

Because the Fiber runs the phases in order, a plugin's `build` can rely on
every other plugin's `claim` having happened. And because the pipeline is
connected in `patch`, plugins may create payloads and arbitration requests on
any stage, in any order, right up until the end.

Where a plugin genuinely needs something another plugin builds, it takes a
`Handle` and suspends. `PredicateFilePlugin` publishes its read function that
way, so `SelectPlugin` can call `read` without caring who builds first.

## 4. The pipeline

Six stages, each a `CtrlLink`, connected by `StageLink` registers.

```
 fetch --- decode --- read --- execute --- memory --- writeback
```

The register read has a stage to itself, and that was measured rather than
assumed. With the read, the forwarding network and the ALU all in one stage,
the critical path ran distributed RAM output, forwarding multiplexer, ALU,
result multiplexer, in series, and held the core to 34.7 MHz on an ECP5.
Splitting the stage gives each half roughly half the path.

Using control links rather than hand-written stage registers is what buys
backpressure. A plugin that needs to stall calls `haltWhen` on its stage and
the arbitration propagates upstream; no other plugin coordinates with it. The
load/store unit uses all three arbitration primitives, each for a different
reason:

| Primitive | Used for | Why |
| --- | --- | --- |
| `haltWhen` | The interlock, and an atomic's second pass | One transaction, more cycles |
| `duplicateWhen` | `LDP` and `STP` | Two transactions, one per register written |
| `throwWhen` | The branch shadow, and a trap | Remove a transaction entirely |

The pair case is worth dwelling on. A pair writes two architectural registers,
so it genuinely is two operations, and `duplicateWhen` makes that explicit: the
instruction goes through the memory stage twice and puts two transactions down
the pipeline, one per register. This is the cracking that pair forms were
always going to cost. It is visible here rather than hidden, and it is why a
`LAST_BEAT` payload exists so the retire counter still counts one instruction.

## 5. Hazards

The register file read and the forwarding network are in the **same** stage,
and that is the decision the rest of the hazard handling follows from. Which
stage matters much less than the fact that they are together.

Reading in one stage and forwarding in a later one means an operand is captured
into a pipeline register and then corrected afterwards. If the instruction is
held by backpressure, and its producer commits and leaves the pipeline while it
waits, the forwarding source disappears and the stale captured value is used.
That is a real bug, and this core had it: it showed up in randomized
co-simulation as a consumer two instructions after an atomic reading a stale
base register. Reading and forwarding together makes the whole value recompute
every cycle the instruction is held, so a value that has already reached the
register file is simply read from it.

From the read stage there are three forwarding distances:

| Producer is | It sits in | Covered by |
| --- | --- | --- |
| one instruction ahead | execute | forwarded, if its value exists yet |
| two ahead | memory | forwarded |
| three ahead | writeback | forwarded |
| four or more ahead | already committed | the register read finds it |

Two producers per instruction have to be forwarded, not one, because
pre-index, post-index and the pair forms all write a base register as well as a
destination. That second comparator on every operand is the honest price of
auto-increment addressing.

"If its value exists yet" is the whole of the interlock. Every producer
declares the stage in which its result actually appears — execute for
arithmetic, memory for an atomic's old value, writeback for a load or a
multiply — and the forwarding multiplexer for a stage only offers the sources
that have arrived by then. Anything it leaves out holds the read stage instead.
One flag drives both, so a new functional unit cannot forward a value that does
not exist and cannot silently skip the stall that replaces it.

A load pair is the one instruction that promises a write it cannot yet name.
It writes two registers by taking a second pass through the memory stage with
its destination field overridden, so before that pass exists nothing in the
pipeline mentions the second register. `WRITES_RM` names it from decode and the
load/store unit clears it once the pass that keeps the promise is in flight.

## 6. Branches

A conditional branch predicts not taken and resolves in execute against
forwarded operands, so it may immediately follow the compare that set its
predicate. Taken, it kills the three instructions behind it.

An unconditional relative branch does not wait for execute. Its target is the
program counter plus a displacement and both are known in decode, so it
redirects from there and kills one instruction instead of three. Calls and
unconditional jumps dominate the taken branches in ordinary code, and folding
them in decode recovered almost all of the cycles the separate read stage cost:
on the demo program, 275 cycles became 235, against 233 for the five-stage
core, while the clock went up by a fifth.

Neither branch plugin counts stages. Each redirect port is registered with the
stage it comes from; the program counter plugin throws the stages in front of
it and bumps a generation counter, and each fetched instruction carries the
generation it was fetched under so decode discards anything stale. A redirect
from a deeper stage wins over one from a shallower stage in the same cycle,
because the deeper instruction is the older one. Adding a stage costs nothing
anywhere else, which is exactly what adding the read stage demonstrated.

## 6b. Memory

Both ports use a ready/valid contract with no promised latency and no hold. A
command is accepted when `enable` and `ready` meet; an accepted read answers
later with `rvalid`, in order; a write needs acceptance and nothing more, which
is what keeps a store to a tightly coupled memory at one cycle.

On the data port at most one command is outstanding, which is what lets its
response buffer be one entry deep. That is arranged by construction rather than
by counting: the memory stage offers its command only on a cycle where the
transaction holding it can move on.

The instruction port keeps several commands out, because a memory that answers
in two cycles would otherwise halve the instruction rate. The program counter
is not a pipeline stage: it advances when a command is accepted, and three
small queues in issue order sit between it and decode — the addresses accepted
and not yet taken into the pipeline, a tag per command not yet answered, and
the words answered and not yet taken. The fetch stage proper is the head of
the address queue. The invariant that makes it safe is that everything is in
order, so decode always takes the next word and the next word is always its
own; a transaction thrown before its word arrived leaves a dead tag where it
stands, and the answer is dropped when it comes. A redirect empties the
address queue and kills every tag but the one for an instruction staying in
decode, which is the one that asked.

Buffering is what the contract costs. `rvalid` is a pulse, and the stage that
wants it may be held for an unrelated reason on the cycle it arrives, so fetch
queues words into decode and the load/store unit buffers into writeback. The
load/store unit's buffer has a second reader: an atomic takes its response in
the memory stage, because its second pass computes what it writes from what the
first pass read.

## 7. Stopping

There is no trap handler yet, so stopping means stopping. What matters is that
it stops precisely: the offending instruction is thrown out of execute before
it commits, everything younger is discarded, and the two instructions already
past execute drain and commit normally. HALT arrives through the same path with
a cause of NONE, so there is one mechanism rather than two.

## 8. What is not here

In rough order of what is worth doing next, with the reasoning in
`roadmap.md`:

- a stallable memory port, so a cache or a real interconnect can be attached
- traps with a handler, the three-tier privilege model, and nested paging
- divide, which needs a multi-cycle unit and therefore the stall protocol above
- the tile unit and the Compute profile
- superscalar issue, which is where the flat encoding and the absence of
  condition codes were supposed to pay off and where that claim gets tested
