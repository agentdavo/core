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

  /** An arithmetic instruction that finishes in execute. */
  val SEL_ALU = Payload(Bool())
  val RESULT  = Payload(Bits(AxiomParam.XLEN bits))

  /** A multiply, which does not.
    *
    * A 64 by 64 multiply is sixteen DSP blocks and a four-level adder tree, and
    * measured at 23.8 ns of a 45.5 ns critical path: half the cycle, for one
    * instruction. Splitting it into partial products in execute and the sum in
    * memory halves that, at the cost of the result arriving a cycle later.
    *
    * Arriving later costs nothing new, because the register file already has an
    * interlock for exactly this shape: a producer whose value is not ready when
    * execute wants it. A multiply now behaves like a load.
    */
  val SEL_MUL    = Payload(Bool())
  val MUL_RESULT = Payload(Bits(AxiomParam.XLEN bits))

  /** The four partial products, registered between execute and memory. */
  val PP_HH = Payload(SInt(2 * (Isa.XLEN / 2 + 1) bits))
  val PP_HL = Payload(SInt(2 * (Isa.XLEN / 2 + 1) bits))
  val PP_LH = Payload(SInt(2 * (Isa.XLEN / 2 + 1) bits))
  val PP_LL = Payload(SInt(2 * (Isa.XLEN / 2 + 1) bits))

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

  /** The multiply functions, which are sub-functions of the register ALU. */
  private def isMultiply(instr: Bits): Bool = {
    val isAluR = instr(Isa.OP_HI downto Isa.OP_LO) === B(Isa.ALU_R, 6 bits)
    val fn = instr(10 downto 6).asUInt
    val multiplies = Seq(Isa.Fn.MUL, Isa.Fn.MULH, Isa.Fn.MULHU, Isa.Fn.MULW)
    isAluR && multiplies.map(value => fn === value).reduce(_ || _)
  }

  val setupLogic = during setup new Area {
    host[DecoderService].claim(SEL, opcodes, subFunctionLegal)
    host[RegFileService].addResult(SEL_ALU, RESULT)
    if (AxiomParam.WITH_MULTIPLIER.get) {
      host[RegFileService].addResult(SEL_MUL, MUL_RESULT, availableAt = Stages.WRITEBACK)
    }
  }

  val logic = during build new Area {
    val xlen = AxiomParam.XLEN.get
    val half = xlen / 2

    // Split the claim at decode, early enough for the register file interlock
    // to see that a multiply is a late producer.
    val classify = new Area {
      val decode = ctrl(Stages.DECODE)
      val multiply = if (AxiomParam.WITH_MULTIPLIER.get) isMultiply(decode(Global.INSTRUCTION)) else False
      decode(SEL_MUL) := decode(SEL) && multiply
      decode(SEL_ALU) := decode(SEL) && !multiply
    }

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

    def sext32(value: Bits): Bits = value(31 downto 0).asSInt.resize(xlen).asBits
    def flag(condition: Bool): Bits = condition.asBits.resize(xlen)

    val compare = new Area {
      val lessSigned = a.asSInt < b.asSInt
      val lessUnsigned = a.asUInt < b.asUInt
    }

    /** One funnel shifter for all eight shift and rotate functions.
      *
      * The construction is `{hi, lo} >> amount`, keeping the low half.
      * Choosing what goes in `hi` and `lo` turns that single right shift into
      * a left shift, an arithmetic shift or a rotate, and placing a 32-bit
      * operand in the correct half of `lo` covers the W forms too. Eight
      * separate shifters became one, which is worth roughly a quarter of this
      * plugin's area and removes seven inputs from the result multiplexer.
      */
    val shifter = new Area {
      val wide = b(5 downto 0).asUInt
      val narrow = b(4 downto 0).asUInt
      val low32 = a(31 downto 0)
      val signFill = B(xlen bits, default -> a.msb)
      val extended32 = low32.asSInt.resize(xlen).asBits

      val hi = Bits(xlen bits)
      val lo = Bits(xlen bits)
      val amount = UInt(7 bits)

      hi := B(0, xlen bits)
      lo := a
      amount := wide.resized
      switch(fn) {
        is(Isa.Fn.SHL)  { hi := a; lo := B(0, xlen bits); amount := U(xlen, 7 bits) - wide.resized }
        is(Isa.Fn.SAR)  { hi := signFill }
        is(Isa.Fn.ROR)  { hi := a }
        is(Isa.Fn.SHLW) { lo := low32 ## B(0, 32 bits); amount := U(32, 7 bits) - narrow.resized }
        is(Isa.Fn.SHRW) { lo := B(0, 32 bits) ## low32; amount := narrow.resized }
        is(Isa.Fn.SARW) { lo := extended32; amount := narrow.resized }
        is(Isa.Fn.RORW) { lo := low32 ## low32; amount := narrow.resized }
      }

      val result = ((hi ## lo).asUInt >> amount)(xlen - 1 downto 0).asBits
    }

    /** Partial products, computed in execute.
      *
      * A 64-bit multiply is written as four 33 by 33 products of the operand
      * halves. Only the high halves carry a sign, and only when the function is
      * the signed high multiply, so one sign-extension mux covers all four
      * multiply functions exactly as a single wide multiplier did.
      *
      * The operands come straight from the pipeline registers rather than from
      * `a` and `srcB`, and the sign control straight from the instruction
      * rather than from `fn`. Every multiply is a three-register form, so the
      * second-operand multiplexer and the function remapping both resolve to
      * the identity here; going through them anyway put an opcode-wide
      * multiplexer in front of the multiplier array, and that multiplexer
      * measured as the critical path of the whole core.
      */
    val multiplyIssue = AxiomParam.WITH_MULTIPLIER.get generate new Area {
      val mulA = node(Global.RS_N)
      val mulB = node(Global.RS_M)
      val signedOp = instr(10 downto 6).asUInt === Isa.Fn.MULH

      def high(value: Bits) = ((signedOp && value(xlen - 1)) ## value(xlen - 1 downto half)).asSInt
      def low(value: Bits) = (False ## value(half - 1 downto 0)).asSInt

      node(PP_HH) := high(mulA) * high(mulB)
      node(PP_HL) := high(mulA) * low(mulB)
      node(PP_LH) := low(mulA) * high(mulB)
      node(PP_LL) := low(mulA) * low(mulB)
    }

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
      is(Isa.Fn.SHL, Isa.Fn.SHR, Isa.Fn.SAR, Isa.Fn.ROR) { aluResult := shifter.result }
      is(Isa.Fn.SHLW, Isa.Fn.SHRW, Isa.Fn.SARW, Isa.Fn.RORW) { aluResult := sext32(shifter.result) }
      is(Isa.Fn.SLT)   { aluResult := flag(compare.lessSigned) }
      is(Isa.Fn.SLTU)  { aluResult := flag(compare.lessUnsigned) }
      is(Isa.Fn.ADDW)  { aluResult := sext32((a.asUInt + b.asUInt).asBits) }
      is(Isa.Fn.SUBW)  { aluResult := sext32((a.asUInt - b.asUInt).asBits) }
      is(Isa.Fn.MIN)   { aluResult := Mux(compare.lessSigned, a, b) }
      is(Isa.Fn.MAX)   { aluResult := Mux(compare.lessSigned, b, a) }
      is(Isa.Fn.MINU)  { aluResult := Mux(compare.lessUnsigned, a, b) }
      is(Isa.Fn.MAXU)  { aluResult := Mux(compare.lessUnsigned, b, a) }
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

    /** Sum the partial products in the memory stage.
      *
      * The four products were registered on the way in, so this side of the
      * multiply is an adder tree and nothing else, and the result is registered
      * again on the way to writeback.
      */
    val multiplyComplete = AxiomParam.WITH_MULTIPLIER.get generate new Area {
      val memory = ctrl(Stages.MEMORY)
      val wide = 2 * xlen

      val product = memory(PP_LL).resize(wide) +
        (memory(PP_HL).resize(wide) << half) +
        (memory(PP_LH).resize(wide) << half) +
        (memory(PP_HH).resize(wide) << xlen)

      val instr = memory(Global.INSTRUCTION)
      val fn = instr(10 downto 6).asUInt
      val low = product(xlen - 1 downto 0).asBits
      val high = product(wide - 1 downto xlen).asBits

      val value = Bits(xlen bits)
      value := low
      when(fn === Isa.Fn.MULH || fn === Isa.Fn.MULHU) { value := high }
      when(fn === Isa.Fn.MULW) { value := low(31 downto 0).asSInt.resize(xlen).asBits }
      memory(MUL_RESULT) := value
    }
  }
}
