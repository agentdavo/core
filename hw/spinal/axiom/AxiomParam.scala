package axiom

import spinal.core._
import spinal.lib.misc.database.Database

/** Elaboration parameters for an Axiom-64 implementation.
  *
  * These are [[Database]] keys rather than fields of a configuration case
  * class. The difference matters once the core is assembled from plugins: a
  * plugin can read a parameter without the plugin that publishes it having to
  * be passed to its constructor, and a plugin can publish a parameter that it
  * alone knows, such as how deep its own pipeline is.
  *
  * Keys are blocking. Reading one before it is set suspends the reading fiber
  * until somebody sets it, instead of failing, so plugins do not have to be
  * constructed in dependency order.
  */
object AxiomParam {

  /** Width of the general registers and of the data path. */
  val XLEN = Database.blocking[Int]()

  /** Width of the program counter and of both bus addresses. */
  val PC_WIDTH = Database.blocking[Int]()

  val REG_COUNT      = Database.blocking[Int]()
  val REG_ADDR_BITS  = Database.blocking[Int]()
  val PRED_COUNT     = Database.blocking[Int]()
  val PRED_ADDR_BITS = Database.blocking[Int]()

  val RESET_VECTOR = Database.blocking[BigInt]()

  /** Size of the tightly coupled memory, in 64-bit words. */
  val MEM_WORDS = Database.blocking[Int]()

  /** When false, the multiply functions decode as illegal and no multiplier is
    * instantiated. Useful for the smallest soft-core builds.
    */
  val WITH_MULTIPLIER = Database.blocking[Boolean]()

  /** When false, the divide opcode decodes as illegal.
    *
    * A divider is sixty-five cycles of iteration and a pair of wide registers,
    * which is a real cost on a small part for an instruction some programs
    * never execute.
    */
  val WITH_DIVIDER = Database.blocking[Boolean]()

  /** When false, the atomic opcodes decode as illegal. */
  val WITH_ATOMICS = Database.blocking[Boolean]()

  /** Whether a late result may be forwarded out of the writeback stage.
    *
    * A load's data comes straight off a block RAM, so forwarding it from
    * writeback chains the memory read, the result multiplexer and the
    * forwarding multiplexer onto the front of whatever the consumer does. That
    * is the longest path in the core once the multiplier is out of the way.
    *
    * Turning it off costs a second interlock cycle on a load-use pair and buys
    * back the frequency. Which way round is better depends on the target, so it
    * is a parameter and both settings are measured rather than argued about.
    */
  val FORWARD_LATE_FROM_WRITEBACK = Database.blocking[Boolean]()

  /** Whether an updated base register may be forwarded, or only waited for.
    *
    * Pre-index, post-index and the pair forms write a base register as well as
    * a destination, so every operand needs a second address comparator and a
    * second multiplexer input against it. That doubles the width of the bypass
    * network, which on an FPGA is the expensive part: the critical path
    * measures around sixty per cent routing, and the bypass network is what
    * spreads the design out.
    *
    * Turning it off leaves the base update to reach the register file before a
    * consumer can read it, which the interlock already knows how to wait for.
    * What that costs depends on how soon the code re-uses its base pointer, so
    * it is a parameter and measured rather than argued about.
    */
  val FORWARD_BASE = Database.blocking[Boolean]()

  /** How often the tightly coupled memory refuses a command, out of sixteen.
    *
    * Zero is a memory that always accepts and answers the next cycle, which is
    * what a tightly coupled memory does and what the core is built for. Any
    * other value makes it stall pseudo-randomly, which is how the stallable
    * protocol gets tested: the same programs must produce the same answers
    * through a memory that says no, only taking more cycles.
    *
    * It is in the design rather than the test bench because the test bench
    * drives the core through its ports and cannot reach inside the memory.
    */
  val MEMORY_STALL = Database.blocking[Int]()

  /** Cycles the backing memory takes to answer, and the minimum gap between
    * commands. One is a memory that answers next cycle; anything larger is
    * what makes a cache worth having.
    */
  val MEMORY_LATENCY = Database.blocking[Int]()

  /** Cache geometry. Zero bytes builds no cache and passes the port straight
    * through, which is how the two caches are measured one at a time.
    */
  val ICACHE_BYTES = Database.blocking[Int]()
  val DCACHE_BYTES = Database.blocking[Int]()
  val CACHE_LINE_BYTES = Database.blocking[Int]()

  /** A combinational read port on the register file for the test bench.
    *
    * It is a whole extra copy of every RAM bank and nothing on a real chip
    * needs it, so a synthesis build turns it off and reads registers the way
    * hardware does.
    */
  val WITH_DEBUG_REGFILE_PORT = Database.blocking[Boolean]()

  /** Fill a database with the Base profile defaults. */
  def base(
      resetVector: BigInt = 0,
      memWords: Int = 4096,
      withMultiplier: Boolean = true,
      withAtomics: Boolean = true,
      withDivider: Boolean = true,
      forwardLateFromWriteback: Boolean = true,
      forwardBase: Boolean = true,
      withDebugRegFilePort: Boolean = true,
      memoryStall: Int = 0,
      memoryLatency: Int = 1,
      icacheBytes: Int = 4096,
      dcacheBytes: Int = 4096,
      cacheLineBytes: Int = 32
  ): Unit = {
    XLEN.set(Isa.XLEN)
    PC_WIDTH.set(Isa.XLEN)
    REG_COUNT.set(Isa.REG_COUNT)
    REG_ADDR_BITS.set(Isa.REG_ADDR_BITS)
    PRED_COUNT.set(Isa.PRED_COUNT)
    PRED_ADDR_BITS.set(Isa.PRED_ADDR_BITS)
    RESET_VECTOR.set(resetVector)
    MEM_WORDS.set(memWords)
    WITH_MULTIPLIER.set(withMultiplier)
    WITH_ATOMICS.set(withAtomics)
    WITH_DIVIDER.set(withDivider)
    FORWARD_LATE_FROM_WRITEBACK.set(forwardLateFromWriteback)
    FORWARD_BASE.set(forwardBase)
    WITH_DEBUG_REGFILE_PORT.set(withDebugRegFilePort)
    MEMORY_STALL.set(memoryStall)
    MEMORY_LATENCY.set(memoryLatency)
    ICACHE_BYTES.set(icacheBytes)
    DCACHE_BYTES.set(dcacheBytes)
    CACHE_LINE_BYTES.set(cacheLineBytes)
  }
}

/** Stage numbers of the Base profile pipeline.
  *
  * These are plain integers into a [[spinal.lib.misc.pipeline.StageCtrlPipeline]],
  * so a plugin that needs an extra stage can insert one by renumbering here
  * without any other plugin caring.
  */
object Stages {
  val FETCH     = 0 // present the program counter to the instruction bus
  val DECODE    = 1 // instruction has arrived, decode it
  val READ      = 2 // read the register file and forward into it
  val EXECUTE   = 3 // arithmetic, address generation, branch resolution
  val MEMORY    = 4 // data bus access
  val WRITEBACK = 5 // select the result and write the register file

  val COUNT = 6

  /** Why the register read has a stage to itself.
    *
    * With the read, the forwarding network and the ALU all in execute, the
    * measured critical path ran distributed RAM output, forwarding
    * multiplexer, ALU, result multiplexer, in series, and held the core to
    * 34.7 MHz on an ECP5. Splitting them gives each half roughly half the
    * path.
    *
    * The read and the forwarding have to stay in the same stage as each other,
    * whichever stage that is. Capturing an operand and then correcting it by
    * forwarding in a later stage is unsound: if the instruction is held by
    * backpressure and its producer commits and leaves the pipeline while it
    * waits, the forwarding source disappears and the stale captured value is
    * used. This core had that bug once. Reading and forwarding together means
    * the whole value is recomputed every cycle the instruction is held.
    *
    * The cost is one more cycle of branch shadow and one more interlock cycle
    * on a load-use pair, because operands are now captured a stage earlier.
    */
  val READ_STAGE_RATIONALE = ()
}
