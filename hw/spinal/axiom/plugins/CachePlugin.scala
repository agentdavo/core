package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._

/** First level caches, one on each port, in front of whatever
  * [[BackingMemoryService]] offers.
  *
  * The plugin is the wiring; [[CacheUnit]] is the cache. Keeping them apart is
  * what lets the cache be driven directly in simulation instead of only
  * through a core.
  *
  * The instruction port is thirty-two bits wide and the cache sixty-four, so
  * the half is picked from a bit of the address held from the command. Only
  * one command is outstanding, so one register holds it however long a refill
  * takes.
  */
class CachePlugin extends AxiomPlugin with MemoryService {

  private var instructionPort: IBus = null
  private var dataPort: DBus = null
  private var instructionBacking: DBus = null
  private var dataBacking: DBus = null
  private var perfIcacheMiss: Bool = null
  private var perfDcacheMiss: Bool = null

  override def newInstructionPort(): IBus = {
    require(instructionPort == null, "there is one instruction port")
    instructionPort = IBus(AxiomParam.PC_WIDTH.get)
    instructionPort
  }

  override def newDataPort(): DBus = {
    require(dataPort == null, "there is one data port")
    dataPort = DBus(AxiomParam.PC_WIDTH.get, AxiomParam.XLEN.get)
    dataPort
  }

  // Ports on the memory behind are asked for in setup, so the plugin that
  // provides them has them all before it builds, whichever order the two
  // plugins happen to be built in.
  val setupLogic = during setup new Area {
    val backing = host[BackingMemoryService]
    instructionBacking = backing.newBackingPort()
    dataBacking = backing.newBackingPort()
    // Claimed whether or not a cache is built. A port with no cache behind it
    // never misses, which is the truth, and the counter reads zero.
    perfIcacheMiss = host[PerfService].newCounter("icache-miss")
    perfDcacheMiss = host[PerfService].newCounter("dcache-miss")
  }

  /** Explicit rather than `<>`: the port handed out by the memory behind is a
    * plain bundle with no directions on it, and asking the operator to work
    * out which way each signal goes from only one side of the pair is asking
    * for a silent mistake.
    */
  private def connect(from: DBus, to: DBus): Unit = {
    // `from` drives the command and `to` answers it.
    to.enable := from.enable
    to.write := from.write
    to.address := from.address
    to.mask := from.mask
    to.wdata := from.wdata
    from.ready := to.ready
    from.rvalid := to.rvalid
    from.rdata := to.rdata
  }

  val logic = during build new Area {
    val xlen = AxiomParam.XLEN.get
    val memoryBytes = AxiomParam.MEM_WORDS.get * 8
    val lineBytes = AxiomParam.CACHE_LINE_BYTES.get

    /** A cache of no bytes is no cache: the port goes straight through to the
      * memory behind. That is how a cache is measured against its own absence
      * rather than against a different memory, which is the only comparison
      * that says what it is worth.
      */
    def cacheOrNot(bytes: Int, writes: Boolean, core: DBus, backing: DBus, miss: Bool): Unit = {
      if (bytes == 0) {
        connect(core, backing)
        miss := False
      } else {
        val unit = CacheUnit(bytes = bytes, lineBytes = lineBytes,
          memoryBytes = memoryBytes, writes = writes)
        connect(core, unit.io.core)
        connect(unit.io.memory, backing)
        miss := unit.io.refillStart
      }
    }

    // ---- instruction side -------------------------------------------------
    // The bus carries 32-bit instructions and the memory 64-bit words, so the
    // half is picked by a bit of the address held from the command. Only one
    // command is outstanding, so one register holds it however long a refill
    // takes.
    val instructionWide = DBus(AxiomParam.PC_WIDTH.get, xlen)
    instructionWide.enable := instructionPort.enable
    instructionWide.address := instructionPort.address
    instructionWide.write := False
    instructionWide.wdata := B(0, xlen bits)
    instructionWide.mask := B(0, xlen / 8 bits)
    instructionPort.ready := instructionWide.ready
    instructionPort.rvalid := instructionWide.rvalid

    cacheOrNot(AxiomParam.ICACHE_BYTES.get, writes = false, instructionWide, instructionBacking,
      perfIcacheMiss)

    val instructionAccepted = instructionPort.enable && instructionWide.ready
    val instructionHigh = RegNextWhen(instructionPort.address(2), instructionAccepted) init False
    instructionPort.data := Mux(instructionHigh,
      instructionWide.rdata(xlen - 1 downto 32), instructionWide.rdata(31 downto 0))

    // ---- data side --------------------------------------------------------
    cacheOrNot(AxiomParam.DCACHE_BYTES.get, writes = true, dataPort, dataBacking, perfDcacheMiss)
  }
}
