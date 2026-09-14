package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib.misc.pipeline._
import scala.collection.mutable.ArrayBuffer

/** The general register file, its forwarding network and the load-use
  * interlock.
  *
  * Producers register a value rather than writing a port, so a plugin that
  * computes a result never has to know which stage the write happens in, how
  * many other producers exist, or how the value reaches a consumer behind it.
  *
  * The registers are read in **execute**, not in decode. That is the decision
  * everything else here follows from, and it is worth being explicit about
  * why. Reading in decode means an operand is captured into a pipeline
  * register and then only corrected by forwarding. If the instruction is then
  * held in execute by backpressure, and its producer commits and leaves the
  * pipeline while it waits, the forwarding source disappears and the stale
  * captured value is used. That is a real bug and this core had it. Reading in
  * execute makes the read repeat every cycle the instruction is held, so a
  * value that has already reached the register file is simply read from it.
  *
  * What remains is two forwarding distances rather than three:
  *
  *  - one instruction ahead, sitting in memory: forwarded
  *  - two ahead, sitting in writeback: forwarded
  *  - three or more ahead: already committed, so the read finds it
  *
  * A load one ahead is the single case forwarding cannot cover, because the
  * data has not left memory yet. The interlock holds execute for the one cycle
  * it takes to become the two-ahead case.
  */
class RegFilePlugin extends AxiomPlugin with RegFileService {

  private case class Source(sel: Payload[Bool], data: Payload[Bits], late: Boolean)

  private val sources = ArrayBuffer[Source]()

  override def addResult(sel: Payload[Bool], data: Payload[Bits], late: Boolean = false): Unit =
    sources += Source(sel, data, late)

  val logic = during build new Area {
    val xlen = AxiomParam.XLEN.get
    val regs = Vec.fill(AxiomParam.REG_COUNT.get)(Reg(Bits(xlen bits)) init 0)

    val ex = ctrl(Stages.EXECUTE)
    val me = ctrl(Stages.MEMORY)
    val wb = ctrl(Stages.WRITEBACK)

    /** Mux the registered producers. A late producer's value is meaningless
      * before writeback, so the memory-stage copy leaves them out.
      */
    def resultAt(node: NodeApi, includeLate: Boolean): Bits = {
      val value = Bits(xlen bits)
      value := B(0, xlen bits)
      for (source <- sources if includeLate || !source.late) {
        when(node(source.sel)) { value := node(source.data) }
      }
      value
    }

    val wbResult  = resultAt(wb.down, includeLate = true)
    val memResult = resultAt(me.down, includeLate = false)

    // ---- commit ---------------------------------------------------------
    val wbWritesRd = wb.down.isFiring && wb.down(Global.WRITES_RD) && wb.down(Global.RD_ADDR) =/= 0
    val wbWritesBase = wb.down.isFiring && wb.down(Global.WRITES_BASE) && wb.down(Global.RN_ADDR) =/= 0

    when(wbWritesBase) { regs(wb.down(Global.RN_ADDR)) := wb.down(Global.BASE_VALUE) }
    when(wbWritesRd) { regs(wb.down(Global.RD_ADDR)) := wbResult }

    // ---- read and forward, both in execute --------------------------------
    val memWritesRd = me.isValid && me.down(Global.WRITES_RD) && me.down(Global.RD_ADDR) =/= 0
    val memWritesBase = me.isValid && me.down(Global.WRITES_BASE) && me.down(Global.RN_ADDR) =/= 0
    val wbHasRd = wb.isValid && wb.down(Global.WRITES_RD) && wb.down(Global.RD_ADDR) =/= 0
    val wbHasBase = wb.isValid && wb.down(Global.WRITES_BASE) && wb.down(Global.RN_ADDR) =/= 0

    /** Assignments run oldest first so the newest producer wins: the memory
      * stage holds a younger instruction than the writeback stage does.
      */
    def readForwarded(address: UInt): Bits = {
      val value = Bits(xlen bits)
      value := regs(address)
      when(wbHasBase && wb.down(Global.RN_ADDR) === address) { value := wb.down(Global.BASE_VALUE) }
      when(wbHasRd && wb.down(Global.RD_ADDR) === address) { value := wbResult }
      when(memWritesBase && me.down(Global.RN_ADDR) === address) { value := me.down(Global.BASE_VALUE) }
      when(memWritesRd && me.down(Global.RD_ADDR) === address) { value := memResult }
      when(address === 0) { value := B(0, xlen bits) }
      value
    }

    ex.down(Global.RS_N) := readForwarded(ex.down(Global.RN_ADDR))
    ex.down(Global.RS_M) := readForwarded(ex.down(Global.RM_ADDR))
    ex.down(Global.RS_D) := readForwarded(ex.down(Global.RD_ADDR))

    // ---- the load-use interlock -------------------------------------------
    val lateAtMemory = sources.filter(_.late)
      .map(source => me.down(source.sel)).reduceOption(_ || _).getOrElse(False)

    val memoryRd = me.down(Global.RD_ADDR)
    val consumerNeedsIt =
      (ex.down(Global.READS_RN) && ex.down(Global.RN_ADDR) === memoryRd) ||
      (ex.down(Global.READS_RM) && ex.down(Global.RM_ADDR) === memoryRd) ||
      (ex.down(Global.READS_RD) && ex.down(Global.RD_ADDR) === memoryRd)

    // Guarded on the up nodes rather than the down ones, because halting
    // clears down.valid and reading it back here would close a loop.
    val interlock = ex.isValid && me.isValid && lateAtMemory &&
      me.down(Global.WRITES_RD) && memoryRd =/= 0 && consumerNeedsIt

    ex.haltWhen(interlock)

    // ---- debug -------------------------------------------------------------
    // A combinational peek at the committed file, for simulation and bring-up.
    val io = host[InterfaceService].io
    io.dbgRegData := regs(io.dbgRegAddr)
  }
}
