package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib.misc.pipeline._

/** The integer ALU, the immediate forms, and constant formation.
  *
  * These live in one plugin because they share the one adder and the one
  * shifter. The opcode map was laid out so this plugin needs almost no decode
  * of its own: for a register-register instruction the ALU function code is
  * already `instr(10 downto 6)` and is fed straight in.
  *
  * The three multiply results share a single 65 by 65 signed multiplier. Only
  * the signed high half needs signed operands, the unsigned high half needs
  * unsigned ones, and every low half is the same either way, so one
  * sign-extension mux covers all four multiply functions.
  */
class AluPlugin extends AxiomPlugin {

  val SEL    = Payload(Bool())
  val RESULT = Payload(Bits(AxiomParam.XLEN bits))

  private val opcodes = Seq(Isa.ALU_R, Isa.ALU_SHIFT, Isa.ADDI, Isa.ANDI, Isa.ORI,
    Isa.XORI, Isa.SLTI, Isa.SLTUI, Isa.MOVZ, Isa.MOVN, Isa.MOVK, Isa.ADDPC)

  /** Only the ALU register form has a sub-function field to police. A build
    * without a multiplier rejects the multiply functions here, which is how
    * they come to raise the same illegal instruction trap as a genuinely
    * undefined encoding.
    */
  private def subFunctionLegal(instr: Bits): Bool = {
    val isAluR = instr(Isa.OP_HI downto Isa.OP_LO) === B(Isa.ALU_R, 6 bits)
    val fn = instr(10 downto 6).asUInt
    val multiplies = Set(Isa.Fn.MUL, Isa.Fn.MULH, Isa.Fn.MULHU, Isa.Fn.MULW)
    val allowed = if (AxiomParam.WITH_MULTIPLIER.get) Isa.Fn.ALL else Isa.Fn.ALL -- multiplies
    !isAluR || allowed.map(v => fn === v).reduce(_ || _)
  }

  val setupLogic = during setup new Area {
    host[DecoderService].claim(SEL, opcodes, subFunctionLegal)
    host[RegFileService].addResult(SEL, RESULT)
  }

  val logic = during build new Area {
    val xlen = AxiomParam.XLEN.get
    val node = ctrl(Stages.EXECUTE)

    val instr  = node(Global.INSTRUCTION)
    val opcode = instr(Isa.OP_HI downto Isa.OP_LO)
    val imm    = node(Global.IMM)

    def opIs(value: Int): Bool = opcode === B(value, 6 bits)

    // ---- pick the function and the second operand ----------------------
    val shiftSub = instr(9 downto 7).asUInt
    // SHL to ROR are contiguous at 0x08 and their W forms at 0x14, so the
    // three-bit shift sub-function maps to the five-bit ALU code by adding a
    // constant that depends only on its top bit.
    val shiftFn = Mux(shiftSub.msb, U(0x10, 5 bits) + shiftSub.resize(5), U(0x08, 5 bits) + shiftSub.resize(5))

    val fn = UInt(5 bits)
    val srcB = Bits(xlen bits)
    fn := instr(10 downto 6).asUInt
    srcB := node(Global.RS_M)
    switch(opcode) {
      is(B(Isa.ALU_SHIFT, 6 bits)) {
        fn := shiftFn
        srcB := instr(15 downto 10).resize(xlen)
      }
      is(B(Isa.ADDI, 6 bits))  { fn := Isa.Fn.ADD;  srcB := imm }
      is(B(Isa.ANDI, 6 bits))  { fn := Isa.Fn.AND;  srcB := imm }
      is(B(Isa.ORI, 6 bits))   { fn := Isa.Fn.OR;   srcB := imm }
      is(B(Isa.XORI, 6 bits))  { fn := Isa.Fn.XOR;  srcB := imm }
      is(B(Isa.SLTI, 6 bits))  { fn := Isa.Fn.SLT;  srcB := imm }
      is(B(Isa.SLTUI, 6 bits)) { fn := Isa.Fn.SLTU; srcB := imm }
    }

    // ---- the datapath ---------------------------------------------------
    val a = node(Global.RS_N)
    val b = srcB

    val shiftWide = b(5 downto 0).asUInt
    val shiftNarrow = b(4 downto 0).asUInt

    def sext32(value: Bits): Bits = value(31 downto 0).asSInt.resize(xlen).asBits
    def flag(condition: Bool): Bits = condition.asBits.resize(xlen)

    val rotated = ((a ## a).asUInt >> shiftWide)(xlen - 1 downto 0).asBits
    val rotated32 = ((a(31 downto 0) ## a(31 downto 0)).asUInt >> shiftNarrow)(31 downto 0).asBits

    val lessSigned   = a.asSInt < b.asSInt
    val lessUnsigned = a.asUInt < b.asUInt

    val product = if (AxiomParam.WITH_MULTIPLIER.get) {
      val signedOp = fn === Isa.Fn.MULH
      val aExt = ((signedOp && a.msb) ## a).asSInt
      val bExt = ((signedOp && b.msb) ## b).asSInt
      (aExt * bExt).asBits
    } else null
    def mulLow  = if (product != null) product(xlen - 1 downto 0) else B(0, xlen bits)
    def mulHigh = if (product != null) product(2 * xlen - 1 downto xlen) else B(0, xlen bits)

    val aluResult = Bits(xlen bits)
    aluResult := B(0, xlen bits)
    switch(fn) {
      is(Isa.Fn.ADD)   { aluResult := (a.asUInt + b.asUInt).asBits }
      is(Isa.Fn.SUB)   { aluResult := (a.asUInt - b.asUInt).asBits }
      is(Isa.Fn.AND)   { aluResult := a & b }
      is(Isa.Fn.OR)    { aluResult := a | b }
      is(Isa.Fn.XOR)   { aluResult := a ^ b }
      is(Isa.Fn.ANDN)  { aluResult := a & ~b }
      is(Isa.Fn.ORN)   { aluResult := a | ~b }
      is(Isa.Fn.XNOR)  { aluResult := ~(a ^ b) }
      is(Isa.Fn.SHL)   { aluResult := (a.asUInt |<< shiftWide).asBits }
      is(Isa.Fn.SHR)   { aluResult := (a.asUInt |>> shiftWide).asBits }
      is(Isa.Fn.SAR)   { aluResult := (a.asSInt |>> shiftWide).asBits }
      is(Isa.Fn.ROR)   { aluResult := rotated }
      is(Isa.Fn.SLT)   { aluResult := flag(lessSigned) }
      is(Isa.Fn.SLTU)  { aluResult := flag(lessUnsigned) }
      is(Isa.Fn.MUL)   { aluResult := mulLow }
      is(Isa.Fn.MULH)  { aluResult := mulHigh }
      is(Isa.Fn.MULHU) { aluResult := mulHigh }
      is(Isa.Fn.ADDW)  { aluResult := sext32((a.asUInt + b.asUInt).asBits) }
      is(Isa.Fn.SUBW)  { aluResult := sext32((a.asUInt - b.asUInt).asBits) }
      is(Isa.Fn.MULW)  { aluResult := sext32(mulLow) }
      is(Isa.Fn.SHLW)  { aluResult := sext32((a(31 downto 0).asUInt |<< shiftNarrow).asBits) }
      is(Isa.Fn.SHRW)  { aluResult := sext32((a(31 downto 0).asUInt |>> shiftNarrow).asBits) }
      is(Isa.Fn.SARW)  { aluResult := sext32((a(31 downto 0).asSInt |>> shiftNarrow).asBits) }
      is(Isa.Fn.RORW)  { aluResult := sext32(rotated32) }
      is(Isa.Fn.MIN)   { aluResult := Mux(lessSigned, a, b) }
      is(Isa.Fn.MAX)   { aluResult := Mux(lessSigned, b, a) }
      is(Isa.Fn.MINU)  { aluResult := Mux(lessUnsigned, a, b) }
      is(Isa.Fn.MAXU)  { aluResult := Mux(lessUnsigned, b, a) }
    }

    // ---- constant formation ---------------------------------------------
    val movAmount = (instr(17 downto 16) ## B(0, 4 bits)).asUInt // the lane, times sixteen
    val movLane = (B(0, (xlen - 16) bits) ## instr(15 downto 0)).asUInt
    val movShifted = (movLane |<< movAmount).asBits
    val movMask = ((B(0, (xlen - 16) bits) ## B(0xffff, 16 bits)).asUInt |<< movAmount).asBits

    val out = Bits(xlen bits)
    out := aluResult
    switch(opcode) {
      is(B(Isa.MOVZ, 6 bits)) { out := movShifted }
      is(B(Isa.MOVN, 6 bits)) { out := ~movShifted }
      // MOVK is the only instruction that reads and writes rd, which is what
      // lets a 64-bit constant be built one lane at a time.
      is(B(Isa.MOVK, 6 bits)) { out := (node(Global.RS_D) & ~movMask) | (movShifted & movMask) }
      is(B(Isa.ADDPC, 6 bits)) { out := (node(Global.PC) + imm.asUInt).asBits }
    }

    node(RESULT) := out
  }
}
