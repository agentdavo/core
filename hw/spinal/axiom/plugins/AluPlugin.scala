package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._
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
object AluKlass {
  val ARITH  = 0 // the adder, and the W forms of it
  val LOGIC  = 1 // and, or, xor and their complemented forms
  val SHIFT  = 2 // the funnel shifter, and the W forms of it
  val FLAG   = 3 // set-if-less-than, zero or one
  val MINMAX = 4 // one operand or the other, chosen by the comparison
}

class AluPlugin extends AxiomPlugin {

  val SEL    = Payload(Bool())

  /** An arithmetic instruction that finishes in execute. */
  val SEL_ALU = Payload(Bool())
  val RESULT  = Payload(Bits(AxiomParam.XLEN bits))

  /** The function code and the second-operand select, both resolved in decode.
    *
    * Neither depends on a register value, only on the instruction, so neither
    * belongs in the stage that has to wait for the register file. Leaving them
    * in execute put a seven-way sixty-four-bit multiplexer and a five-bit
    * opcode remap in front of the ALU, in series with the function decode of
    * the result multiplexer behind it. Decode is otherwise close to idle.
    */
  val FN      = Payload(UInt(5 bits))
  val USE_IMM = Payload(Bool())

  /** The second operand, selected in the read stage.
    *
    * Selecting it in execute meant the adder's input arrived through a
    * multiplexer whose one-bit control had to reach all sixty-four lanes. On
    * an ECP5 that control net measured 1.9 ns of routing on its own and the
    * net from the multiplexer to the carry chain another 2.1: four of a twenty
    * nanosecond cycle, spent getting a decoded bit and an operand to the same
    * place.
    *
    * The read stage already has a sixty-four bit multiplexer for forwarding
    * and the immediate sitting next to it, so it selects there instead and
    * execute reads an operand straight out of a register. Register rm still
    * travels separately because the multiplier and a store pair want it
    * unmodified.
    */
  val SRC_B = Payload(Bits(AxiomParam.XLEN bits))

  /** What kind of result this instruction produces, and the handful of bits
    * each kind needs, all decided in decode.
    *
    * The result used to be a nineteen-case switch on the function code, which
    * yosys turns into a seven-level multiplexer tree. Measured on an ECP5 that
    * tree was 8.4 ns of a 17.8 ns execute stage, larger than the 64-bit adder
    * feeding it, and nine tenths of it was routing between levels rather than
    * the levels themselves.
    *
    * The function encoding was laid out in contiguous groups, so the group is
    * a fact about the instruction and belongs in decode. Execute is then one
    * five-way multiplexer over five units, and each unit resolves its own
    * variant from bits it already has.
    */
  /** A shift, which finishes a stage later than the rest of the ALU.
    *
    * The funnel shifter is seven multiplexer levels of sixty-four bits, which
    * is comparable to the 64-bit adder beside it and shares the stage with it.
    * Splitting it in half across execute and memory makes each half three or
    * four levels, and takes the deeper of the two off the stage that every
    * instruction goes through.
    *
    * Shifts pay a cycle for that, in the same way a load or a multiply does,
    * and through the same mechanism: the result is declared as arriving in
    * memory and the interlock holds a consumer that reaches for it sooner. It
    * is the right operation to spend that on, being far rarer in ordinary code
    * than an add and no more common than a load.
    */
  val SEL_SHIFT    = Payload(Bool())
  val SHIFT_RESULT = Payload(Bits(AxiomParam.XLEN bits))

  /** The funnel shift, half done: shifted by whole groups of sixteen, with the
    * remainder of the distance still to go. Seventy-nine bits is what a
    * sixty-four bit answer needs when it may still move fifteen more places.
    */
  val SHIFT_COARSE = Payload(Bits(Isa.XLEN + 15 bits))
  val SHIFT_FINE   = Payload(UInt(4 bits))

  val KLASS        = Payload(UInt(3 bits))
  val IS_WORD      = Payload(Bool())
  val SUBTRACT     = Payload(Bool())
  val CMP_UNSIGNED = Payload(Bool())

  /** Constant formation, also resolved in decode.
    *
    * MOVZ, MOVN and ADDPC read no register at all, and MOVK reads one only to
    * keep the lanes it is not writing. Forming the constant in execute put a
    * four-way opcode multiplexer on top of the function-wide result
    * multiplexer, in series, on the path every arithmetic instruction takes.
    * Doing it in decode leaves execute one select between the ALU and a
    * constant that is already built.
    */
  val SEL_CONST   = Payload(Bool())
  val CONST_VALUE = Payload(Bits(AxiomParam.XLEN bits))
  val IS_MOVK     = Payload(Bool())

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

  /** Partial products, registered between execute and memory, one per pair of
    * operand limbs.
    *
    * The limb width is the whole point. An ECP5 MULT18X18D multiplies 18 bits
    * by 18 bits in one cell. Ask for anything wider and yosys decomposes it
    * into a cascade of cells with adders between them, so a 33 by 33 product
    * is four cells deep: measured at 3.9 ns inside the DSP and another 2.6 ns
    * routing between its halves, which was the critical path of the core.
    *
    * Sixteen bits of operand plus a sign bit is seventeen, which fits one cell
    * with a bit to spare, so every product here is a single DSP and nothing
    * cascades. The same sixteen cells do the work, arranged to suit the part
    * rather than to suit the arithmetic.
    */
  val LimbBits = 16
  val Limbs = Isa.XLEN / LimbBits
  val ProductBits = 2 * (LimbBits + 1)

  val PP: Array[Array[Payload[SInt]]] = Array.tabulate(Limbs, Limbs) { (i, j) =>
    Payload(SInt(ProductBits bits)).setName(s"PP_${i}_$j")
  }

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
    host[RegFileService].addResult(SEL_SHIFT, SHIFT_RESULT, availableAt = Stages.MEMORY)
    if (AxiomParam.WITH_MULTIPLIER.get) {
      host[RegFileService].addResult(SEL_MUL, MUL_RESULT, availableAt = Stages.WRITEBACK)
    }
  }

  val logic = during build new Area {
    val xlen = AxiomParam.XLEN.get
    val half = xlen / 2

    // ---- decode: everything that depends only on the instruction --------
    //
    // Split the claim here too, early enough for the register file interlock
    // to see that a multiply is a late producer.
    val classify = new Area {
      val decode = ctrl(Stages.DECODE)
      val instr = decode(Global.INSTRUCTION)
      val opcode = instr(Isa.OP_HI downto Isa.OP_LO)

      val multiply = if (AxiomParam.WITH_MULTIPLIER.get) isMultiply(instr) else False
      decode(SEL_MUL) := decode(SEL) && multiply

      val shiftSub = instr(9 downto 7).asUInt
      // SHL to ROR are contiguous at 0x08 and their W forms at 0x14, so the
      // three-bit shift sub-function maps to the five-bit ALU code by adding a
      // constant that depends only on its top bit.
      val shiftFn = Mux(shiftSub.msb, U(0x10, 5 bits) + shiftSub.resize(5), U(0x08, 5 bits) + shiftSub.resize(5))

      // The register form feeds its sub-function straight through, which is
      // what the opcode map was laid out for.
      val fn = UInt(5 bits)
      val useImm = False
      fn := instr(10 downto 6).asUInt
      switch(opcode) {
        // The shift amount rides in the shared immediate, so the second
        // operand is one select here rather than two.
        is(B(Isa.ALU_SHIFT, 6 bits)) { fn := shiftFn;     useImm := True }
        is(B(Isa.ADDI, 6 bits))      { fn := Isa.Fn.ADD;  useImm := True }
        is(B(Isa.ANDI, 6 bits))      { fn := Isa.Fn.AND;  useImm := True }
        is(B(Isa.ORI, 6 bits))       { fn := Isa.Fn.OR;   useImm := True }
        is(B(Isa.XORI, 6 bits))      { fn := Isa.Fn.XOR;  useImm := True }
        is(B(Isa.SLTI, 6 bits))      { fn := Isa.Fn.SLT;  useImm := True }
        is(B(Isa.SLTUI, 6 bits))     { fn := Isa.Fn.SLTU; useImm := True }
      }
      decode(FN) := fn
      decode(USE_IMM) := useImm

      def fnIs(values: Int*): Bool = values.map(v => fn === v).reduce(_ || _)

      val klass = UInt(3 bits)
      klass := AluKlass.ARITH
      when(fnIs(Isa.Fn.AND, Isa.Fn.OR, Isa.Fn.XOR, Isa.Fn.ANDN, Isa.Fn.ORN, Isa.Fn.XNOR)) {
        klass := AluKlass.LOGIC
      }
      when(fnIs(Isa.Fn.SHL, Isa.Fn.SHR, Isa.Fn.SAR, Isa.Fn.ROR,
                Isa.Fn.SHLW, Isa.Fn.SHRW, Isa.Fn.SARW, Isa.Fn.RORW)) {
        klass := AluKlass.SHIFT
      }
      when(fnIs(Isa.Fn.SLT, Isa.Fn.SLTU)) { klass := AluKlass.FLAG }
      when(fnIs(Isa.Fn.MIN, Isa.Fn.MAX, Isa.Fn.MINU, Isa.Fn.MAXU)) { klass := AluKlass.MINMAX }
      decode(KLASS) := klass

      // Constant formation borrows the same instruction bits the function code
      // lives in, so a MOVZ whose immediate happens to look like a shift must
      // not be routed to the shifter. The three claims are exclusive.
      val isConst = opcode === B(Isa.MOVZ, 6 bits) || opcode === B(Isa.MOVN, 6 bits) ||
        opcode === B(Isa.MOVK, 6 bits) || opcode === B(Isa.ADDPC, 6 bits)
      val shift = !isConst && klass === AluKlass.SHIFT
      decode(SEL_SHIFT) := decode(SEL) && !multiply && shift
      decode(SEL_ALU) := decode(SEL) && !multiply && !shift

      decode(IS_WORD) := fnIs(Isa.Fn.ADDW, Isa.Fn.SUBW,
        Isa.Fn.SHLW, Isa.Fn.SHRW, Isa.Fn.SARW, Isa.Fn.RORW)

      // One adder serves subtraction and both comparisons, so anything that
      // needs a - b asks for it here.
      decode(SUBTRACT) := fnIs(Isa.Fn.SUB, Isa.Fn.SUBW, Isa.Fn.SLT, Isa.Fn.SLTU,
        Isa.Fn.MIN, Isa.Fn.MAX, Isa.Fn.MINU, Isa.Fn.MAXU)

      // SLT and SLTU differ in bit 0; MIN, MAX, MINU and MAXU in bit 1.
      decode(CMP_UNSIGNED) := Mux(klass === AluKlass.FLAG, fn(0), fn(1))

      // The lane, times sixteen. MOVK keeps the mask out of the payloads: the
      // lane index is two bits of the instruction, which execute already has,
      // so rebuilding the mask there is one level of logic and saves carrying
      // sixty-four bits through two stages.
      val movAmount = (instr(17 downto 16) ## B(0, 4 bits)).asUInt
      val movLane = (B(0, (xlen - 16) bits) ## instr(15 downto 0)).asUInt
      val movShifted = (movLane |<< movAmount).asBits

      val isMovk = opcode === B(Isa.MOVK, 6 bits)
      decode(IS_MOVK) := isMovk
      decode(SEL_CONST) := isConst

      val constValue = Bits(xlen bits)
      constValue := movShifted
      when(opcode === B(Isa.MOVN, 6 bits)) { constValue := ~movShifted }
      when(opcode === B(Isa.ADDPC, 6 bits)) {
        constValue := (decode(Global.PC) + decode(Global.IMM).asUInt).asBits
      }
      decode(CONST_VALUE) := constValue
    }

    // ---- read: finish the second operand --------------------------------
    val operands = new Area {
      val read = ctrl(Stages.READ)
      // down, not the bare node: the register file drives RS_M onto this
      // node's downstream side in the same stage, and the bare form would read
      // the upstream value from before the read happened.
      read.down(SRC_B) := Mux(read.down(USE_IMM), read.down(Global.IMM), read.down(Global.RS_M))
    }

    val node = ctrl(Stages.EXECUTE)

    val instr  = node(Global.INSTRUCTION)
    val opcode = instr(Isa.OP_HI downto Isa.OP_LO)
    val imm    = node(Global.IMM)

    def opIs(value: Int): Bool = opcode === B(value, 6 bits)

    val fn = node(FN)
    val srcB = node(SRC_B)

    // ---- the datapath ---------------------------------------------------
    val a = node(Global.RS_N)
    val b = srcB

    def sext32(value: Bits): Bits = value(31 downto 0).asSInt.resize(xlen).asBits

    /** One adder for addition, subtraction and both comparisons.
      *
      * Written as three separate expressions, add, subtract and two
      * comparisons became four independent carry chains: this plugin held 450
      * CCU2C, seven times what a single 64-bit adder needs. Sharing one chain
      * is smaller, and it takes three inputs off the result multiplexer.
      *
      * The comparison falls out of the subtraction. Unsigned, a is below b
      * exactly when the subtraction borrows. Signed, operands of different
      * signs are ordered by a's sign alone, and operands of the same sign
      * cannot overflow, so the difference's sign is the answer.
      */
    val adder = new Area {
      val subtract = node(SUBTRACT)
      val operand = b ^ B(xlen bits, default -> subtract)
      val sum = (a.asUInt +^ operand.asUInt) + subtract.asUInt
      val difference = sum(xlen - 1 downto 0).asBits

      val borrow = !sum.msb
      val lessUnsigned = borrow
      val lessSigned = Mux(a.msb =/= b.msb, a.msb, difference.msb)
      val less = Mux(node(CMP_UNSIGNED), lessUnsigned, lessSigned)
    }

    /** Every logic function, as one multiplexer rather than six operations
      * and a tree. The codes are contiguous from AND at 0x02, so the low three
      * bits of the function select directly.
      */
    val logicUnit = new Area {
      val result = Bits(xlen bits)
      result := a & b
      switch(fn(2 downto 0)) {
        is(Isa.Fn.AND & 7)  { result := a & b }
        is(Isa.Fn.OR & 7)   { result := a | b }
        is(Isa.Fn.XOR & 7)  { result := a ^ b }
        is(Isa.Fn.ANDN & 7) { result := a & ~b }
        is(Isa.Fn.ORN & 7)  { result := a | ~b }
        is(Isa.Fn.XNOR & 7) { result := ~(a ^ b) }
      }
    }

    /** One funnel shifter for all eight shift and rotate functions, cut in
      * half across execute and memory.
      *
      * The construction is `{hi, lo} >> amount`, keeping the low half.
      * Choosing what goes in `hi` and `lo` turns that single right shift into
      * a left shift, an arithmetic shift or a rotate, and placing a 32-bit
      * operand in the correct half of `lo` covers the W forms too. Eight
      * separate shifters became one, which is worth roughly a quarter of this
      * plugin's area.
      *
      * A 128-bit shift by a seven-bit distance is seven multiplexer levels,
      * which is as deep as the adder it shares the stage with. Execute does
      * the top three bits of the distance, moving whole groups of sixteen, and
      * memory does the remaining four. Between them sits a value that has
      * settled to within fifteen places of its answer, so seventy-nine bits
      * carry it rather than a hundred and twenty-eight.
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

      // Three levels here: the distance is a multiple of sixteen, so this is
      // three multiplexers of sixteen, thirty-two and sixty-four places.
      val coarse = ((hi ## lo).asUInt >> (amount(6 downto 4) @@ U"0000"))
      node(SHIFT_COARSE) := coarse(AxiomParam.XLEN.get + 14 downto 0).asBits
      node(SHIFT_FINE) := amount(3 downto 0)
    }

    /** The other four levels, and the W narrowing that goes with them. */
    val shiftComplete = new Area {
      val memory = ctrl(Stages.MEMORY)
      val fine = (memory(SHIFT_COARSE).asUInt >> memory(SHIFT_FINE))(xlen - 1 downto 0).asBits
      memory(SHIFT_RESULT) := Mux(memory(IS_WORD), fine(31 downto 0).asSInt.resize(xlen).asBits, fine)
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

      /** One limb, as a seventeen-bit signed value.
        *
        * Only the top limb carries a sign, and only when the function is the
        * signed high multiply, so one multiplexer covers all four multiply
        * functions exactly as a single wide multiplier did. The lower limbs
        * are non-negative by construction, which is what makes the sum of the
        * products correct for both signednesses.
        */
      def limb(value: Bits, i: Int): SInt = {
        val top = i == Limbs - 1
        val sign = if (top) signedOp && value(xlen - 1) else False
        (sign ## value(LimbBits * i + LimbBits - 1 downto LimbBits * i)).asSInt
      }

      val limbsA = Array.tabulate(Limbs)(limb(mulA, _))
      val limbsB = Array.tabulate(Limbs)(limb(mulB, _))

      for (i <- 0 until Limbs; j <- 0 until Limbs) node(PP(i)(j)) := limbsA(i) * limbsB(j)
    }

    /** Five units, one multiplexer, and the W narrowing applied once.
      *
      * Every W form is its full-width form with the low half sign extended, so
      * that extension is a single select on the way out rather than a case of
      * its own for each. MIN and MAX pick an operand rather than computing
      * one: bit 0 of the function says which way round.
      */
    val minmax = Mux(adder.less ^ fn(0), a, b)

    // No shift input: it finishes in memory and arrives through its own
    // result, so the stage every instruction goes through does not carry it.
    val wide = Bits(xlen bits)
    wide := adder.difference
    switch(node(KLASS)) {
      is(AluKlass.LOGIC)  { wide := logicUnit.result }
      is(AluKlass.FLAG)   { wide := adder.less.asBits.resize(xlen) }
      is(AluKlass.MINMAX) { wide := minmax }
    }

    val aluResult = Mux(node(IS_WORD), sext32(wide), wide)

    // ---- constant formation, finished ------------------------------------
    //
    // MOVK is the only instruction that reads and writes rd, which is what
    // lets a 64-bit constant be built one lane at a time. It is also the only
    // part of constant formation that needs a register, so it is the only part
    // left here: the lane it writes is already in place in CONST_VALUE, and
    // what remains is to keep the three lanes it does not.
    val movMask = ((B(0, (xlen - 16) bits) ## B(0xffff, 16 bits)).asUInt |<<
      (instr(17 downto 16) ## B(0, 4 bits)).asUInt).asBits

    val constant = Bits(xlen bits)
    constant := node(CONST_VALUE)
    when(node(IS_MOVK)) { constant := (node(Global.RS_D) & ~movMask) | node(CONST_VALUE) }

    val out = Mux(node(SEL_CONST), constant, aluResult)

    node(RESULT) := out

    /** Sum the partial products in the memory stage.
      *
      * Every product was registered on the way in, so this side of the
      * multiply is an adder tree and nothing else, and the result is
      * registered again on the way to writeback. A product of limbs i and j
      * carries weight 16 times i plus j; sign extending each to the full
      * double width before shifting makes the sum correct in two's complement
      * for both signednesses, and the terms that fall off the top cancel
      * modulo 2^128 exactly as they should.
      */
    val multiplyComplete = AxiomParam.WITH_MULTIPLIER.get generate new Area {
      val memory = ctrl(Stages.MEMORY)
      val wide = 2 * xlen

      val terms = for (i <- 0 until Limbs; j <- 0 until Limbs)
        yield memory(PP(i)(j)).resize(wide) |<< (LimbBits * (i + j))
      val product = terms.toSeq.reduceBalancedTree(_ + _)

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
