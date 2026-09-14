package axiom

/** Encoding tables for the Axiom-64 instruction set, Base profile.
  *
  * Free of any SpinalHDL dependency, so the RTL decoder, the assembler and the
  * reference model all read their opcode numbers from one place.
  *
  * Design decisions this encoding records, all of them settled in the design
  * discussion rather than inherited:
  *
  *  - 32 general registers, five-bit fields. Sixty-four would cost three more
  *    bits on a three-operand instruction and make the pair forms impossible.
  *  - Predicates exist but predication does not. P0 to P7 are written only by
  *    compare instructions and read only by select and branch. No predicate
  *    field rides on general arithmetic, which is what keeps the offsets wide.
  *  - Constants come from a 16-bit move with a two-bit shift, so any 64-bit
  *    constant takes at most four instructions and no prefix instruction is
  *    needed. Nothing here is wider than 32 bits.
  *  - Ordered memory accesses have their own encoding with no displacement,
  *    rather than spending two bits on every load and store in the program.
  *
  * Field positions never move: the opcode is always instr(31 downto 26), rd is
  * always instr(25 downto 21), rn is always instr(20 downto 16), and rm is
  * always instr(15 downto 11).
  */
object Isa {

  // =====================================================================
  // Geometry
  // =====================================================================

  val XLEN          = 64
  val INSTR_BITS    = 32
  val REG_COUNT     = 32
  val REG_ADDR_BITS = 5
  val PRED_COUNT    = 8
  val PRED_ADDR_BITS = 3

  val OP_HI = 31; val OP_LO = 26
  val RD_HI = 25; val RD_LO = 21
  val RN_HI = 20; val RN_LO = 16
  val RM_HI = 15; val RM_LO = 11

  // =====================================================================
  // Primary opcodes
  // =====================================================================

  // -- arithmetic and constants ----------------------------------------
  val ALU_R     = 0x00 // rd = rn op rm, operation in fn(10 downto 6)
  val ALU_SHIFT = 0x01 // rd = rn shifted by an immediate
  val ADDI      = 0x02
  val ANDI      = 0x03
  val ORI       = 0x04
  val XORI      = 0x05
  val SLTI      = 0x06
  val SLTUI     = 0x07
  val MOVZ      = 0x08 // rd = imm16 << (16 * s)
  val MOVN      = 0x09 // rd = ~(imm16 << (16 * s))
  val MOVK      = 0x0a // rd(16*s + 15 downto 16*s) = imm16, rest preserved
  val ADDPC     = 0x0b // rd = pc + sext(imm21)
  val CMP_R     = 0x0c // pd = rn cc rm
  val CMP_I     = 0x0d // pd = rn cc sext(imm12)
  val SEL       = 0x0e // rd = p ? rn : rm
  val ALU_DIV   = 0x0f // rd = rn divided by rm, form in instr(8 downto 6)

  // -- memory -----------------------------------------------------------
  val LDB  = 0x10; val LDBU = 0x11
  val LDH  = 0x12; val LDHU = 0x13
  val LDW  = 0x14; val LDWU = 0x15
  val LDD  = 0x16
  val STB  = 0x18; val STH  = 0x19
  val STW  = 0x1a; val STD  = 0x1b
  val LDP  = 0x1c; val STP  = 0x1d

  // -- control ----------------------------------------------------------
  val B    = 0x20 // pc += sext(off26) * 4
  val BL   = 0x21 // lr = pc + 4, then branch
  val BP   = 0x22 // branch if predicate matches sense
  val JALR = 0x23 // rd = pc + 4, pc = (rn + sext(imm16)) & ~3

  // -- ordered memory, atomics, system ----------------------------------
  val LD_ORD = 0x30 // acquire or sequentially consistent load, no displacement
  val ST_ORD = 0x31 // release or sequentially consistent store
  val ATOMIC = 0x32 // far-atomic read-modify-write
  val FENCE  = 0x33
  val SYSTEM = 0x34

  // =====================================================================
  // ALU function codes, in instr(10 downto 6) of ALU_R
  // =====================================================================

  object Fn {
    val ADD = 0x00; val SUB = 0x01; val AND = 0x02; val OR   = 0x03
    val XOR = 0x04; val ANDN= 0x05; val ORN = 0x06; val XNOR = 0x07
    val SHL = 0x08; val SHR = 0x09; val SAR = 0x0a; val ROR  = 0x0b
    val SLT = 0x0c; val SLTU= 0x0d; val MUL = 0x0e; val MULH = 0x0f
    val MULHU = 0x10
    // Thirty-two bit forms, sign extended into the full register. A 64-bit
    // machine without these makes every C `int` expression cost a pair of
    // shifts, which is the mistake RV64 avoided with its W suffix.
    val ADDW = 0x11; val SUBW = 0x12; val MULW = 0x13
    val SHLW = 0x14; val SHRW = 0x15; val SARW = 0x16; val RORW = 0x17
    val MIN  = 0x18; val MAX  = 0x19; val MINU = 0x1a; val MAXU = 0x1b

    val WIDTH = 5

    val ALL: Set[Int] = Set(ADD, SUB, AND, OR, XOR, ANDN, ORN, XNOR,
      SHL, SHR, SAR, ROR, SLT, SLTU, MUL, MULH, MULHU,
      ADDW, SUBW, MULW, SHLW, SHRW, SARW, RORW, MIN, MAX, MINU, MAXU)

    val NAMES: Map[Int, String] = Map(
      ADD -> "add", SUB -> "sub", AND -> "and", OR -> "or", XOR -> "xor",
      ANDN -> "andn", ORN -> "orn", XNOR -> "xnor", SHL -> "shl", SHR -> "shr",
      SAR -> "sar", ROR -> "ror", SLT -> "slt", SLTU -> "sltu", MUL -> "mul",
      MULH -> "mulh", MULHU -> "mulhu", ADDW -> "addw", SUBW -> "subw",
      MULW -> "mulw", SHLW -> "shlw", SHRW -> "shrw", SARW -> "sarw",
      RORW -> "rorw", MIN -> "min", MAX -> "max", MINU -> "minu", MAXU -> "maxu")
  }

  /** Divide and remainder, in instr(8 downto 6) of ALU_DIV.
    *
    * Divide does not share the ALU register form's sub-function field. Eight
    * forms would not fit in the four codes left there, and dropping the
    * 32-bit forms to make them fit would put every C `int` division behind a
    * pair of shifts, which is the cost the W forms exist to avoid everywhere
    * else.
    *
    * The encoding is regular in the way the rest of the map is: bit 0 selects
    * unsigned, bit 1 selects the remainder rather than the quotient, and bit 2
    * selects the 32-bit form.
    */
  object DivFn {
    val DIV = 0; val DIVU = 1; val REM = 2; val REMU = 3
    val DIVW = 4; val DIVUW = 5; val REMW = 6; val REMUW = 7

    val WIDTH = 3

    def unsigned(fn: Int): Boolean = (fn & 1) != 0
    def remainder(fn: Int): Boolean = (fn & 2) != 0
    def word(fn: Int): Boolean = (fn & 4) != 0

    val ALL: Set[Int] = Set(DIV, DIVU, REM, REMU, DIVW, DIVUW, REMW, REMUW)

    val NAMES: Map[Int, String] = Map(
      DIV -> "div", DIVU -> "divu", REM -> "rem", REMU -> "remu",
      DIVW -> "divw", DIVUW -> "divuw", REMW -> "remw", REMUW -> "remuw")
  }

  /** Shift-by-immediate sub-function, in instr(9 downto 7) of ALU_SHIFT. */
  object ShiftFn {
    val SHL = 0; val SHR = 1; val SAR = 2; val ROR = 3
    val SHLW = 4; val SHRW = 5; val SARW = 6; val RORW = 7
    val NAMES: Map[Int, String] = Map(SHL -> "shli", SHR -> "shri", SAR -> "sari",
      ROR -> "rori", SHLW -> "shlwi", SHRW -> "shrwi", SARW -> "sarwi", RORW -> "rorwi")
  }

  // =====================================================================
  // Condition codes, in instr(10 downto 7) of CMP_R and instr(15 downto 12)
  // of CMP_I
  // =====================================================================

  object Cc {
    val EQ = 0; val NE = 1
    val LT = 2; val GE = 3   // signed
    val LTU = 4; val GEU = 5 // unsigned
    // LE and GT are not just swapped operands once an immediate is involved,
    // so they earn their own codes.
    val LE = 6; val GT = 7
    val LEU = 8; val GTU = 9

    val WIDTH = 4
    val ALL: Set[Int] = Set(EQ, NE, LT, GE, LTU, GEU, LE, GT, LEU, GTU)
    val NAMES: Map[Int, String] = Map(EQ -> "eq", NE -> "ne", LT -> "lt",
      GE -> "ge", LTU -> "ltu", GEU -> "geu", LE -> "le", GT -> "gt",
      LEU -> "leu", GTU -> "gtu")
  }

  // =====================================================================
  // Memory
  // =====================================================================

  /** Access size, log2 of the byte count. */
  val SIZE_B = 0; val SIZE_H = 1; val SIZE_W = 2; val SIZE_D = 3

  /** Addressing mode, in instr(15 downto 14) of the single-register forms. */
  object Mode {
    val OFFSET = 0 // address = rn + imm, rn unchanged
    val PRE    = 1 // address = rn + imm, rn updated to the address
    val POST   = 2 // address = rn, rn updated to rn + imm
    val NAMES: Map[Int, String] = Map(OFFSET -> "", PRE -> "!", POST -> "post")
  }

  /** Ordering annotation carried by the ordered accesses and the atomics. */
  object Ord {
    val PLAIN = 0
    val ACQREL = 1 // acquire on a load, release on a store, RCpc
    val SEQ = 2    // sequentially consistent, RCsc
    val ACQREL_BOTH = 3 // read-modify-write that both acquires and releases
    val NAMES: Map[Int, String] = Map(PLAIN -> "", ACQREL -> ".aqrl", SEQ -> ".seq", ACQREL_BOTH -> ".aq.rl")
  }

  /** Atomic sub-function, in instr(10 downto 7) of ATOMIC. */
  object AtomicFn {
    val SWP = 0; val ADD = 1; val AND = 2; val OR = 3; val XOR = 4
    val CAS = 5; val MIN = 6; val MAX = 7; val MINU = 8; val MAXU = 9
    val ALL: Set[Int] = Set(SWP, ADD, AND, OR, XOR, CAS, MIN, MAX, MINU, MAXU)
    val NAMES: Map[Int, String] = Map(SWP -> "swp", ADD -> "ldadd", AND -> "ldand",
      OR -> "ldor", XOR -> "ldxor", CAS -> "cas", MIN -> "ldmin", MAX -> "ldmax",
      MINU -> "ldminu", MAXU -> "ldmaxu")
  }

  /** Fence kind, in instr(3 downto 0) of FENCE. */
  object FenceKind {
    val FULL = 0    // store-load included, what smp_mb and seq_cst need
    val ACQUIRE = 1
    val RELEASE = 2
    val TILE = 3    // drain the tile unit's memory traffic to visibility
    val ALL: Set[Int] = Set(FULL, ACQUIRE, RELEASE, TILE)
    val NAMES: Map[Int, String] = Map(FULL -> "fence", ACQUIRE -> "fence.aq",
      RELEASE -> "fence.rl", TILE -> "fence.tile")
  }

  /** System sub-function, in instr(4 downto 0) of SYSTEM. */
  object SystemFn {
    val HALT = 0
    val ALL: Set[Int] = Set(HALT)
    val NAMES: Map[Int, String] = Map(HALT -> "halt")
  }

  // =====================================================================
  // Trap causes
  // =====================================================================

  object Cause {
    val NONE              = 0
    val ILLEGAL           = 1
    val MISALIGNED_FETCH  = 2
    val MISALIGNED_LOAD   = 3
    val MISALIGNED_STORE  = 4
    val WIDTH             = 3
    val NAMES: Map[Int, String] = Map(NONE -> "NONE", ILLEGAL -> "ILLEGAL",
      MISALIGNED_FETCH -> "MISALIGNED_FETCH", MISALIGNED_LOAD -> "MISALIGNED_LOAD",
      MISALIGNED_STORE -> "MISALIGNED_STORE")
  }

  // =====================================================================
  // Register names
  // =====================================================================

  /** The link register written by BL. */
  val LR = 30
  /** The stack pointer, by convention only; the hardware does not know. */
  val SP = 31

  val REG_NAMES: Seq[String] =
    Seq("zero") ++ (1 to 7).map(i => s"a${i - 1}") ++ (8 to 17).map(i => s"t${i - 8}") ++
      (18 to 29).map(i => s"s${i - 18}") ++ Seq("lr", "sp")

  def regName(i: Int): String = if (i >= 0 && i < REG_COUNT) REG_NAMES(i) else s"x$i"
  def predName(i: Int): String = s"p$i"

  // =====================================================================
  // Field extraction
  // =====================================================================

  private def bits(instr: Int, hi: Int, lo: Int): Int =
    (instr >>> lo) & (if (hi - lo + 1 == 32) -1 else (1 << (hi - lo + 1)) - 1)

  private def sext(value: Int, width: Int): Int = {
    val shift = 32 - width
    (value << shift) >> shift
  }

  def opcode(instr: Int): Int = bits(instr, OP_HI, OP_LO)
  def rd(instr: Int): Int = bits(instr, RD_HI, RD_LO)
  def rn(instr: Int): Int = bits(instr, RN_HI, RN_LO)
  def rm(instr: Int): Int = bits(instr, RM_HI, RM_LO)

  def aluFn(instr: Int): Int = bits(instr, 10, 6)
  def divFn(instr: Int): Int = bits(instr, 8, 6)
  def shiftFn(instr: Int): Int = bits(instr, 9, 7)
  def shiftAmount(instr: Int): Int = bits(instr, 15, 10)

  def imm16(instr: Int): Int = bits(instr, 15, 0)
  def imm16s(instr: Int): Int = sext(imm16(instr), 16)
  def movShift(instr: Int): Int = bits(instr, 17, 16)
  def imm21(instr: Int): Int = sext(bits(instr, 20, 0), 21)

  def pd(instr: Int): Int = bits(instr, 23, 21)
  def ccR(instr: Int): Int = bits(instr, 10, 7)
  def ccI(instr: Int): Int = bits(instr, 15, 12)
  def imm12(instr: Int): Int = sext(bits(instr, 11, 0), 12)

  def selP(instr: Int): Int = bits(instr, 10, 8)
  def selInvert(instr: Int): Boolean = bits(instr, 7, 7) != 0

  def memMode(instr: Int): Int = bits(instr, 15, 14)
  def memImm(instr: Int): Int = sext(bits(instr, 13, 0), 14)

  def pairRt2(instr: Int): Int = rm(instr)
  def pairMode(instr: Int): Int = bits(instr, 10, 9)
  def pairImm(instr: Int): Int = sext(bits(instr, 8, 0), 9)

  def off26(instr: Int): Int = sext(bits(instr, 25, 0), 26) << 2
  def bpPred(instr: Int): Int = bits(instr, 25, 23)
  def bpInvert(instr: Int): Boolean = bits(instr, 22, 22) != 0
  def off22(instr: Int): Int = sext(bits(instr, 21, 0), 22) << 2

  def ordSize(instr: Int): Int = bits(instr, 15, 14)
  def ordKind(instr: Int): Int = bits(instr, 13, 12)

  def atomicRs(instr: Int): Int = rm(instr)
  def atomicFn(instr: Int): Int = bits(instr, 10, 7)
  def atomicOrd(instr: Int): Int = bits(instr, 6, 5)
  def atomicSize(instr: Int): Int = bits(instr, 4, 3)

  def fenceKind(instr: Int): Int = bits(instr, 3, 0)
  def systemFn(instr: Int): Int = bits(instr, 4, 0)

  /** Byte count of an access, from its size code. */
  def sizeBytes(size: Int): Int = 1 << size

  // =====================================================================
  // Encoders. The single source of truth for how an instruction is built.
  // =====================================================================

  private def check(cond: Boolean, msg: => String): Unit =
    if (!cond) throw new IllegalArgumentException(msg)

  private def reg(r: Int, what: String): Int = {
    check(r >= 0 && r < REG_COUNT, s"$what register $r is outside 0 to ${REG_COUNT - 1}")
    r
  }

  private def pred(p: Int, what: String): Int = {
    check(p >= 0 && p < PRED_COUNT, s"$what predicate $p is outside 0 to ${PRED_COUNT - 1}")
    p
  }

  private def signed(v: Int, width: Int, what: String): Int = {
    val lo = -(1 << (width - 1))
    val hi = (1 << (width - 1)) - 1
    check(v >= lo && v <= hi, s"$what value $v does not fit in $width signed bits, $lo to $hi")
    v & ((1 << width) - 1)
  }

  private def unsigned(v: Int, width: Int, what: String): Int = {
    check(v >= 0 && v < (1 << width), s"$what value $v does not fit in $width unsigned bits")
    v
  }

  /** A field that may be written either as a signed number or as a raw pattern. */
  private def field(v: Int, width: Int, what: String): Int = {
    val lo = -(1 << (width - 1))
    val hi = (1 << width) - 1
    check(v >= lo && v <= hi, s"$what value $v does not fit in $width bits, $lo to $hi")
    v & ((1 << width) - 1)
  }

  /** The two results division has to define, and the reasoning for them.
    *
    * **Division by zero does not trap.** The quotient is all ones and the
    * remainder is the dividend. Nothing else in this architecture traps on an
    * arithmetic result — an addition that overflows does not — and making
    * division the exception would buy a trap cause, a second way for an
    * instruction to fail late, and an argument every compiler has to have with
    * the hardware. All ones is the natural answer for the unsigned quotient,
    * being where it tends as the divisor tends to zero, and taking the same
    * bits for the signed one keeps the two forms from needing separate paths.
    *
    * **Signed overflow does not trap either.** The one case is the most
    * negative value divided by minus one, whose true quotient is one larger
    * than the format holds. The quotient is the dividend unchanged and the
    * remainder is zero, which is the two's complement answer modulo 2^64 and
    * so what every other overflowing operation here already returns.
    *
    * Both match RV64M, and the agreement is deliberate rather than
    * coincidental. A compiler back end already knows these two cases and
    * already knows not to guard them; choosing different answers would put a
    * test in front of every division to buy nothing.
    *
    * One function, used by the assembler's tests, the reference model and the
    * documentation, so that none of them can drift from the others.
    */
  def divide(fn: Int, rn: Long, rm: Long): Long = {
    val isWord = DivFn.word(fn)
    val isUnsigned = DivFn.unsigned(fn)
    val wantRemainder = DivFn.remainder(fn)

    def narrow(value: Long): Long =
      if (!isWord) value
      else if (isUnsigned) value & 0xffffffffL
      else value.toInt.toLong

    val a = narrow(rn)
    val b = narrow(rm)
    def widen(value: Long): Long = if (isWord) value.toInt.toLong else value

    if (b == 0) widen(if (wantRemainder) a else -1L)
    else if (isUnsigned) {
      widen(if (wantRemainder) java.lang.Long.remainderUnsigned(a, b)
            else java.lang.Long.divideUnsigned(a, b))
    } else {
      val mostNegative = if (isWord) Int.MinValue.toLong else Long.MinValue
      if (a == mostNegative && b == -1L) widen(if (wantRemainder) 0L else a)
      else widen(if (wantRemainder) a % b else a / b)
    }
  }

  def encAluDiv(fn: Int, d: Int, n: Int, m: Int): Int = {
    check(DivFn.ALL.contains(fn), f"no divide function 0x$fn%x")
    (ALU_DIV << OP_LO) | (reg(d, "rd") << RD_LO) | (reg(n, "rn") << RN_LO) |
      (reg(m, "rm") << RM_LO) | (fn << 6)
  }

  def encAluR(fn: Int, d: Int, n: Int, m: Int): Int = {
    check(Fn.ALL.contains(fn), f"no ALU function 0x$fn%02x")
    (ALU_R << OP_LO) | (reg(d, "rd") << RD_LO) | (reg(n, "rn") << RN_LO) |
      (reg(m, "rm") << RM_LO) | (fn << 6)
  }

  def encAluShift(fn: Int, d: Int, n: Int, shamt: Int): Int = {
    check(ShiftFn.NAMES.contains(fn), s"no shift function $fn")
    val wide = fn >= ShiftFn.SHLW
    check(shamt >= 0 && shamt < (if (wide) 32 else 64), s"shift amount $shamt out of range")
    (ALU_SHIFT << OP_LO) | (reg(d, "rd") << RD_LO) | (reg(n, "rn") << RN_LO) |
      (shamt << 10) | (fn << 7)
  }

  def encAluImm(op: Int, d: Int, n: Int, imm: Int): Int =
    (op << OP_LO) | (reg(d, "rd") << RD_LO) | (reg(n, "rn") << RN_LO) | signed(imm, 16, "imm16")

  def encMove(op: Int, d: Int, imm: Int, shift: Int): Int = {
    check(shift >= 0 && shift < 4, s"move shift $shift must be 0 to 3")
    (op << OP_LO) | (reg(d, "rd") << RD_LO) | (shift << 16) | unsigned(imm, 16, "imm16")
  }

  def encAddPc(d: Int, imm: Int): Int =
    (ADDPC << OP_LO) | (reg(d, "rd") << RD_LO) | signed(imm, 21, "imm21")

  def encCmpR(cc: Int, p: Int, n: Int, m: Int): Int = {
    check(Cc.ALL.contains(cc), s"no condition code $cc")
    (CMP_R << OP_LO) | (pred(p, "pd") << 21) | (reg(n, "rn") << RN_LO) |
      (reg(m, "rm") << RM_LO) | (cc << 7)
  }

  def encCmpI(cc: Int, p: Int, n: Int, imm: Int): Int = {
    check(Cc.ALL.contains(cc), s"no condition code $cc")
    (CMP_I << OP_LO) | (pred(p, "pd") << 21) | (reg(n, "rn") << RN_LO) |
      (cc << 12) | signed(imm, 12, "imm12")
  }

  def encSel(d: Int, n: Int, m: Int, p: Int, invert: Boolean): Int =
    (SEL << OP_LO) | (reg(d, "rd") << RD_LO) | (reg(n, "rn") << RN_LO) |
      (reg(m, "rm") << RM_LO) | (pred(p, "p") << 8) | (if (invert) 1 << 7 else 0)

  def encMem(op: Int, t: Int, n: Int, byteOffset: Int, mode: Int = Mode.OFFSET): Int = {
    val size = accessSize(op)
    val scale = sizeBytes(size)
    check(byteOffset % scale == 0, s"displacement $byteOffset is not a multiple of $scale")
    check(mode >= 0 && mode <= 2, s"addressing mode $mode is reserved")
    (op << OP_LO) | (reg(t, "rt") << RD_LO) | (reg(n, "rn") << RN_LO) |
      (mode << 14) | signed(byteOffset / scale, 14, "displacement")
  }

  def encPair(op: Int, t1: Int, t2: Int, n: Int, byteOffset: Int, mode: Int = Mode.OFFSET): Int = {
    check(byteOffset % 8 == 0, s"pair displacement $byteOffset is not a multiple of 8")
    check(mode >= 0 && mode <= 2, s"addressing mode $mode is reserved")
    (op << OP_LO) | (reg(t1, "rt1") << RD_LO) | (reg(n, "rn") << RN_LO) |
      (reg(t2, "rt2") << RM_LO) | (mode << 9) | signed(byteOffset / 8, 9, "pair displacement")
  }

  def encBranch(op: Int, byteOffset: Int): Int = {
    check((byteOffset & 3) == 0, s"branch displacement $byteOffset is not 4-byte aligned")
    (op << OP_LO) | signed(byteOffset >> 2, 26, "branch displacement")
  }

  def encBp(p: Int, invert: Boolean, byteOffset: Int): Int = {
    check((byteOffset & 3) == 0, s"branch displacement $byteOffset is not 4-byte aligned")
    (BP << OP_LO) | (pred(p, "p") << 23) | (if (invert) 1 << 22 else 0) |
      signed(byteOffset >> 2, 22, "branch displacement")
  }

  def encJalr(d: Int, n: Int, imm: Int): Int =
    (JALR << OP_LO) | (reg(d, "rd") << RD_LO) | (reg(n, "rn") << RN_LO) | signed(imm, 16, "imm16")

  def encOrdered(op: Int, t: Int, n: Int, size: Int, ord: Int): Int = {
    check(size >= 0 && size <= 3, s"access size $size is invalid")
    check(ord == Ord.ACQREL || ord == Ord.SEQ, s"ordered access needs an acquire, release or seq_cst annotation")
    (op << OP_LO) | (reg(t, "rt") << RD_LO) | (reg(n, "rn") << RN_LO) |
      (size << 14) | (ord << 12)
  }

  def encAtomic(fn: Int, d: Int, n: Int, s: Int, size: Int, ord: Int): Int = {
    check(AtomicFn.ALL.contains(fn), s"no atomic function $fn")
    check(size == SIZE_W || size == SIZE_D, "atomics operate on words or doublewords")
    check(ord >= 0 && ord <= 3, s"invalid ordering $ord")
    (ATOMIC << OP_LO) | (reg(d, "rd") << RD_LO) | (reg(n, "rn") << RN_LO) |
      (reg(s, "rs") << RM_LO) | (fn << 7) | (ord << 5) | (size << 3)
  }

  def encFence(kind: Int): Int = {
    check(FenceKind.ALL.contains(kind), s"no fence kind $kind")
    (FENCE << OP_LO) | kind
  }

  def encSystem(fn: Int): Int = {
    check(SystemFn.ALL.contains(fn), s"no system function $fn")
    (SYSTEM << OP_LO) | fn
  }

  // =====================================================================
  // Legality
  // =====================================================================

  val LOADS: Set[Int] = Set(LDB, LDBU, LDH, LDHU, LDW, LDWU, LDD)
  val STORES: Set[Int] = Set(STB, STH, STW, STD)

  /** Access size of a single-register memory opcode. */
  def accessSize(op: Int): Int = op match {
    case LDB | LDBU | STB => SIZE_B
    case LDH | LDHU | STH => SIZE_H
    case LDW | LDWU | STW => SIZE_W
    case LDD | STD        => SIZE_D
    case other            => throw new IllegalArgumentException(f"opcode 0x$other%02x is not a sized access")
  }

  /** Whether a load sign extends its result. */
  def loadSigned(op: Int): Boolean = op == LDB || op == LDH || op == LDW

  val PRIMARY_OPCODES: Set[Int] = Set(
    ALU_R, ALU_SHIFT, ADDI, ANDI, ORI, XORI, SLTI, SLTUI,
    MOVZ, MOVN, MOVK, ADDPC, CMP_R, CMP_I, SEL, ALU_DIV
  ) ++ LOADS ++ STORES ++ Set(LDP, STP, B, BL, BP, JALR, LD_ORD, ST_ORD, ATOMIC, FENCE, SYSTEM)

  /** Whether an instruction word is architecturally defined.
    *
    * Both the primary opcode and any sub-function have to be defined, so that
    * the reserved space inside a class traps rather than aliasing.
    */
  def isLegal(instr: Int): Boolean = {
    val op = opcode(instr)
    if (!PRIMARY_OPCODES.contains(op)) return false
    op match {
      case ALU_R   => Fn.ALL.contains(aluFn(instr))
      case ALU_DIV => DivFn.ALL.contains(divFn(instr))
      case CMP_R  => Cc.ALL.contains(ccR(instr))
      case CMP_I  => Cc.ALL.contains(ccI(instr))
      case ATOMIC =>
        AtomicFn.ALL.contains(atomicFn(instr)) &&
          (atomicSize(instr) == SIZE_W || atomicSize(instr) == SIZE_D)
      case FENCE  => FenceKind.ALL.contains(fenceKind(instr))
      case SYSTEM => SystemFn.ALL.contains(systemFn(instr))
      case LD_ORD | ST_ORD => ordKind(instr) == Ord.ACQREL || ordKind(instr) == Ord.SEQ
      case op if LOADS.contains(op) || STORES.contains(op) => memMode(instr) <= Mode.POST
      case LDP | STP => pairMode(instr) <= Mode.POST
      case _ => true
    }
  }

  /** Instructions that update their base register as well as their data
    * register. These are the forms that cost a second write port, which is the
    * price of the code density they buy.
    */
  def writesBase(instr: Int): Boolean = opcode(instr) match {
    case op if LOADS.contains(op) || STORES.contains(op) => memMode(instr) != Mode.OFFSET
    case LDP | STP => pairMode(instr) != Mode.OFFSET
    case _ => false
  }

  // =====================================================================
  // Disassembly
  // =====================================================================

  val MNEMONICS: Map[Int, String] = Map(
    ADDI -> "addi", ANDI -> "andi", ORI -> "ori", XORI -> "xori",
    SLTI -> "slti", SLTUI -> "sltui", MOVZ -> "movz", MOVN -> "movn",
    MOVK -> "movk", ADDPC -> "addpc", SEL -> "sel",
    LDB -> "ldb", LDBU -> "ldbu", LDH -> "ldh", LDHU -> "ldhu",
    LDW -> "ldw", LDWU -> "ldwu", LDD -> "ldd",
    STB -> "stb", STH -> "sth", STW -> "stw", STD -> "std",
    LDP -> "ldp", STP -> "stp", B -> "b", BL -> "bl", JALR -> "jalr",
    LD_ORD -> "ld", ST_ORD -> "st", FENCE -> "fence")

  private val SIZE_SUFFIX = Array("b", "h", "w", "d")

  def disassemble(instr: Int, pc: Int = 0): String = {
    val op = opcode(instr)
    def r(i: Int) = regName(i)
    def p(i: Int) = predName(i)
    if (!isLegal(instr)) return f"<illegal 0x$instr%08x>"
    def memOperand(base: Int, imm: Int, mode: Int): String = mode match {
      case Mode.OFFSET => s"[${r(base)}, $imm]"
      case Mode.PRE    => s"[${r(base)}, $imm]!"
      case _           => s"[${r(base)}], $imm"
    }
    op match {
      case ALU_R => s"${Fn.NAMES(aluFn(instr))} ${r(rd(instr))}, ${r(rn(instr))}, ${r(rm(instr))}"
      case ALU_DIV =>
        s"${DivFn.NAMES(divFn(instr))} ${r(rd(instr))}, ${r(rn(instr))}, ${r(rm(instr))}"
      case ALU_SHIFT => s"${ShiftFn.NAMES(shiftFn(instr))} ${r(rd(instr))}, ${r(rn(instr))}, ${shiftAmount(instr)}"
      case ADDI | ANDI | ORI | XORI | SLTI | SLTUI =>
        s"${MNEMONICS(op)} ${r(rd(instr))}, ${r(rn(instr))}, ${imm16s(instr)}"
      case MOVZ | MOVN | MOVK =>
        val sh = movShift(instr)
        s"${MNEMONICS(op)} ${r(rd(instr))}, 0x${imm16(instr).toHexString}" + (if (sh != 0) s", lsl ${sh * 16}" else "")
      case ADDPC => s"addpc ${r(rd(instr))}, ${imm21(instr)}"
      case CMP_R => s"cmp.${Cc.NAMES(ccR(instr))} ${p(pd(instr))}, ${r(rn(instr))}, ${r(rm(instr))}"
      case CMP_I => s"cmpi.${Cc.NAMES(ccI(instr))} ${p(pd(instr))}, ${r(rn(instr))}, ${imm12(instr)}"
      case SEL =>
        val sense = if (selInvert(instr)) "!" else ""
        s"sel ${r(rd(instr))}, ${r(rn(instr))}, ${r(rm(instr))}, $sense${p(selP(instr))}"
      case o if LOADS.contains(o) || STORES.contains(o) =>
        s"${MNEMONICS(o)} ${r(rd(instr))}, ${memOperand(rn(instr), memImm(instr) * sizeBytes(accessSize(o)), memMode(instr))}"
      case LDP | STP =>
        s"${MNEMONICS(op)} ${r(rd(instr))}, ${r(pairRt2(instr))}, ${memOperand(rn(instr), pairImm(instr) * 8, pairMode(instr))}"
      case B | BL => f"${MNEMONICS(op)} 0x${pc + off26(instr)}%08x"
      case BP =>
        val sense = if (bpInvert(instr)) "!" else ""
        f"b.$sense${p(bpPred(instr))} 0x${pc + off22(instr)}%08x"
      case JALR => s"jalr ${r(rd(instr))}, ${r(rn(instr))}, ${imm16s(instr)}"
      case LD_ORD => s"ld${SIZE_SUFFIX(ordSize(instr))}${Ord.NAMES(ordKind(instr))} ${r(rd(instr))}, [${r(rn(instr))}]"
      case ST_ORD => s"st${SIZE_SUFFIX(ordSize(instr))}${Ord.NAMES(ordKind(instr))} ${r(rd(instr))}, [${r(rn(instr))}]"
      case ATOMIC =>
        s"${AtomicFn.NAMES(atomicFn(instr))}${SIZE_SUFFIX(atomicSize(instr))}${Ord.NAMES(atomicOrd(instr))} " +
          s"${r(rd(instr))}, ${r(atomicRs(instr))}, [${r(rn(instr))}]"
      case FENCE => FenceKind.NAMES(fenceKind(instr))
      case SYSTEM => SystemFn.NAMES(systemFn(instr))
      case other => f"<0x$other%02x>"
    }
  }
}
