package core

import spinal.core._

/** Everything the back end needs to know about an instruction. */
case class CoreCtrl() extends Bundle {

  /** ALU function code, which is literally `opcode(3 downto 0)` for every
    * arithmetic instruction and is forced to ADD for address generation.
    */
  val aluFn = Bits(4 bits)

  /** Second ALU operand comes from the immediate rather than from rs2. */
  val useImm = Bool()

  val regWrite = Bool()

  val isLoad    = Bool()
  val isStore   = Bool()
  val memSize   = UInt(2 bits)
  val memSigned = Bool()

  val isBranch = Bool()

  /** `opcode(2 downto 0)` for branches: the comparison to perform. */
  val branchFn = Bits(3 bits)

  /** PC-relative jump (JMP, CALL). */
  val isJump = Bool()

  /** Register-indirect jump (JMPR, CALLR). */
  val isIndirect = Bool()

  /** Writes the return address instead of an ALU result (CALL, CALLR). */
  val link = Bool()

  val isMovi  = Bool()
  val isMovhi = Bool()
  val isAddpc = Bool()

  val isHalt  = Bool()
  val illegal = Bool()
}

/** Decoded instruction: control plus the operand addresses and constants. */
case class DecodedInstr() extends Bundle {
  val ctrl = CoreCtrl()
  val rd   = UInt(Isa.REG_ADDR_BITS bits)
  val rs1  = UInt(Isa.REG_ADDR_BITS bits)
  val rs2  = UInt(Isa.REG_ADDR_BITS bits)

  /** Which register ports this instruction really reads.
    *
    * Only decode needs these, for the load-use interlock: they keep an
    * instruction whose `ra` field is actually immediate bits, as in MOVI, from
    * causing a phantom stall. They are not pipelined past decode.
    */
  val usesRs1 = Bool()
  val usesRs2 = Bool()

  /** The instruction's constant, already in the form the execute stage wants:
    * sign-extended imm18 for the RRI instructions, sign-extended imm22 for
    * MOVI and ADDPC, and imm22 shifted into place for MOVHI.
    */
  val imm = Bits(Isa.XLEN bits)

  /** Sign-extended, word-scaled PC-relative displacement for branches and jumps. */
  val offset = UInt(Isa.XLEN bits)
}

/** Purely combinational CORE-32 instruction decoder.
  *
  * The opcode map was chosen so this stays flat: two bits pick the class, the
  * low nibble is already the ALU function, `opcode(3)` distinguishes load from
  * store, and `opcode(2 downto 0)` is already the branch condition. There is no
  * secondary function field to look up.
  */
case class Decoder(cfg: CoreConfig) extends Component {
  val io = new Bundle {
    val instr = in Bits (Isa.INSTR_BITS bits)
    val decoded = out(DecodedInstr())
  }

  private def oc(value: Int) = B(value, 6 bits)

  private val instr = io.instr
  private val op    = instr(Isa.OP_HI downto Isa.OP_LO)
  private val rdF   = instr(Isa.RD_HI downto Isa.RD_LO).asUInt
  private val raF   = instr(Isa.RA_HI downto Isa.RA_LO).asUInt
  private val rbF   = instr(Isa.RB_HI downto Isa.RB_LO).asUInt

  private val imm18 = instr(17 downto 0).asSInt.resize(Isa.XLEN).asBits
  private val imm22 = instr(21 downto 0).asSInt.resize(Isa.XLEN).asBits
  private val imm22Hi = (instr(21 downto 0) ## B(0, 10 bits)).resize(Isa.XLEN)
  private val off14 = (instr(13 downto 0) ## B"00").asSInt.resize(Isa.XLEN).asUInt
  private val off26 = (instr(25 downto 0) ## B"00").asSInt.resize(Isa.XLEN).asUInt

  private val o = io.decoded
  private val c = o.ctrl

  // Defaults. Everything below only has to state what differs.
  c.aluFn     := op(3 downto 0)
  c.useImm    := False
  c.regWrite  := False
  c.isLoad    := False
  c.isStore   := False
  c.memSize   := U(Isa.SIZE_W, 2 bits)
  c.memSigned := False
  c.isBranch  := False
  c.branchFn  := op(2 downto 0)
  c.isJump    := False
  c.isIndirect:= False
  c.link      := False
  c.isMovi    := False
  c.isMovhi   := False
  c.isAddpc   := False
  c.isHalt    := False
  c.illegal   := False

  o.usesRs1 := False
  o.usesRs2 := False

  o.rd     := rdF
  o.rs1    := raF
  o.rs2    := rbF
  o.imm    := imm18
  o.offset := off26

  /** Three-operand register ALU. */
  private def aluReg(): Unit = {
    c.regWrite := True
    o.usesRs1  := True
    o.usesRs2  := True
  }

  /** Two-operand register/immediate ALU. */
  private def aluImm(): Unit = {
    c.regWrite := True
    o.usesRs1  := True
    c.useImm   := True
  }

  /** Address generation shared by loads, stores and indirect jumps. */
  private def addressGen(): Unit = {
    c.aluFn   := B(Isa.Fn.ADD, 4 bits)
    c.useImm  := True
    o.usesRs1 := True
  }

  private def load(size: Int, signed: Boolean): Unit = {
    addressGen()
    c.isLoad    := True
    c.regWrite  := True
    c.memSize   := U(size, 2 bits)
    c.memSigned := Bool(signed)
  }

  private def store(size: Int): Unit = {
    addressGen()
    c.isStore := True
    o.usesRs2 := True
    o.rs2     := rdF // stores take their data from the rd field
    c.memSize := U(size, 2 bits)
  }

  switch(op) {
    // ---- class 0: register ALU ----------------------------------------
    is(oc(Isa.ADD), oc(Isa.SUB), oc(Isa.AND), oc(Isa.OR), oc(Isa.XOR),
       oc(Isa.SHL), oc(Isa.SHR), oc(Isa.SAR), oc(Isa.SLT), oc(Isa.SLTU),
       oc(Isa.SEQ), oc(Isa.SNE), oc(Isa.ROR)) {
      aluReg()
    }
    is(oc(Isa.MUL), oc(Isa.MULH), oc(Isa.MULHU)) {
      if (cfg.hasMultiplier) aluReg() else c.illegal := True
    }

    // ---- class 1: immediate ALU and constant formation -----------------
    is(oc(Isa.ADDI), oc(Isa.ANDI), oc(Isa.ORI), oc(Isa.XORI), oc(Isa.SHLI),
       oc(Isa.SHRI), oc(Isa.SARI), oc(Isa.SLTI), oc(Isa.SLTUI),
       oc(Isa.SEQI), oc(Isa.SNEI), oc(Isa.RORI)) {
      aluImm()
    }
    is(oc(Isa.MOVI)) {
      c.regWrite := True
      c.isMovi   := True
      o.imm      := imm22
    }
    is(oc(Isa.MOVHI)) {
      // The only instruction that reads and writes rd: the low ten bits of the
      // destination survive so that MOVI followed by MOVHI builds any constant.
      c.regWrite := True
      c.isMovhi  := True
      o.usesRs2  := True
      o.rs2      := rdF
      o.imm      := imm22Hi
    }
    is(oc(Isa.ADDPC)) {
      c.regWrite := True
      c.isAddpc  := True
      o.imm      := imm22
    }

    // ---- class 2: memory and system ------------------------------------
    is(oc(Isa.LDW))  { load(Isa.SIZE_W, signed = false) }
    is(oc(Isa.LDH))  { load(Isa.SIZE_H, signed = true) }
    is(oc(Isa.LDHU)) { load(Isa.SIZE_H, signed = false) }
    is(oc(Isa.LDB))  { load(Isa.SIZE_B, signed = true) }
    is(oc(Isa.LDBU)) { load(Isa.SIZE_B, signed = false) }
    is(oc(Isa.STW))  { store(Isa.SIZE_W) }
    is(oc(Isa.STH))  { store(Isa.SIZE_H) }
    is(oc(Isa.STB))  { store(Isa.SIZE_B) }
    is(oc(Isa.HALT)) { c.isHalt := True }

    // ---- class 3: control transfer --------------------------------------
    is(oc(Isa.BEQ), oc(Isa.BNE), oc(Isa.BLT),
       oc(Isa.BGE), oc(Isa.BLTU), oc(Isa.BGEU)) {
      c.isBranch := True
      o.usesRs1  := True
      o.usesRs2  := True
      o.offset   := off14
    }
    is(oc(Isa.JMP)) {
      c.isJump := True
    }
    is(oc(Isa.CALL)) {
      c.isJump   := True
      c.link     := True
      c.regWrite := True
      o.rd       := U(Isa.LR, Isa.REG_ADDR_BITS bits)
    }
    is(oc(Isa.JMPR)) {
      addressGen()
      c.isIndirect := True
    }
    is(oc(Isa.CALLR)) {
      addressGen()
      c.isIndirect := True
      c.link       := True
      c.regWrite   := True
    }

    default {
      c.illegal := True
    }
  }
}
