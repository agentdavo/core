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
      * distributed RAM, and the live value table is one bit wide, so it lives
      * in the same kind of memory rather than in flip-flops with a
      * multiplexer to index it.
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

      /** Which bank holds the current value of each register.
        *
        * One bit per register, and the reason it used to be flip-flops is that
        * it has two writers: a destination write and a base write can land in
        * the same cycle, and a distributed RAM has one write port. Indexing a
        * vector of thirty-two registers is a thirty-two to one multiplexer,
        * four levels of LUT with a wire between each, and it sat in front of
        * the bank multiplexer on the longest path in the core.
        *
        * So the table is two tables, each with one writer, and the bank is the
        * exclusive or of the two. Writing through port zero makes them equal;
        * writing through port one makes them differ. Both are memories, so a
        * read is two cells and an exclusive or rather than four levels of
        * multiplexer, and yosys has a mapping for it.
        *
        * Each table is copied per read port, as the banks are, plus one copy
        * for the other port's write logic to read.
        */
      val liveCopies = readPorts + 1
      val LiveWriteCopy = readPorts

      val live = Array.tabulate(2, liveCopies) { (write, copy) =>
        // Four bits wide because that is the narrowest the part's LUT RAM
        // comes in; only the bottom bit is read. Initialised like the banks it
        // selects between, so that a register never written reads as zero in
        // simulation rather than as unknown.
        val table = Mem(Bits(4 bits), count)
        table.init(Seq.fill(count)(B(0, 4 bits)))
        table.setCompositeName(this, s"live_${write}_$copy")
        table
      }

      def write(port: Int, enable: Bool, address: UInt, data: Bits): Unit = {
        for (read <- 0 until readPorts) banks(port)(read).write(address, data, enable)

        // Equal means bank zero and different means bank one, so a write
        // through port zero copies the other table and a write through port
        // one inverts it.
        val other = live(1 - port)(LiveWriteCopy).readAsync(address).lsb
        val mark = if (port == 0) other else !other
        for (copy <- 0 until liveCopies) {
          live(port)(copy).write(address, B(4 bits, default -> mark), enable)
        }
      }

      def read(port: Int, address: UInt): Bits = {
        val fromOne = live(0)(port).readAsync(address).lsb ^ live(1)(port).readAsync(address).lsb
        Mux(fromOne, banks(1)(port).readAsync(address), banks(0)(port).readAsync(address))
      }
    }

    val rd = ctrl(Stages.READ)
    val ex = ctrl(Stages.EXECUTE)
    val me = ctrl(Stages.MEMORY)
    val wb = ctrl(Stages.WRITEBACK)

    val forwardLate = AxiomParam.FORWARD_LATE_FROM_WRITEBACK.get
    val forwardBase = AxiomParam.FORWARD_BASE.get

    /** The result, chosen once and then carried.
      *
      * Each stage picks between the producers that finish in it and passes on
      * what the stage before decided, so the multiplexer in any one stage is
      * two or three wide instead of eight, and a stage that forwards reads a
      * payload rather than building a multiplexer of its own. Four eight-input
      * multiplexers sixty-four bits wide were the largest single block of logic
      * in this core.
      *
      * A source is only muxed in at a stage at or after the one it says it is
      * available in. Its payload has no driver before that, so reading it
      * earlier would not elaborate; the interlock covers exactly the sources
      * this leaves out.
      */
    val result = new Area {
      def choose(node: CtrlLink, at: Int, carried: Bits): Bits = {
        val value = Bits(xlen bits)
        value := carried
        for (source <- sources if source.availableAt == at) {
          when(node.down(source.sel)) { value := node.down(source.data) }
        }
        value
      }

      ex.down(Global.RESULT) := choose(ex, Stages.EXECUTE, B(0, xlen bits))

      val memory = me.bypass(Global.RESULT)
      memory := choose(me, Stages.MEMORY, me.up(Global.RESULT))

      val writeback = wb.bypass(Global.RESULT)
      writeback := choose(wb, Stages.WRITEBACK, wb.up(Global.RESULT))
    }

    /** Whether the writeback stage offers its result to the read stage at all.
      *
      * With one carried result there is no longer an early half of it to
      * forward separately: the value in writeback is whatever that instruction
      * produces, load data included. So this now means what it says, and a
      * build that turns it off waits for every writeback producer to commit
      * rather than only the late ones.
      */
    val forwardFromWriteback = if (forwardLate) Stages.WRITEBACK else Stages.READ
    def wbResult = wb.down(Global.RESULT)
    def memResult = me.down(Global.RESULT)
    def exResult = ex.down(Global.RESULT)

    // ---- commit ---------------------------------------------------------
    val wbWritesRd = wb.down.isFiring && wb.down(Global.WRITES_RD) && wb.down(Global.RD_ADDR) =/= 0
    val wbWritesBase = wb.down.isFiring && wb.down(Global.WRITES_BASE) && wb.down(Global.RN_ADDR) =/= 0

    storage.write(0, wbWritesRd, wb.down(Global.RD_ADDR), wb.down(Global.RESULT))
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
      * Six legs, each a producer stage writing either its destination or its
      * base register, with the register file's own value as the fallback and
      * the youngest producer winning. Written as a chain of conditional
      * assignments that is a priority multiplexer six deep, and it measured
      * 15.8 ns on the fabric probe: a compare, a multiplexer and a wire, six
      * times in series.
      *
      * So the priority is resolved on the six hit bits, which are one LUT
      * each, and the data goes through one balanced one-hot multiplexer. The
      * same legs that way measured 7.4 ns. An earlier note here recorded the
      * one-hot form as more area for little speed; that was measured when the
      * read port was not the critical structure of the core, and it is now.
      *
      * Legs are listed youngest first, because that is the priority.
      */
    class ReadPort(port: Int, address: UInt) extends Area {
      def hits(writes: Bool, producer: UInt): Bool = writes && producer === address

      val legs = ArrayBuffer[(Bool, Bits)]()
      legs += hits(exWritesRd, ex.down(Global.RD_ADDR)) -> exResult
      if (forwardBase) legs += hits(exWritesBase, ex.down(Global.RN_ADDR)) -> ex.down(Global.BASE_VALUE)
      legs += hits(memWritesRd, me.down(Global.RD_ADDR)) -> memResult
      if (forwardBase) legs += hits(memWritesBase, me.down(Global.RN_ADDR)) -> me.down(Global.BASE_VALUE)
      if (forwardLate) {
        legs += hits(wbHasRd, wb.down(Global.RD_ADDR)) -> wbResult
        if (forwardBase) legs += hits(wbHasBase, wb.down(Global.RN_ADDR)) -> wb.down(Global.BASE_VALUE)
      }

      val hit = legs.map(_._1)
      // Chosen when it hits and nothing younger does.
      val chosen = hit.zipWithIndex.map { case (h, i) => h && !hit.take(i).fold(False)(_ || _) }
      val fromFile = !hit.reduce(_ || _)

      def spread(bit: Bool): Bits = B(xlen bits, default -> bit)
      val candidates = chosen.zip(legs.map(_._2)).map { case (c, v) => v & spread(c) } :+
        (storage.read(port, address) & spread(fromFile))

      val value = Bits(xlen bits)
      value := candidates.reduceBalancedTree(_ | _) & spread(address =/= 0)
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
      value = wb.down(Global.RESULT)
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
