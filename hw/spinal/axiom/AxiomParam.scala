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

  /** Fill a database with the Base profile defaults. */
  def base(
      resetVector: BigInt = 0,
      memWords: Int = 4096,
      withMultiplier: Boolean = true,
      withAtomics: Boolean = true,
      forwardLateFromWriteback: Boolean = true
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
    FORWARD_LATE_FROM_WRITEBACK.set(forwardLateFromWriteback)
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
  val DECODE    = 1 // instruction has arrived, decode and read registers
  val EXECUTE   = 2 // arithmetic, address generation, branch resolution
  val MEMORY    = 3 // data bus access
  val WRITEBACK = 4 // select the result and write the register file

  val COUNT = 5
}
