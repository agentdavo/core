# A1: the first Axiom-64 implementation

A1 is a five stage, in-order, single-issue pipeline implementing the Base
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
| `PipelinePlugin` | The five control links and the connections between them |
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

Five stages, each a `CtrlLink`, connected by `StageLink` registers.

```
 fetch --- decode --- execute --- memory --- writeback
```

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

The register file is read in **execute**, not in decode, and that is the
decision the rest of the hazard handling follows from.

Reading in decode means an operand is captured into a pipeline register and
then corrected by forwarding. If the instruction is later held in execute by
backpressure, and its producer commits and leaves the pipeline while it waits,
the forwarding source disappears and the stale captured value is used. That is
a real bug, and this core had it: it showed up in randomized co-simulation as a
consumer two instructions after an atomic reading a stale base register.
Reading in execute makes the read repeat every cycle the instruction is held,
so a value that has already reached the register file is simply read from it.

What remains is two forwarding distances instead of three:

| Producer is | It sits in | Covered by |
| --- | --- | --- |
| one instruction ahead | memory | forwarded into execute |
| two ahead | writeback | forwarded into execute |
| three or more ahead | already committed | the register read finds it |

Two producers per instruction have to be forwarded, not one, because
pre-index, post-index and the pair forms all write a base register as well as a
destination. That second comparator on every operand is the honest price of
auto-increment addressing.

The interlock covers the one case forwarding cannot: a load in the memory
stage whose data has not arrived. Execute holds for one cycle, which turns it
into the two-ahead case.

Atomics deliberately do **not** use the interlock. An atomic holds the memory
stage for two cycles, so a consumer can sit in execute while it finishes, and
that pairing is outside what the interlock models. Instead the atomic's old
value is registered as an ordinary early result and forwarded from the memory
stage. It is correct because a consumer can only advance on the cycle the
atomic completes, which is exactly the cycle the value is valid.

## 6. Branches

Predict not taken, resolve in execute against forwarded operands, so a branch
may immediately follow the compare that set its predicate.

A taken branch kills the two instructions behind it, but the branch plugin does
not know there are two. The program counter plugin keeps a generation counter,
bumped on every redirect; each fetched instruction carries the generation it
was fetched under, and decode discards anything stale. Adding a fetch stage
later costs nothing anywhere else.

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
