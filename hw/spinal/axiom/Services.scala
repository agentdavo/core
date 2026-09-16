package axiom

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._
import spinal.core.fiber.Handle

/** Registration interface of the instruction decoder.
  *
  * An execution plugin does not describe its instructions to the decoder field
  * by field. The Axiom-64 encoding is deliberately flat, so the decoder already
  * knows where the register fields and the immediates are; what it does not
  * know is which unit should execute a given opcode. A plugin claims a set of
  * primary opcodes and gets back a one-hot select payload it can test.
  */
trait DecoderService {

  /** Drive `sel` at decode when the instruction's primary opcode is one of
    * `opcodes` and `subFunctionLegal` holds.
    *
    * @param subFunctionLegal given the raw instruction, whether the
    *        sub-function inside the opcode is defined. Reserved encodings
    *        inside a claimed opcode must trap rather than alias, so a plugin
    *        that owns a sub-function field is responsible for saying which
    *        values of it are real.
    */
  def claim(sel: Payload[Bool], opcodes: Seq[Int], subFunctionLegal: Bits => Bool = null): Unit
}

/** Program counter ownership and redirection. */
trait PcService {

  /** Allocate a redirect port for an instruction sitting in stage `from`.
    *
    * Driving it valid sends the front end to `payload` and kills everything
    * fetched behind it. The stage is the only thing the program counter needs
    * to be told: it decides which stages hold instructions younger than the
    * redirecting one, and those are exactly the ones thrown. A redirect from a
    * deeper stage wins over one from a shallower stage in the same cycle,
    * because the deeper instruction is the older one.
    */
  def newRedirect(from: Int): Flow[UInt]

  /** Was the instruction arriving at this node fetched under the current
    * generation?
    *
    * Decode already throws stale instructions, so a plugin that only needs to
    * know whether an instruction is real can read the throw's effect on the
    * stage's validity instead. A plugin that must not have its own answer
    * depend on every other reason a stage might be cancelled asks here, and
    * gets the generation compare on its own.
    */
  def generationOk(node: NodeApi): Bool
}

/** The general register file.
  *
  * Results are registered rather than written directly, so that the plugin
  * producing a value does not have to know which stage the write happens in or
  * how forwarding reaches the consumers.
  */
trait RegFileService {

  /** Offer a value as the result of the instructions selected by `sel`.
    *
    * The select and the data are both payloads, so the producer may compute
    * them in any stage at or before writeback and the pipeline carries them
    * forward. Exactly one registered source may be selected at a time.
    *
    * @param availableAt the first stage in which `data` actually holds the
    *        result. The forwarding network only offers a source to stages at
    *        or after that point, and the interlock holds a consumer whose
    *        producer is not there yet. An ALU result is ready in execute, a
    *        load's is not ready until writeback, and an atomic's old value
    *        appears in memory.
    * @param ready for a source that reaches its stage and then waits there.
    *        `availableAt` says which stage the value appears in, which is a
    *        fact about the pipeline; this says whether it has appeared yet,
    *        which is a fact about this cycle. A load behind a cache is in
    *        writeback long before its data is, and forwarding what is on that
    *        wire meanwhile hands a consumer whatever the memory happened to be
    *        driving. Backpressure does not save it: a stalled writeback still
    *        lets the read stage advance into an empty execute, so the
    *        interlock has to be told.
    *
    *        A Handle because the signal usually does not exist when the source
    *        is registered: producers claim their results in setup and build
    *        their logic afterwards.
    */
  def addResult(
      sel: Payload[Bool],
      data: Payload[Bits],
      availableAt: Int = Stages.EXECUTE,
      ready: Handle[Bool] = null
  ): Unit
}

/** The predicate register file. */
trait PredicateService {

  /** Offer a value as the predicate written by the instructions selected by `sel`. */
  def addWriter(sel: Payload[Bool], value: Payload[Bool]): Unit

  /** Read a predicate at the execute stage, with the in-flight writes
    * forwarded in. Only valid to call from a plugin's build phase.
    */
  def read(address: UInt): Bool
}

/** Collection point for everything that stops the machine. */
trait TrapService {

  /** Allocate a trap port raised from the execute stage. Raising it suppresses
    * the instruction's own side effects and halts the core once the older
    * instructions have drained.
    */
  def newTrapPort(): TrapCmd
}

/** A request to stop the machine. */
case class TrapCmd() extends Bundle {
  val valid = Bool()
  val cause = UInt(Isa.Cause.WIDTH bits)

  def raise(condition: Bool, reason: Int): Unit = {
    valid setWhen condition
    when(condition) { cause := U(reason, Isa.Cause.WIDTH bits) }
  }
}

/** Source of the two memory ports.
  *
  * Fetch and the load/store unit ask for a port rather than declaring one, so
  * the same core can be built against a tightly coupled memory, against an
  * external bus, or eventually against a cache, by swapping the plugin that
  * implements this and changing nothing else.
  */
trait MemoryService {
  def newInstructionPort(): IBus
  def newDataPort(): DBus
}

/** Source of ports on whatever sits behind a cache.
  *
  * A separate service from [[MemoryService]] on purpose. The core asks for a
  * memory; a cache asks for a memory too, and is itself a memory to the core.
  * Two names keep the two roles apart, so a plugin cannot accidentally answer
  * the wrong one and the profile decides which side of the cache each memory
  * is on.
  *
  * The port is a [[DBus]]: a cache refills a line one doubleword at a time and
  * writes through the same way, so it needs nothing an ordinary data port does
  * not already have.
  */
trait BackingMemoryService {
  def newBackingPort(): DBus
}

/** The events a performance counter can count.
  *
  * The list is fixed and the index of each event is part of the debug
  * interface, so that a test bench can ask for a counter by index without
  * knowing which plugins were built. A plugin that does not exist in a given
  * profile simply leaves its counter at zero, which reads the same way as an
  * event that never happened, and is the truth in both cases.
  *
  * Every counter here answers one question: how many cycles did the machine
  * spend not retiring an instruction, and whose fault was it? The sum of the
  * stall counters plus the retired count plus the flush shadow accounts for
  * the whole run, which is what makes the numbers worth having: an
  * optimisation that moves cycles from one counter to another has not made the
  * program faster.
  */
object PerfEvent {

  /** Decode had no instruction to decode. Instruction memory's fault. */
  val FETCH = 0

  /** Read held an instruction whose operand was not ready yet. */
  val INTERLOCK = 1

  /** Writeback waited for load data. */
  val LOAD = 2

  /** Memory waited for the data bus to accept a command. A load and a store
    * both wait here, which is why this is not called the store counter: it is
    * the cost of getting a command in, where [[LOAD]] is the cost of waiting
    * for the answer to one.
    */
  val ISSUE = 3

  /** Execute held an instruction while the divider iterated. */
  val DIVIDE = 4

  /** A redirect was taken; everything younger was thrown. */
  val REDIRECT = 5

  /** A line was fetched into the instruction cache. */
  val ICACHE_MISS = 6

  /** A line was fetched into the data cache. */
  val DCACHE_MISS = 7

  val NAMES: Seq[String] = Seq(
    "fetch", "interlock", "load", "issue", "divide", "redirect",
    "icache-miss", "dcache-miss"
  )

  val COUNT: Int = NAMES.size

  def index(name: String): Int = {
    val at = NAMES.indexOf(name)
    require(at >= 0, s"'$name' is not a performance event; known events are ${NAMES.mkString(", ")}")
    at
  }
}

/** Cycle accounting.
  *
  * A plugin that can stall the pipeline says so here rather than being counted
  * from outside, because only the plugin knows which of the several reasons it
  * might be holding a stage is the one that applies. What comes back is a Bool
  * to drive high on every cycle the event happens; the counting, the width and
  * the way the result leaves the design are none of the caller's business.
  */
trait PerfService {

  /** Allocate the counter for `name`, which must be one of [[PerfEvent]]'s.
    *
    * The returned Bool has no driver and wants exactly one: a counter claimed
    * and left undriven is an elaboration error rather than a zero that reads
    * like a measurement. A plugin that only sometimes has anything to count
    * claims the counter only in those builds, or drives it False.
    *
    * Each event has one owner. Claiming the same one twice is a mistake, since
    * two plugins each counting half a reason produce a number that is neither.
    */
  def newCounter(name: String): Bool
}
