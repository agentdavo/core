package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._
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
    val count = AxiomParam.REG_COUNT.get

    /** The register file storage.
      *
      * Not a vector of flip-flops. Thirty-two 64-bit registers with three
      * asynchronous read ports built from flip-flops become three 32-to-1
      * multiplexers 64 bits wide, and on an ECP5 that measured at roughly
      * forty per cent of the whole core and a critical path that held the
      * design to 13 MHz. Distributed RAM does the same job in about a
      * fifteenth of the area and a fraction of the delay.
      *
      * Distributed RAM has one write port, and this file needs two, because an
      * indexed access writes its base register as well as its destination. The
      * standard answer is a live value table: one bank per write port, each
      * replicated per read port, plus one bit per register saying which bank
      * last wrote it. Every array stays single-write, so every array maps to
      * distributed RAM, and the live value table is one bit wide and therefore
      * cheap to keep in flip-flops.
      */
    val storage = new Area {
      val PortRn = 0; val PortRm = 1; val PortRd = 2; val PortDebug = 3
      val readPorts = 4

      // An asynchronous read alongside a synchronous write is write-first in
      // the generated Verilog and read-first in an ECP5 distributed RAM, which
      // would be a real difference if it could be observed. It cannot: the
      // only cycle the two disagree is one where writeback is writing the
      // register being read, and that is exactly the cycle the forwarding
      // select below discards the file's output in favour of the forwarded
      // value.
      //
      // setCompositeName rather than setName: a bare name would drop the
      // enclosing plugin and area prefix, and every report that attributes
      // area or delay back to a plugin works off that prefix.
      val banks = Array.tabulate(2, readPorts) { (write, read) =>
        val bank = Mem(Bits(xlen bits), count)
        bank.init(Seq.fill(count)(B(0, xlen bits)))
        bank.setCompositeName(this, s"bank_${write}_$read")
        bank
      }

      /** Which bank holds the current value of each register. */
      val live = Vec.fill(count)(Reg(Bool()) init False)

      def write(port: Int, enable: Bool, address: UInt, data: Bits): Unit = {
        for (read <- 0 until readPorts) banks(port)(read).write(address, data, enable)
        when(enable) { live(address) := Bool(port == 1) }
      }

      def read(port: Int, address: UInt): Bits =
        Mux(live(address), banks(1)(port).readAsync(address), banks(0)(port).readAsync(address))
    }

    val ex = ctrl(Stages.EXECUTE)
    val me = ctrl(Stages.MEMORY)
    val wb = ctrl(Stages.WRITEBACK)

    /** Mux the registered producers. A late producer's value is meaningless
      * before writeback, so the memory-stage copy leaves them out.
      */
    /** Replicate a single bit across the data path, for one-hot masking. */
    def spread(bit: Bool): Bits = B(xlen bits, default -> bit)

    /** A one-hot multiplexer built as a balanced OR tree.
      *
      * A chain of conditional assignments is a priority multiplexer, and its
      * depth grows with the number of sources. These selects are mutually
      * exclusive by construction, so masking and OR-ing is both smaller and
      * two levels deep instead of five. The result multiplexer sits directly in
      * the writeback to execute forwarding path, where that depth was costing
      * about 7 ns.
      */
    def oneHot(choices: Seq[(Bool, Bits)]): Bits =
      choices.map { case (sel, value) => value & spread(sel) }.reduceBalancedTree(_ | _)

    class ResultMux(node: NodeApi, includeLate: Boolean) extends Area {
      val value = Bits(xlen bits)
      value := B(0, xlen bits)
      for (source <- sources if includeLate || !source.late) {
        when(node(source.sel)) { value := node(source.data) }
      }
    }

    val forwardLate = AxiomParam.FORWARD_LATE_FROM_WRITEBACK.get

    // The commit multiplexer always sees every source; the forwarding copy may
    // not, because a late source drags a block RAM read into the path.
    val writebackCommit = new ResultMux(wb.down, includeLate = true)
    val writebackForward = if (forwardLate) writebackCommit else new ResultMux(wb.down, includeLate = false)
    val memoryResult = new ResultMux(me.down, includeLate = false)
    def wbResult = writebackForward.value
    def memResult = memoryResult.value

    // ---- commit ---------------------------------------------------------
    val wbWritesRd = wb.down.isFiring && wb.down(Global.WRITES_RD) && wb.down(Global.RD_ADDR) =/= 0
    val wbWritesBase = wb.down.isFiring && wb.down(Global.WRITES_BASE) && wb.down(Global.RN_ADDR) =/= 0

    storage.write(0, wbWritesRd, wb.down(Global.RD_ADDR), writebackCommit.value)
    storage.write(1, wbWritesBase, wb.down(Global.RN_ADDR), wb.down(Global.BASE_VALUE))

    // ---- read and forward, both in execute --------------------------------
    val memWritesRd = me.isValid && me.down(Global.WRITES_RD) && me.down(Global.RD_ADDR) =/= 0
    val memWritesBase = me.isValid && me.down(Global.WRITES_BASE) && me.down(Global.RN_ADDR) =/= 0
    val wbHasRd = wb.isValid && wb.down(Global.WRITES_RD) && wb.down(Global.RD_ADDR) =/= 0
    val wbHasBase = wb.isValid && wb.down(Global.WRITES_BASE) && wb.down(Global.RN_ADDR) =/= 0

    /** Assignments run oldest first so the newest producer wins: the memory
      * stage holds a younger instruction than the writeback stage does.
      */
    /** One read port, and the forwarding around it.
      *
      * A chain of conditional assignments, which is a priority multiplexer.
      * The obvious alternative, resolving priority on the one-bit selects and
      * then applying them to the data as a one-hot multiplexer, was tried and
      * measured: 13 per cent more area for 4 per cent more frequency, because
      * yosys did not merge the mask and OR pairs into single LUT4s. On a part
      * where the critical path is 62 per cent routing, more area is the wrong
      * trade, so the shallower form was reverted.
      *
      * Assignments run oldest first so the newest producer wins.
      */
    class ReadPort(port: Int, address: UInt) extends Area {
      val value = Bits(xlen bits)
      value := storage.read(port, address)
      when(wbHasBase && wb.down(Global.RN_ADDR) === address) { value := wb.down(Global.BASE_VALUE) }
      when(wbHasRd && wb.down(Global.RD_ADDR) === address) { value := wbResult }
      when(memWritesBase && me.down(Global.RN_ADDR) === address) { value := me.down(Global.BASE_VALUE) }
      when(memWritesRd && me.down(Global.RD_ADDR) === address) { value := memResult }
      when(address === 0) { value := B(0, xlen bits) }
    }

    val readRn = new ReadPort(storage.PortRn, ex.down(Global.RN_ADDR))
    val readRm = new ReadPort(storage.PortRm, ex.down(Global.RM_ADDR))
    val readRd = new ReadPort(storage.PortRd, ex.down(Global.RD_ADDR))

    ex.down(Global.RS_N) := readRn.value
    ex.down(Global.RS_M) := readRm.value
    ex.down(Global.RS_D) := readRd.value

    // ---- the load-use interlock -------------------------------------------
    def lateAt(node: CtrlLink): Bool = sources.filter(_.late)
      .map(source => node.down(source.sel)).reduceOption(_ || _).getOrElse(False)

    def consumerNeeds(destination: UInt): Bool =
      (ex.down(Global.READS_RN) && ex.down(Global.RN_ADDR) === destination) ||
      (ex.down(Global.READS_RM) && ex.down(Global.RM_ADDR) === destination) ||
      (ex.down(Global.READS_RD) && ex.down(Global.RD_ADDR) === destination)

    def blockedBy(node: CtrlLink): Bool = {
      val destination = node.down(Global.RD_ADDR)
      node.isValid && lateAt(node) && node.down(Global.WRITES_RD) &&
        destination =/= 0 && consumerNeeds(destination)
    }

    // Guarded on the up nodes rather than the down ones, because halting
    // clears down.valid and reading it back here would close a loop.
    //
    // With writeback forwarding of late results turned off, a consumer has to
    // wait for the producer to commit, which is one cycle further.
    val blocked = if (forwardLate) blockedBy(me) else blockedBy(me) || blockedBy(wb)
    val interlock = ex.isValid && blocked

    ex.haltWhen(interlock)

    // ---- debug -------------------------------------------------------------
    // A combinational peek at the committed file, for simulation and bring-up.
    val io = host[InterfaceService].io
    io.dbgRegData := storage.read(storage.PortDebug, io.dbgRegAddr)
  }
}
