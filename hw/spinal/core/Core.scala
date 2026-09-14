package core

import spinal.core._
import spinal.lib._

/** Control bits that survive past execute, which is much less than a full
  * [[CoreCtrl]]. Kept separate so the memory and writeback pipeline registers
  * stay narrow. `isStore` is not here because it dies in the memory stage; it
  * rides in its own register alongside the store data.
  */
case class LateCtrl() extends Bundle {
  val regWrite  = Bool()
  val isLoad    = Bool()
  val memSize   = UInt(2 bits)
  val memSigned = Bool()
}

/** The decode/execute pipeline register. */
case class ExStage(addressWidth: Int) extends Bundle {
  val pc      = UInt(addressWidth bits)
  val ctrl    = CoreCtrl()
  val rd      = UInt(Isa.REG_ADDR_BITS bits)
  val rs1Addr = UInt(Isa.REG_ADDR_BITS bits)
  val rs2Addr = UInt(Isa.REG_ADDR_BITS bits)
  val imm     = Bits(Isa.XLEN bits)
  val offset  = UInt(Isa.XLEN bits)
  val rs1Data = Bits(Isa.XLEN bits)
  val rs2Data = Bits(Isa.XLEN bits)
}

/** The execute/memory and memory/writeback pipeline registers.
  *
  * Store data is deliberately not in here: it is only needed in the memory
  * stage, so it rides in its own register and does not widen writeback.
  */
case class LateStage(addressWidth: Int) extends Bundle {
  val pc      = UInt(addressWidth bits)
  val ctrl    = LateCtrl()
  val rd      = UInt(Isa.REG_ADDR_BITS bits)
  val result  = Bits(Isa.XLEN bits)
  val addrLow = UInt(2 bits)
}

/** C1: the first CORE-32 implementation.
  *
  * A classic five stage in-order pipeline — fetch, decode, execute, memory,
  * writeback — with full forwarding, a single-cycle load-use interlock, and
  * branches resolved in execute against a predict-not-taken front end.
  *
  * Hazard summary, by distance from the consumer:
  *
  *  - one instruction ahead: forwarded in execute from the memory stage
  *  - two ahead: forwarded in execute from the writeback stage
  *  - three ahead: bypassed into the register file read in decode
  *
  * A load one instruction ahead of its consumer is the only case forwarding
  * cannot cover, because the data has not left memory yet. That is what the
  * interlock is for; it costs exactly one cycle and turns the case into the
  * two-ahead case, which forwarding does cover.
  *
  * Both buses are tightly coupled fixed-latency memories with no backpressure,
  * so the pipeline never stalls on memory. Adding a stallable bus is the main
  * planned change to this microarchitecture.
  */
class Core(val cfg: CoreConfig = CoreConfig()) extends Component {
  import Isa.{XLEN, REG_COUNT, REG_ADDR_BITS, Cause}

  private val aw = cfg.addressWidth

  val io = new Bundle {
    val ibus = master(IBus(aw))
    val dbus = master(DBus(aw))

    /** Execution has stopped, either on HALT or on a trap. */
    val halted = out Bool ()

    /** The stop was a trap rather than a HALT. */
    val trapped = out Bool ()
    val cause   = out UInt (Cause.WIDTH bits)
    val trapPc  = out UInt (aw bits)

    /** Free-running counters, for reporting cycles per instruction. */
    val cycleCount  = out UInt (32 bits)
    val retireCount = out UInt (32 bits)

    /** Combinational register file peek, for simulation and debug. */
    val dbgRegAddr = in UInt (REG_ADDR_BITS bits)
    val dbgRegData = out Bits (XLEN bits)

    /** Address of the instruction retiring this cycle, for tracing. */
    val dbgRetireValid = out Bool ()
    val dbgRetirePc    = out UInt (aw bits)
  }

  // =======================================================================
  // Signals that cross stages. Declared here, driven by the stage that owns
  // them, so that each stage can read the ones it needs regardless of the
  // order the areas appear in.
  // =======================================================================

  /** Decode is holding an instruction; fetch must not advance. */
  val stall = Bool()

  /** Execute redirected the PC; the two younger instructions must be killed. */
  val flush      = Bool()
  val redirect   = Bool()
  val redirectPc = UInt(aw bits)

  /** Execute hit a HALT or a trap this cycle. */
  val stopNow = Bool()

  val haltedReg  = Reg(Bool()) init False
  val trappedReg = Reg(Bool()) init False
  val causeReg   = Reg(UInt(Cause.WIDTH bits)) init Cause.NONE
  val trapPcReg  = Reg(UInt(aw bits)) init 0

  val halted = haltedReg || stopNow

  // =======================================================================
  // Register file
  // =======================================================================

  val regs = Vec.fill(REG_COUNT)(Reg(Bits(XLEN bits)) init 0)

  val wbEnable = Bool()
  val wbAddr   = UInt(REG_ADDR_BITS bits)
  val wbData   = Bits(XLEN bits)

  when(wbEnable && wbAddr =/= 0) {
    regs(wbAddr) := wbData
  }

  /** Read a register, with the writeback happening this cycle bypassed in.
    * Without this bypass an instruction three ahead of its consumer would be
    * missed, since it leaves the pipeline before execute could forward it.
    */
  private def regRead(addr: UInt): Bits =
    Mux(wbEnable && addr =/= 0 && wbAddr === addr, wbData, regs(addr))

  io.dbgRegData := regs(io.dbgRegAddr)

  // =======================================================================
  // Pipeline registers
  // =======================================================================

  val idValid = Reg(Bool()) init False
  val idPc    = Reg(UInt(aw bits)) init cfg.resetVector

  val exValid = Reg(Bool()) init False
  val ex      = Reg(ExStage(aw)) init ExStage(aw).getZero

  val memValid     = Reg(Bool()) init False
  val mem          = Reg(LateStage(aw)) init LateStage(aw).getZero
  val memStoreData = Reg(Bits(XLEN bits)) init 0
  val memIsStore   = Reg(Bool()) init False

  val wbValid = Reg(Bool()) init False
  val wb      = Reg(LateStage(aw)) init LateStage(aw).getZero

  // =======================================================================
  // Writeback
  //
  // Declared ahead of execute because execute forwards from it.
  // =======================================================================

  val writeback = new Area {
    val raw = io.dbus.rdata

    val byteLane = Bits(8 bits)
    switch(wb.addrLow) {
      is(0) { byteLane := raw(7 downto 0) }
      is(1) { byteLane := raw(15 downto 8) }
      is(2) { byteLane := raw(23 downto 16) }
      is(3) { byteLane := raw(31 downto 24) }
    }
    val halfLane = Mux(wb.addrLow(1), raw(31 downto 16), raw(15 downto 0))

    val loadData = Bits(XLEN bits)
    switch(wb.ctrl.memSize) {
      is(U(Isa.SIZE_B, 2 bits)) {
        loadData := Mux(wb.ctrl.memSigned, byteLane.asSInt.resize(XLEN).asBits, byteLane.resize(XLEN))
      }
      is(U(Isa.SIZE_H, 2 bits)) {
        loadData := Mux(wb.ctrl.memSigned, halfLane.asSInt.resize(XLEN).asBits, halfLane.resize(XLEN))
      }
      default {
        loadData := raw
      }
    }

    /** What this instruction actually writes to the register file. */
    val value = Mux(wb.ctrl.isLoad, loadData, wb.result)
  }

  wbEnable := wbValid && wb.ctrl.regWrite
  wbAddr   := wb.rd
  wbData   := writeback.value

  // =======================================================================
  // Fetch
  //
  // The address register is held while stalled, and the bus contract says the
  // memory holds its output while `enable` is low, so the instruction in
  // decode stays stable without a shadow register.
  // =======================================================================

  val fetch = new Area {
    val pc     = Reg(UInt(aw bits)) init cfg.resetVector
    val enable = !stall && !halted

    io.ibus.enable  := enable
    io.ibus.address := pc

    when(enable) { pc := pc + 4 }
    when(redirect) { pc := redirectPc }
  }

  // =======================================================================
  // Decode
  // =======================================================================

  val decode = new Area {
    val decoder = Decoder(cfg)
    decoder.io.instr := io.ibus.data
    val d = decoder.io.decoded

    val rs1Data = regRead(d.rs1)
    val rs2Data = regRead(d.rs2)

    /** The load-use interlock. `usesRs1`/`usesRs2` keep this from firing on a
      * register field that is really immediate bits, as in MOVI.
      */
    val loadUse = idValid && exValid && ex.ctrl.isLoad && ex.rd =/= 0 &&
      ((d.usesRs1 && d.rs1 === ex.rd) || (d.usesRs2 && d.rs2 === ex.rd))

    when(!stall && !halted) {
      idValid := True
      idPc    := fetch.pc
    }
    when(flush || halted) {
      idValid := False
    }
  }

  stall := decode.loadUse

  // =======================================================================
  // Execute
  // =======================================================================

  val execute = new Area {

    /** Pick the newest definition of a register: the memory stage first, then
      * the writeback stage, then whatever decode read.
      */
    private def forward(addr: UInt, fromRegFile: Bits): Bits = {
      val value = Bits(XLEN bits)
      when(memValid && mem.ctrl.regWrite && mem.rd =/= 0 && mem.rd === addr) {
        value := mem.result
      }.elsewhen(wbValid && wb.ctrl.regWrite && wb.rd =/= 0 && wb.rd === addr) {
        value := writeback.value
      }.otherwise {
        value := fromRegFile
      }
      value
    }

    val srcA = forward(ex.rs1Addr, ex.rs1Data)
    val srcB = forward(ex.rs2Addr, ex.rs2Data)

    val alu = Alu(cfg.hasMultiplier)
    alu.io.fn := ex.ctrl.aluFn
    alu.io.a  := srcA
    alu.io.b  := Mux(ex.ctrl.useImm, ex.imm, srcB)

    // ---- branch resolution ------------------------------------------
    val equal      = srcA === srcB
    val ltSigned   = srcA.asSInt < srcB.asSInt
    val ltUnsigned = srcA.asUInt < srcB.asUInt

    val conditionMet = Bool()
    switch(ex.ctrl.branchFn) {
      is(B"000") { conditionMet := equal }        // BEQ
      is(B"001") { conditionMet := !equal }       // BNE
      is(B"010") { conditionMet := ltSigned }     // BLT
      is(B"011") { conditionMet := !ltSigned }    // BGE
      is(B"100") { conditionMet := ltUnsigned }   // BLTU
      is(B"101") { conditionMet := !ltUnsigned }  // BGEU
      default    { conditionMet := False }
    }

    val relativeTarget = ex.pc + ex.offset.resize(aw)
    // Computed targets have their low two bits cleared rather than trapping,
    // so returning through a tagged pointer stays safe.
    val indirectTarget = (alu.io.result(aw - 1 downto 2).asUInt @@ U"00")

    val takeBranch = exValid && ex.ctrl.isBranch && conditionMet
    val takeJump   = exValid && (ex.ctrl.isJump || ex.ctrl.isIndirect)

    redirect   := (takeBranch || takeJump) && !halted
    redirectPc := Mux(ex.ctrl.isIndirect, indirectTarget, relativeTarget)
    flush      := redirect

    // ---- traps --------------------------------------------------------
    val address = alu.io.result.asUInt

    val sizeIsWord = ex.ctrl.memSize === U(Isa.SIZE_W, 2 bits)
    val sizeIsHalf = ex.ctrl.memSize === U(Isa.SIZE_H, 2 bits)
    val misaligned = (sizeIsWord && address(1 downto 0) =/= 0) || (sizeIsHalf && address(0))

    val misalignedLoad  = ex.ctrl.isLoad && misaligned
    val misalignedStore = ex.ctrl.isStore && misaligned

    val trapping = exValid && (ex.ctrl.illegal || misalignedLoad || misalignedStore)
    val halting  = exValid && ex.ctrl.isHalt

    stopNow := (trapping || halting) && !haltedReg

    val cause = UInt(Cause.WIDTH bits)
    when(ex.ctrl.illegal) {
      cause := Cause.ILLEGAL
    }.elsewhen(misalignedLoad) {
      cause := Cause.MISALIGNED_LOAD
    }.elsewhen(misalignedStore) {
      cause := Cause.MISALIGNED_STORE
    }.otherwise {
      cause := Cause.NONE
    }

    when(stopNow) {
      haltedReg  := True
      trappedReg := trapping
      causeReg   := cause
      trapPcReg  := ex.pc
    }

    // ---- what this instruction writes back -----------------------------
    val result = Bits(XLEN bits)
    when(ex.ctrl.link) {
      result := (ex.pc + 4).asBits.resize(XLEN)
    }.elsewhen(ex.ctrl.isMovi) {
      result := ex.imm
    }.elsewhen(ex.ctrl.isMovhi) {
      // Splice: the immediate already sits in bits 31:10, keep the low ten.
      result := ex.imm | (srcB & B(0x3ff, XLEN bits))
    }.elsewhen(ex.ctrl.isAddpc) {
      result := (ex.pc + ex.imm.asUInt.resize(aw)).asBits.resize(XLEN)
    }.otherwise {
      result := alu.io.result
    }

    // ---- decode/execute register ---------------------------------------
    when(!stall) {
      exValid     := idValid
      ex.pc       := idPc
      ex.ctrl     := decode.d.ctrl
      ex.rd       := decode.d.rd
      ex.rs1Addr  := decode.d.rs1
      ex.rs2Addr  := decode.d.rs2
      ex.imm      := decode.d.imm
      ex.offset   := decode.d.offset
      ex.rs1Data  := decode.rs1Data
      ex.rs2Data  := decode.rs2Data
    }.otherwise {
      exValid := False // the interlock injects a bubble
    }
    when(flush || halted) {
      exValid := False
    }

    // ---- execute/memory register ----------------------------------------
    memValid        := exValid && !trapping && !halting
    mem.pc          := ex.pc
    mem.ctrl.regWrite  := ex.ctrl.regWrite
    mem.ctrl.isLoad    := ex.ctrl.isLoad
    mem.ctrl.memSize   := ex.ctrl.memSize
    mem.ctrl.memSigned := ex.ctrl.memSigned
    mem.rd          := ex.rd
    mem.result      := result
    mem.addrLow     := address(1 downto 0)
    memStoreData    := srcB
    memIsStore      := ex.ctrl.isStore
  }

  // =======================================================================
  // Memory
  // =======================================================================

  val memory = new Area {
    io.dbus.enable  := memValid && (mem.ctrl.isLoad || memIsStore)
    io.dbus.write   := memValid && memIsStore
    io.dbus.address := mem.result.asUInt.resize(aw)

    val sd = memStoreData

    val wdata = Bits(XLEN bits)
    val mask  = Bits(4 bits)
    switch(mem.ctrl.memSize) {
      is(U(Isa.SIZE_B, 2 bits)) {
        wdata := sd(7 downto 0) ## sd(7 downto 0) ## sd(7 downto 0) ## sd(7 downto 0)
        mask  := (B"0001" << mem.addrLow).resize(4)
      }
      is(U(Isa.SIZE_H, 2 bits)) {
        wdata := sd(15 downto 0) ## sd(15 downto 0)
        mask  := (B"0011" << mem.addrLow).resize(4)
      }
      default {
        wdata := sd
        mask  := B"1111"
      }
    }
    io.dbus.wdata := wdata
    io.dbus.mask  := mask

    // ---- memory/writeback register ---------------------------------------
    wbValid := memValid
    wb      := mem
  }

  // =======================================================================
  // Counters and debug outputs
  // =======================================================================

  val cycleCounter  = Reg(UInt(32 bits)) init 0
  val retireCounter = Reg(UInt(32 bits)) init 0

  when(!haltedReg) { cycleCounter := cycleCounter + 1 }
  when(wbValid) { retireCounter := retireCounter + 1 }

  io.halted  := haltedReg
  io.trapped := trappedReg
  io.cause   := causeReg
  io.trapPc  := trapPcReg

  io.cycleCount  := cycleCounter
  io.retireCount := retireCounter

  io.dbgRetireValid := wbValid
  io.dbgRetirePc    := wb.pc
}
