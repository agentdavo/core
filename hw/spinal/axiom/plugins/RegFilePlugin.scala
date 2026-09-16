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
  * The registers are read in **read**, its own stage, and the forwarding
  * network sits in that same stage. The pairing is the decision everything
  * else here follows from, and it is worth being explicit about why. Reading
  * in one stage and forwarding in a later one means an operand is captured
  * into a pipeline register and then only corrected afterwards. If the
  * instruction is held by backpressure, and its producer commits and leaves
  * the pipeline while it waits, the forwarding source disappears and the stale
  * captured value is used. That is a real bug and this core had it. Reading
  * and forwarding together makes the whole value recompute every cycle the
  * instruction is held, so a value that has already reached the register file
  * is simply read from it.
  *
  * From the read stage there are three forwarding distances:
  *
  *  - one instruction ahead, sitting in execute: forwarded, if its value is
  *    ready there, which an ALU result is and a load or an atomic is not
  *  - two ahead, sitting in memory: forwarded
  *  - three ahead, sitting in writeback: forwarded
  *  - four or more ahead: already committed, so the read finds it
  *
  * Each producer declares the stage its value actually arrives in, and the
  * interlock holds the read stage for exactly the cycles a consumer would
  * otherwise reach past that. A load one ahead is the common case: its data
  * has not left memory yet, so the consumer waits.
  */
class RegFilePlugin extends AxiomPlugin with RegFileService {

  private case class Source(
      sel: Payload[Bool],
      data: Payload[Bits],
      availableAt: Int,
      ready: spinal.core.fiber.Handle[Bool])

  private val sources = ArrayBuffer[Source]()

  override def addResult(
      sel: Payload[Bool],
      data: Payload[Bits],
      availableAt: Int = Stages.EXECUTE,
      ready: spinal.core.fiber.Handle[Bool] = null
  ): Unit = sources += Source(sel, data, availableAt, ready)

  private var perfInterlock: Bool = null

  val setupLogic = during setup new Area {
    perfInterlock = host[PerfService].newCounter("interlock")
  }

  /** What writeback is producing, offered to the memory stage.
    *
    * A Handle because a consumer asks for it in its own build, which may run
    * before this one. The comparison against the address wanted is built where
    * it is asked for; what is published here is the one producer there can be.
    */
  private case class LateSource(writes: Bool, address: UInt, ready: Bool, value: Bits)
  private val lateSource = spinal.core.fiber.Handle[LateSource]()

  override def lateForward(address: UInt): LateForward = {
    val source = lateSource.get
    LateForward(
      hit = source.writes && source.address === address && address =/= 0,
      ready = source.ready,
      value = source.value
    )
  }

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
      val withDebug = AxiomParam.WITH_DEBUG_REGFILE_PORT.get
      val PortRn = 0; val PortRm = 1; val PortRd = 2
      val PortDebug = 3
      val readPorts = if (withDebug) 4 else 3

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

    val rd = ctrl(Stages.READ)
    val ex = ctrl(Stages.EXECUTE)
    val me = ctrl(Stages.MEMORY)
    val wb = ctrl(Stages.WRITEBACK)

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

    /** Mux the producers whose value has actually arrived by `upTo`.
      *
      * A source that is not ready yet is left out rather than muxed in and
      * ignored: its payload has no driver at that stage at all, so reading it
      * there would not elaborate. The interlock below covers exactly the
      * sources this leaves out.
      */
    class ResultMux(node: NodeApi, upTo: Int) extends Area {
      val value = Bits(xlen bits)
      value := B(0, xlen bits)
      for (source <- sources if source.availableAt <= upTo) {
        when(node(source.sel)) { value := node(source.data) }
      }
    }

    val forwardLate = AxiomParam.FORWARD_LATE_FROM_WRITEBACK.get
    val forwardBase = AxiomParam.FORWARD_BASE.get

    // The commit multiplexer always sees every source; the forwarding copy may
    // not, because a writeback-stage source drags a block RAM read into the
    // path, which is what FORWARD_LATE_FROM_WRITEBACK trades away.
    val forwardFromWriteback = if (forwardLate) Stages.WRITEBACK else Stages.MEMORY
    val writebackCommit = new ResultMux(wb.down, Stages.WRITEBACK)
    val writebackForward = if (forwardLate) writebackCommit else new ResultMux(wb.down, Stages.MEMORY)
    val memoryResult = new ResultMux(me.down, Stages.MEMORY)
    val executeResult = new ResultMux(ex.down, Stages.EXECUTE)
    def wbResult = writebackForward.value
    def memResult = memoryResult.value
    def exResult = executeResult.value

    // ---- commit ---------------------------------------------------------
    val wbWritesRd = wb.down.isFiring && wb.down(Global.WRITES_RD) && wb.down(Global.RD_ADDR) =/= 0
    val wbWritesBase = wb.down.isFiring && wb.down(Global.WRITES_BASE) && wb.down(Global.RN_ADDR) =/= 0

    storage.write(0, wbWritesRd, wb.down(Global.RD_ADDR), writebackCommit.value)
    storage.write(1, wbWritesBase, wb.down(Global.RN_ADDR), wb.down(Global.BASE_VALUE))

    // ---- read and forward, both in execute --------------------------------
    val exWritesRd = ex.isValid && ex.down(Global.WRITES_RD) && ex.down(Global.RD_ADDR) =/= 0
    val exWritesBase = ex.isValid && ex.down(Global.WRITES_BASE) && ex.down(Global.RN_ADDR) =/= 0
    val memWritesRd = me.isValid && me.down(Global.WRITES_RD) && me.down(Global.RD_ADDR) =/= 0
    // The upstream side for the base, as the interlock uses. A pair clears the
    // downstream flag on its first pass so the update is not performed twice,
    // and a consumer sampling during that pass would see no base write and
    // read the register file instead, which does not have it yet. The value
    // is the same on both passes, so offering it on both is the correct
    // answer as well as the simpler one.
    val memWritesBase = me.isValid && me.up(Global.WRITES_BASE) && me.down(Global.RN_ADDR) =/= 0
    val wbHasRd = wb.isValid && wb.down(Global.WRITES_RD) && wb.down(Global.RD_ADDR) =/= 0
    val wbHasBase = wb.isValid && wb.up(Global.WRITES_BASE) && wb.down(Global.RN_ADDR) =/= 0

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
      if (forwardBase) when(wbHasBase && wb.down(Global.RN_ADDR) === address) { value := wb.down(Global.BASE_VALUE) }
      when(wbHasRd && wb.down(Global.RD_ADDR) === address) { value := wbResult }
      if (forwardBase) when(memWritesBase && me.down(Global.RN_ADDR) === address) { value := me.down(Global.BASE_VALUE) }
      when(memWritesRd && me.down(Global.RD_ADDR) === address) { value := memResult }
      if (forwardBase) when(exWritesBase && ex.down(Global.RN_ADDR) === address) { value := ex.down(Global.BASE_VALUE) }
      when(exWritesRd && ex.down(Global.RD_ADDR) === address) { value := exResult }
      when(address === 0) { value := B(0, xlen bits) }
    }

    val readRn = new ReadPort(storage.PortRn, rd.down(Global.RN_ADDR))
    val readRm = new ReadPort(storage.PortRm, rd.down(Global.RM_ADDR))
    val readRd = new ReadPort(storage.PortRd, rd.down(Global.RD_ADDR))

    rd.down(Global.RS_N) := readRn.value
    rd.down(Global.RS_M) := readRm.value
    rd.down(Global.RS_D) := readRd.value

    // ---- the load-use interlock -------------------------------------------
    /** Is this node holding a producer whose value has not arrived yet?
      *
      * Two reasons it might not have. It belongs to a later stage than `upTo`,
      * which is known at elaboration; or it belongs to this one and has not
      * turned up, which is only known this cycle. A load behind a cache is the
      * second: it reaches writeback in three cycles and its data in thirty.
      */
    def unavailableAt(node: CtrlLink, upTo: Int): Bool = sources
      .map { source =>
        val late = source.availableAt > upTo
        val waiting = if (source.ready == null) False else !source.ready.get
        if (late) node.down(source.sel) else node.down(source.sel) && waiting
      }
      .reduceOption(_ || _).getOrElse(False)

    /** @param exemptLateRd whether an instruction that takes its `rd` operand
      *        in the memory stage may be let past a producer in execute. It
      *        may, and only past that one: by the time it reaches memory, that
      *        producer is in writeback, which is where it collects the value
      *        from. A producer in memory or writeback is further ahead and
      *        will have committed and gone by then, leaving nothing to collect
      *        and a stale operand, so those still hold the consumer here.
      */
    def consumerNeeds(destination: UInt, exemptLateRd: Bool = False): Bool = {
      val late = exemptLateRd && rd.down(Global.LATE_RD)
      (rd.down(Global.READS_RN) && rd.down(Global.RN_ADDR) === destination) ||
      (rd.down(Global.READS_RM) && rd.down(Global.RM_ADDR) === destination) ||
      (rd.down(Global.READS_RD) && rd.down(Global.RD_ADDR) === destination && !late)
    }

    /** @param owesBase a base write this instruction has not performed yet.
      *        Read from the upstream side in memory and writeback, because a
      *        pair defers its base update to its second pass and the
      *        downstream side says "not this one" on the first. The upstream
      *        side is held across both passes, so it says what the instruction
      *        as a whole still owes, which is the question being asked.
      */
    def blockedBy(node: CtrlLink, upTo: Int, owesBase: Bool,
                  exemptLateRd: Boolean = false): Bool = {
      val destination = node.down(Global.RD_ADDR)

      /** A pair is not exempt, whichever of its two registers is wanted.
        *
        * A load pair goes through memory and writeback twice, and the second
        * pass renames `rd` to the second register. A consumer let past it
        * arrives in memory while the second pass is in writeback, finds the
        * name it is looking for is not the one there, concludes nothing is
        * being written and uses what it read before the pair wrote anything.
        * So a producer that writes twice holds it here as it always did.
        */
      val exempt = if (exemptLateRd) !node.down(Global.WRITES_RM) else False
      val primary = unavailableAt(node, upTo) && node.down(Global.WRITES_RD) &&
        destination =/= 0 && consumerNeeds(destination, exempt)

      // A second register this instruction has promised to write but has not
      // reached yet. There is no value to forward and no stage at which one
      // appears, so it blocks outright until the write is in flight.
      val second = node.down(Global.WRITES_RM) && node.down(Global.RM_ADDR) =/= 0 &&
        consumerNeeds(node.down(Global.RM_ADDR))

      // With base forwarding off, an updated base register is the same shape
      // of promise: named, but not readable until it commits.
      val base =
        if (forwardBase) False
        else owesBase && node.down(Global.RN_ADDR) =/= 0 &&
          consumerNeeds(node.down(Global.RN_ADDR))

      node.isValid && (primary || second || base)
    }

    // Guarded on the up nodes rather than the down ones, because halting
    // clears down.valid and reading it back here would close a loop.
    //
    // Each stage is asked about the sources its own forwarding path cannot
    // supply: an atomic in execute has not touched memory yet, a load in
    // memory has not returned its data yet, and with writeback forwarding of
    // those turned off a consumer has to wait for the producer to commit.
    val blocked =
      blockedBy(ex, Stages.EXECUTE, ex.down(Global.WRITES_BASE), exemptLateRd = true) ||
        blockedBy(me, Stages.MEMORY, me.up(Global.WRITES_BASE)) ||
        blockedBy(wb, forwardFromWriteback, wb.up(Global.WRITES_BASE))
    val interlock = rd.isValid && blocked

    rd.haltWhen(interlock)
    perfInterlock := interlock && rd.down.isReady

    // ---- the late collection point -----------------------------------------
    // The same three facts the read stage's forwarding uses, asked of the
    // writeback stage alone and one stage later. Nothing here is a second
    // bypass network: there is one source, one comparator and one multiplexer,
    // because only one instruction can be the producer.
    lateSource.load(LateSource(
      writes = wbHasRd,
      address = wb.down(Global.RD_ADDR),
      ready = !unavailableAt(wb, Stages.WRITEBACK),
      value = writebackCommit.value
    ))

    // ---- debug -------------------------------------------------------------
    // A combinational peek at the committed file, for simulation and bring-up.
    // It is a whole extra copy of every bank, so a build that is not going to
    // be simulated leaves it out and ties the port off.
    val io = host[InterfaceService].io
    if (storage.withDebug) io.dbgRegData := storage.read(storage.PortDebug, io.dbgRegAddr)
    else io.dbgRegData := B(0, xlen bits)
  }
}
