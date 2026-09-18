package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

/** Every memory access: the single-register forms with their three addressing
  * modes, the pair forms, the ordered accesses and the atomics.
  *
  * Two of these need more than one pass through the memory stage, and they
  * need it for different reasons, which the pipeline API expresses directly:
  *
  *  - A pair moves two registers, so it needs two transactions downstream and
  *    uses `duplicateWhen`. This is the cracking that the pair forms were
  *    always going to cost; it is visible here rather than hidden.
  *  - An atomic needs a read and then a write, but produces one result, so it
  *    holds the stage for an extra cycle with `haltWhen` and still emits a
  *    single transaction.
  *
  * The backpressure that both rely on is the reason this core uses control
  * links at all. The earlier fixed-latency design could not have expressed
  * either without hand-written stall logic in every stage.
  */
class LsuPlugin extends AxiomPlugin {

  val SEL    = Payload(Bool())
  val RESULT = Payload(Bits(AxiomParam.XLEN bits))

  /** A load, whose data is not ready until writeback. This is what arms the
    * load-use interlock.
    */
  val SEL_LOAD = Payload(Bool())

  /** An atomic. Its old value is captured in the memory stage, but only on
    * the second of its two passes there, so it is declared as arriving in
    * writeback rather than in memory.
    *
    * That is one cycle more pessimistic than the value strictly needs, and the
    * pessimism is the point. A memory-stage declaration would be read by a
    * consumer sitting in the read stage on the atomic's *first* pass, when the
    * old value has not been captured yet and nothing is holding the consumer
    * back: the stage ahead of it is empty, so there is no backpressure. By the
    * time the atomic reaches writeback the value is registered and stable, and
    * the interlock covers the wait. Atomics are rare enough that the cycle is
    * not worth a special case in the forwarding network.
    */
  val SEL_ATOMIC = Payload(Bool())

  /** Byte address of the access, and its low bits kept separately because
    * writeback needs them to pick the lane.
    */
  val ADDRESS  = Payload(UInt(AxiomParam.PC_WIDTH bits))
  val ADDR_LOW = Payload(UInt(3 bits))

  /** The old memory value of an atomic, captured in the memory stage because
    * the bus has moved on by the time writeback runs.
    */
  val ATOMIC_OLD = Payload(Bits(AxiomParam.XLEN bits))

  private val loadOpcodes = Isa.LOADS.toSeq
  private val storeOpcodes = Isa.STORES.toSeq
  private val opcodes = loadOpcodes ++ storeOpcodes ++
    Seq(Isa.LDP, Isa.STP, Isa.LD_ORD, Isa.ST_ORD, Isa.ATOMIC)

  private def subFunctionLegal(instr: Bits): Bool = {
    val opcode = instr(Isa.OP_HI downto Isa.OP_LO)
    def opIs(values: Iterable[Int]): Bool =
      values.map(v => opcode === B(v, 6 bits)).reduce(_ || _)

    val singleModeOk = instr(15 downto 14).asUInt <= Isa.Mode.POST
    val pairModeOk = instr(10 downto 9).asUInt <= Isa.Mode.POST
    val orderOk = {
      val kind = instr(13 downto 12).asUInt
      kind === Isa.Ord.ACQREL || kind === Isa.Ord.SEQ
    }
    val atomicOk = {
      val fn = instr(10 downto 7).asUInt
      val size = instr(4 downto 3).asUInt
      val fnOk = Isa.AtomicFn.ALL.map(v => fn === v).reduce(_ || _)
      val sizeOk = size === Isa.SIZE_W || size === Isa.SIZE_D
      Bool(AxiomParam.WITH_ATOMICS.get) && fnOk && sizeOk
    }

    val result = True
    when(opIs(loadOpcodes ++ storeOpcodes)) { result := singleModeOk }
    when(opIs(Seq(Isa.LDP, Isa.STP))) { result := pairModeOk }
    when(opIs(Seq(Isa.LD_ORD, Isa.ST_ORD))) { result := orderOk }
    when(opIs(Seq(Isa.ATOMIC))) { result := atomicOk }
    result
  }

  private var trap: TrapCmd = null
  private var bus: DBus = null
  private var perfLoad: Bool = null
  private var perfIssue: Bool = null

  /** True on the cycles a load's data is actually on hand. Read by the
    * register file's interlock, which is why it is a Handle: it is claimed in
    * setup and driven in build.
    */
  private val loadDataReady = spinal.core.fiber.Handle[Bool]()

  val setupLogic = during setup new Area {
    bus = host[MemoryService].newDataPort()
    host[DecoderService].claim(SEL, opcodes, subFunctionLegal)
    // A load's data has not left memory when execute needs it, which is the
    // one hazard forwarding cannot cover and the reason the interlock exists.
    host[RegFileService].addResult(SEL_LOAD, RESULT, availableAt = Stages.WRITEBACK,
      ready = loadDataReady)
    host[RegFileService].addResult(SEL_ATOMIC, ATOMIC_OLD, availableAt = Stages.WRITEBACK)
    trap = host[TrapService].newTrapPort()
    perfLoad = host[PerfService].newCounter("load")
    perfIssue = host[PerfService].newCounter("issue")
  }

  /** What the opcode means, decoded once and carried.
    *
    * Every stage used to work this out again from the instruction word. It is
    * the same answer each time and it is cheap logic, so that looked free; it
    * was not. Measured on an ECP5 the critical path began at the instruction
    * register in execute, spent three and a half nanoseconds reaching a decode
    * on the far side of the die, four levels of logic working out whether the
    * instruction updates its base register, and only then reached the address
    * adder. A register read is half a nanosecond and has no levels at all.
    *
    * Nine bits carry everything the later stages ask about.
    */
  val IS_LOAD       = Payload(Bool())
  val IS_STORE      = Payload(Bool())
  val IS_PAIR       = Payload(Bool())
  val IS_STORE_PAIR = Payload(Bool())
  val IS_ATOMIC     = Payload(Bool())
  val ACCESS_SIZE   = Payload(UInt(2 bits))
  val ACCESS_SIGNED = Payload(Bool())
  val ACCESS_MODE   = Payload(UInt(2 bits))

  /** The same questions, answered from the payloads rather than the opcode. */
  class Carried(node: CtrlLink) {
    val isLoad = node(IS_LOAD)
    val isStore = node(IS_STORE)
    val isPair = node(IS_PAIR)
    val isStorePair = node(IS_STORE_PAIR)
    val isAtomic = node(IS_ATOMIC)
    val size = node(ACCESS_SIZE)
    val signed = node(ACCESS_SIGNED)
    val mode = node(ACCESS_MODE)
  }

  /** The decode itself, used once, in the decode stage. */
  class Kind(instr: Bits) {
    val opcode = instr(Isa.OP_HI downto Isa.OP_LO)
    def opIs(value: Int): Bool = opcode === B(value, 6 bits)
    def opIs(values: Iterable[Int]): Bool =
      values.map(v => opcode === B(v, 6 bits)).reduce(_ || _)

    val isSingleLoad  = opIs(loadOpcodes)
    val isSingleStore = opIs(storeOpcodes)
    val isLoadPair    = opIs(Isa.LDP)
    val isStorePair   = opIs(Isa.STP)
    val isPair        = isLoadPair || isStorePair
    val isOrderedLoad  = opIs(Isa.LD_ORD)
    val isOrderedStore = opIs(Isa.ST_ORD)
    val isAtomic      = opIs(Isa.ATOMIC)

    val isLoad  = isSingleLoad || isLoadPair || isOrderedLoad
    val isStore = isSingleStore || isStorePair || isOrderedStore

    /** Access size, log2 of the byte count. Constant per opcode except for the
      * ordered and atomic forms, which carry it in a field.
      */
    val size = UInt(2 bits)
    size := U(Isa.SIZE_D, 2 bits)
    switch(opcode) {
      is(B(Isa.LDB, 6 bits), B(Isa.LDBU, 6 bits), B(Isa.STB, 6 bits)) { size := Isa.SIZE_B }
      is(B(Isa.LDH, 6 bits), B(Isa.LDHU, 6 bits), B(Isa.STH, 6 bits)) { size := Isa.SIZE_H }
      is(B(Isa.LDW, 6 bits), B(Isa.LDWU, 6 bits), B(Isa.STW, 6 bits)) { size := Isa.SIZE_W }
      is(B(Isa.LD_ORD, 6 bits), B(Isa.ST_ORD, 6 bits)) { size := instr(15 downto 14).asUInt }
      is(B(Isa.ATOMIC, 6 bits)) { size := instr(4 downto 3).asUInt }
    }

    /** Loads that sign extend. The ordered loads deliberately do not have a
      * signed form: they address a lock, a flag or a count. Atomics do sign
      * extend their returned value, matching the W arithmetic convention.
      */
    val signed = opIs(Isa.LDB) || opIs(Isa.LDH) || opIs(Isa.LDW) || isAtomic

    val mode = UInt(2 bits)
    mode := U(Isa.Mode.OFFSET, 2 bits)
    when(isSingleLoad || isSingleStore) { mode := instr(15 downto 14).asUInt }
    when(isPair) { mode := instr(10 downto 9).asUInt }
  }

  val logic = during build new Area {
    val xlen = AxiomParam.XLEN.get

    /** The one-deep response buffer, and the two places a response is taken.
      *
      * A load's data is taken in writeback, where the lane is picked and
      * extended. An atomic's is taken in memory, because its second pass needs
      * the old value to compute the new one. Those are the only two readers,
      * and at most one command is outstanding, so one entry is enough.
      *
      * Buffering at all is what the contract change costs: `rvalid` is a pulse
      * and the stage that wants it may be held for some other reason on the
      * cycle it arrives.
      */
    val response = new Area {
      val data = Reg(Bits(xlen bits)) init 0
      val full = Reg(Bool()) init False
      val takenByMemory = Bool()
      val takenByWriteback = Bool()
      val taken = takenByMemory || takenByWriteback

      when(bus.rvalid) { data := bus.rdata }
      full := (full || bus.rvalid) && !taken

      // Arriving counts as present, so a memory that answers the next cycle
      // never costs a cycle in the buffer.
      val present = full || bus.rvalid
      val word = Mux(full, data, bus.rdata)
    }
    loadDataReady.load(response.present)

    // =================================================================
    // Decode: split the claim into the two result paths, early enough for
    // the interlock to see it
    // =================================================================
    val classify = new Area {
      val node = ctrl(Stages.DECODE)
      val kind = new Kind(node(Global.INSTRUCTION))
      node(SEL_LOAD) := node(SEL) && kind.isLoad
      node(SEL_ATOMIC) := node(SEL) && kind.isAtomic

      node(IS_LOAD) := kind.isLoad
      node(IS_STORE) := kind.isStore
      node(IS_PAIR) := kind.isPair
      node(IS_STORE_PAIR) := kind.isStorePair
      node(IS_ATOMIC) := kind.isAtomic
      node(ACCESS_SIZE) := kind.size
      node(ACCESS_SIGNED) := kind.signed
      node(ACCESS_MODE) := kind.mode

      // An indexed access writes its base register, and the interlock in the
      // read stage asks about that. Deciding it here rather than in execute is
      // what keeps a decode out of the interlock's path as well as the address
      // adder's.
      node(Global.WRITES_BASE) := node(SEL) && kind.mode =/= Isa.Mode.OFFSET

      // A single store hands its data straight to the bus two stages after
      // reading it, so it is the one instruction that can start before that
      // data exists. Everything else computes with what it reads.
      node(Global.LATE_RD) := node(SEL) && kind.isSingleStore
    }

    // =================================================================
    // Execute: address generation, base update and the alignment check
    // =================================================================
    val execute = new Area {
      val node = ctrl(Stages.EXECUTE)
      val instr = node(Global.INSTRUCTION)
      val kind = new Carried(node)

      val base = node(Global.RS_N).asUInt
      val sum = base + node(Global.IMM).asUInt

      // Post-index uses the base as it stands and writes back the sum;
      // pre-index uses the sum for both.
      val address = Mux(kind.mode === Isa.Mode.POST, base, sum)
      node(ADDRESS) := address
      node(ADDR_LOW) := address(2 downto 0)

      node(Global.BASE_VALUE) := sum.asBits

      // A pair is two doublewords at address and address+8, so it needs the
      // alignment of a doubleword whatever its elements are.
      val sizeMask = (((U(1, 4 bits) << kind.size) - 1)(2 downto 0))
      val misaligned = (address(2 downto 0) & sizeMask) =/= 0

      trap.raise(node.isValid && node(SEL) && misaligned && !kind.isStore, Isa.Cause.MISALIGNED_LOAD)
      trap.raise(node.isValid && node(SEL) && misaligned && kind.isStore, Isa.Cause.MISALIGNED_STORE)
    }

    // =================================================================
    // Memory: drive the bus, and take a second pass where one is needed
    // =================================================================
    val memory = new Area {
      val node = ctrl(Stages.MEMORY)
      val instr = node(Global.INSTRUCTION)
      val kind = new Carried(node)
      val active = node.isValid && node(SEL)

      /** Second pass through this stage. A pair uses it for its second
        * register, an atomic for its write.
        */
      val beat = Reg(Bool()) init False

      node.duplicateWhen(active && kind.isPair && !beat)
      node.haltWhen(active && kind.isAtomic && !beat)

      // A pair's second pass starts when the first one actually leaves, not
      // merely when the pair arrives. Those were the same cycle while the
      // memory always accepted, and are not once it can refuse: the beat would
      // advance past an access that had not been issued, and the pair would
      // touch one address twice.
      when(active && kind.isPair && !beat && node.down.isMoving) { beat := True }
      when(node.down.isMoving && beat) { beat := False }
      when(!node.isValid) { beat := False }

      // The second half of a pair targets the rm field and must not repeat the
      // base update, so both are overridden for that pass only.
      val destination = node.bypass(Global.RD_ADDR)
      destination := node.up(Global.RD_ADDR)
      when(kind.isPair && beat) { destination := node.up(Global.RM_ADDR) }

      // Once the second pass is the one in flight, RD_ADDR names the second
      // register itself and the promise made at decode is being kept.
      val writesRm = node.bypass(Global.WRITES_RM)
      writesRm := node.up(Global.WRITES_RM)
      when(kind.isPair && beat) { writesRm := False }

      val writesBase = node.bypass(Global.WRITES_BASE)
      writesBase := node.up(Global.WRITES_BASE)
      when(kind.isPair && !beat) { writesBase := False }

      val lastBeat = node.bypass(Global.LAST_BEAT)
      lastBeat := node.up(Global.LAST_BEAT)
      when(kind.isPair && !beat) { lastBeat := False }

      val address = node(ADDRESS) + Mux(kind.isPair && beat, U(8), U(0)).resized
      val addressLow = node(ADDR_LOW)

      /** The bus handshake for this beat.
        *
        * A beat offers its command until the memory takes it, and the stage
        * holds until then. `issued` is what stops the same access being asked
        * for twice while the stage waits for some later reason.
        *
        * Never two commands in flight: the next beat waits for the answer to
        * the last one, and for the buffer to be emptied by whoever wanted it.
        * That is what keeps the response buffer one deep.
        */
      val issued = Reg(Bool()) init False
      val outstanding = Reg(Bool()) init False
      val stillOwed = outstanding && !bus.rvalid
      val bufferBusy = response.full && !response.taken

      /** The atomic's read, taken here rather than in writeback, because the
        * second pass computes what it writes from what the first pass read.
        */
      val atomicRead = new Area {
        val arrived = active && kind.isAtomic && !beat && issued && response.present
        val value = Reg(Bits(xlen bits)) init 0
        // Clearing `issued` is what lets the second pass ask for the bus. The
        // two passes of an atomic do not leave the stage between them, so the
        // usual clear on the way out never happens, and without this the write
        // would simply never be offered. Never both in one cycle: arriving
        // needs a command already accepted, offering needs none.
        when(arrived) { value := response.word; beat := True; issued := False }
      }
      response.takenByMemory := atomicRead.arrived

      // ---- atomic read-modify-write -----------------------------------
      val atomic = new Area {
        val fn = instr(10 downto 7).asUInt
        val raw = (atomicRead.value >> (addressLow @@ U"000")).resize(xlen)

        def extend(value: Bits, signed: Boolean): Bits = {
          val out = Bits(xlen bits)
          out := value
          when(kind.size === Isa.SIZE_W) {
            out := (if (signed) value(31 downto 0).asSInt.resize(xlen).asBits
                    else value(31 downto 0).asUInt.resize(xlen).asBits)
          }
          out
        }

        val old = extend(raw, signed = true)
        val operand = extend(node(Global.RS_M), signed = true)
        val compare = extend(node(Global.RS_D), signed = true)

        val newValue = Bits(xlen bits)
        newValue := operand
        switch(fn) {
          is(Isa.AtomicFn.SWP)  { newValue := operand }
          is(Isa.AtomicFn.ADD)  { newValue := (old.asUInt + operand.asUInt).asBits }
          is(Isa.AtomicFn.AND)  { newValue := old & operand }
          is(Isa.AtomicFn.OR)   { newValue := old | operand }
          is(Isa.AtomicFn.XOR)  { newValue := old ^ operand }
          is(Isa.AtomicFn.CAS)  { newValue := operand }
          is(Isa.AtomicFn.MIN)  { newValue := Mux(old.asSInt < operand.asSInt, old, operand) }
          is(Isa.AtomicFn.MAX)  { newValue := Mux(old.asSInt > operand.asSInt, old, operand) }
          is(Isa.AtomicFn.MINU) { newValue := Mux(old.asUInt < operand.asUInt, old, operand) }
          is(Isa.AtomicFn.MAXU) { newValue := Mux(old.asUInt > operand.asUInt, old, operand) }
        }
        val commits = fn =/= Isa.AtomicFn.CAS || compare === old
      }

      node(ATOMIC_OLD) := atomic.old

      /** The store data that was not ready when the store went past.
        *
        * The interlock lets a single store past a producer still in execute,
        * on the understanding that the store collects the value here, where
        * that producer has reached writeback. Three cases, and the third is
        * why there is a register:
        *
        *  - writeback is not writing this register: nothing was skipped and
        *    what the read stage read is the value.
        *  - it is, and the value is there: take it.
        *  - it is, and the value is not there yet, because the producer is a
        *    load still waiting on memory. Then the store waits too. The
        *    producer leaves writeback the moment its data lands, so the value
        *    is caught in a register on the way past rather than read again
        *    from a stage that no longer holds it.
        */
      val late = new Area {
        val wanted = node.isValid && node.up(Global.LATE_RD)
        val forward = host[RegFileService].lateForward(node.up(Global.RD_ADDR))

        val held = Reg(Bits(xlen bits)) init 0
        val have = Reg(Bool()) init False
        when(wanted && forward.hit && forward.ready && !have) {
          held := forward.value
          have := True
        }
        // One collection per instruction, whichever way it leaves.
        when(node.down.isMoving) { have := False }

        /** Taken combinationally when it is there, so a store that arrives
          * beside its producer costs nothing at all.
          *
          * This puts the writeback stage's result multiplexer in front of the
          * bus write data, which looked like the sort of thing that ends up in
          * the critical path. It was measured both ways: collecting through
          * the register instead costs sixty-four cycles on a copy loop and
          * buys two tenths of a megahertz, which is nothing. The path that
          * limits this core is the command arbitration further down, not the
          * data.
          */
        val waiting = wanted && forward.hit && !forward.ready && !have
        val value = Mux(have, held, Mux(forward.hit, forward.value, node(Global.RS_D)))
      }

      // The stage holds, and the command below is not offered either: a
      // command accepted now would carry whatever the read stage happened to
      // read.
      node.haltWhen(late.waiting)

      // ---- bus drive ----------------------------------------------------
      val storeData = Bits(xlen bits)
      storeData := late.value
      when(kind.isStorePair && beat) { storeData := node(Global.RS_M) }
      when(kind.isAtomic) { storeData := atomic.newValue }

      // The data shifts by a bit offset and the mask by a byte offset. Using
      // one for both silently drops every store whose lane is past the first.
      val bitShift = addressLow @@ U"000"
      val laneMask = UInt(8 bits)
      switch(kind.size) {
        is(Isa.SIZE_B) { laneMask := 0x01 }
        is(Isa.SIZE_H) { laneMask := 0x03 }
        is(Isa.SIZE_W) { laneMask := 0x0f }
        default        { laneMask := 0xff }
      }

      // A compare-and-swap that finds the wrong value wants no command at all
      // on its second pass. Under a fixed-latency bus that was a read nobody
      // looked at; here it would be a response nobody takes, and the buffer
      // would still be holding it when the next load wanted the buffer.
      val compareFailed = kind.isAtomic && beat && !atomic.commits
      val wantsCommand = active && !compareFailed

      bus.enable := wantsCommand && !issued && !stillOwed && !bufferBusy && !late.waiting
      bus.write := kind.isStore || (kind.isAtomic && beat && atomic.commits)
      bus.address := address.resized
      bus.wdata := (storeData.asUInt << bitShift).resize(xlen).asBits
      bus.mask := (laneMask << addressLow).resize(8).asBits

      val accepted = bus.enable && bus.ready
      // Acceptance wins over the answer arriving, for the same reason it does
      // in fetch: a read accepted on the cycle the previous answer comes back
      // is owed one of its own, and a memory that pipelines can do both at
      // once.
      outstanding := (outstanding && !bus.rvalid) || (accepted && !bus.write)
      when(accepted) { issued := True }
      when(node.down.isMoving) { issued := False }

      val waiting = wantsCommand && !issued && !accepted
      node.haltWhen(waiting)
      perfIssue := waiting && node.down.isReady
    }

    // =================================================================
    // Writeback: pick the lane and extend it
    // =================================================================
    val writeback = new Area {
      val node = ctrl(Stages.WRITEBACK)
      val instr = node(Global.INSTRUCTION)
      val kind = new Carried(node)

      // A load is the only thing here that is owed an answer. Everything else
      // passes straight through, so a store never waits for memory twice.
      val expectsData = node.isValid && node(SEL) && kind.isLoad
      val waiting = expectsData && !response.present
      node.haltWhen(waiting)
      perfLoad := waiting && node.down.isReady
      // isMoving for the same reason it is used in the fetch buffer: a
      // cancelled transaction issued its command all the same, and the answer
      // it is owed has to be taken out of the way.
      response.takenByWriteback := expectsData && node.up.isMoving

      val shifted = (response.word >> (node(ADDR_LOW) @@ U"000")).resize(xlen)

      val extended = Bits(xlen bits)
      extended := shifted
      switch(kind.size) {
        is(Isa.SIZE_B) {
          extended := Mux(kind.signed, shifted(7 downto 0).asSInt.resize(xlen).asBits,
            shifted(7 downto 0).asUInt.resize(xlen).asBits)
        }
        is(Isa.SIZE_H) {
          extended := Mux(kind.signed, shifted(15 downto 0).asSInt.resize(xlen).asBits,
            shifted(15 downto 0).asUInt.resize(xlen).asBits)
        }
        is(Isa.SIZE_W) {
          extended := Mux(kind.signed, shifted(31 downto 0).asSInt.resize(xlen).asBits,
            shifted(31 downto 0).asUInt.resize(xlen).asBits)
        }
      }

      node(RESULT) := extended
    }
  }
}
