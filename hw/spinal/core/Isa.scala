package core

/** Encoding tables for the CORE-32 instruction set.
  *
  * This object is deliberately free of any SpinalHDL dependency: the RTL
  * decoder, the assembler and the reference model all read their opcode
  * numbers from here, so there is exactly one place where the encoding is
  * defined and no way for the three to drift apart.
  *
  * See docs/isa.md for the architectural description.
  */
object Isa {

  // ---------------------------------------------------------------------
  // Geometry
  // ---------------------------------------------------------------------

  val XLEN          = 32
  val INSTR_BITS    = 32
  val REG_COUNT     = 16
  val REG_ADDR_BITS = 4

  /** Bit positions of the fixed fields. */
  val OP_HI = 31; val OP_LO = 26
  val RD_HI = 25; val RD_LO = 22
  val RA_HI = 21; val RA_LO = 18
  val RB_HI = 17; val RB_LO = 14

  val IMM18_BITS = 18
  val IMM22_BITS = 22
  val OFF14_BITS = 14
  val OFF26_BITS = 26

  // ---------------------------------------------------------------------
  // Opcode classes: op(5 downto 4)
  // ---------------------------------------------------------------------

  val CLS_ALU_R = 0x0 // register-register ALU        (RRR)
  val CLS_ALU_I = 0x1 // immediate ALU and constants  (RRI / RI)
  val CLS_MEM   = 0x2 // load, store, system          (RRI)
  val CLS_CTRL  = 0x3 // branches and jumps           (BR / J / RRI)

  // ---------------------------------------------------------------------
  // Class 0: register ALU. The low nibble doubles as the ALU function code.
  // ---------------------------------------------------------------------

  val ADD   = 0x00
  val SUB   = 0x01
  val AND   = 0x02
  val OR    = 0x03
  val XOR   = 0x04
  val SHL   = 0x05
  val SHR   = 0x06
  val SAR   = 0x07
  val SLT   = 0x08
  val SLTU  = 0x09
  val MUL   = 0x0a
  val MULH  = 0x0b
  val MULHU = 0x0c
  val SEQ   = 0x0d
  val SNE   = 0x0e
  val ROR   = 0x0f

  /** ALU function codes, identical to the class-0 opcodes above. The decoder
    * feeds `op(3 downto 0)` straight into the ALU for both class 0 and the
    * RRI half of class 1, which is the whole point of the opcode layout.
    */
  object Fn {
    val ADD = Isa.ADD; val SUB = Isa.SUB; val AND = Isa.AND; val OR    = Isa.OR
    val XOR = Isa.XOR; val SHL = Isa.SHL; val SHR = Isa.SHR; val SAR   = Isa.SAR
    val SLT = Isa.SLT; val SLTU= Isa.SLTU;val MUL = Isa.MUL; val MULH  = Isa.MULH
    val MULHU = Isa.MULHU; val SEQ = Isa.SEQ; val SNE = Isa.SNE; val ROR = Isa.ROR
  }

  // ---------------------------------------------------------------------
  // Class 1: immediate ALU and constant formation.
  // ---------------------------------------------------------------------

  val ADDI  = 0x10
  // 0x11 reserved: no SUBI, negate the immediate instead.
  val ANDI  = 0x12
  val ORI   = 0x13
  val XORI  = 0x14
  val SHLI  = 0x15
  val SHRI  = 0x16
  val SARI  = 0x17
  val SLTI  = 0x18
  val SLTUI = 0x19
  val MOVI  = 0x1a // RI: rd = sext(imm22)
  val MOVHI = 0x1b // RI: rd = (imm22 << 10) | (rd & 0x3ff)
  val ADDPC = 0x1c // RI: rd = pc + sext(imm22)
  val SEQI  = 0x1d
  val SNEI  = 0x1e
  val RORI  = 0x1f

  /** Class-1 opcodes that are RI-format constant formation rather than an
    * immediate ALU operation, i.e. the three that break the `op | 0x10`
    * mirror because the multiplies have no immediate form.
    */
  val CLS1_RI_OPS: Set[Int] = Set(MOVI, MOVHI, ADDPC)

  /** Class-1 opcode that is architecturally reserved. */
  val CLS1_RESERVED: Set[Int] = Set(0x11)

  // ---------------------------------------------------------------------
  // Class 2: memory and system. op(3) = store, op(2 downto 0) = size/extend.
  // ---------------------------------------------------------------------

  val LDW  = 0x20
  val LDH  = 0x21
  val LDHU = 0x22
  val LDB  = 0x23
  val LDBU = 0x24
  val STW  = 0x28
  val STH  = 0x29
  val STB  = 0x2a
  val HALT = 0x2f

  /** Access size encoding carried down the pipeline. */
  val SIZE_B = 0
  val SIZE_H = 1
  val SIZE_W = 2

  // ---------------------------------------------------------------------
  // Class 3: control transfer. op(3) = 0 conditional branch, 1 jump.
  // ---------------------------------------------------------------------

  val BEQ   = 0x30
  val BNE   = 0x31
  val BLT   = 0x32
  val BGE   = 0x33
  val BLTU  = 0x34
  val BGEU  = 0x35
  val JMP   = 0x38
  val CALL  = 0x39
  val JMPR  = 0x3a
  val CALLR = 0x3b

  /** Register number of the implicit link register written by CALL. */
  val LR = 15

  // ---------------------------------------------------------------------
  // Trap causes reported on the debug interface.
  // ---------------------------------------------------------------------

  object Cause {
    val NONE              = 0
    val ILLEGAL           = 1
    val MISALIGNED_FETCH  = 2
    val MISALIGNED_LOAD   = 3
    val MISALIGNED_STORE  = 4
    val WIDTH             = 3
  }

  // ---------------------------------------------------------------------
  // ABI register names, used by the assembler and by simulation dumps.
  // ---------------------------------------------------------------------

  val REG_NAMES: Seq[String] = Seq(
    "zero", "a0", "a1", "a2", "a3", "t0", "t1", "t2",
    "s0", "s1", "s2", "s3", "gp", "fp", "sp", "lr"
  )

  def regName(i: Int): String = if (i >= 0 && i < REG_COUNT) REG_NAMES(i) else s"r$i"

  // ---------------------------------------------------------------------
  // Field extraction helpers, shared by the disassembler and reference model.
  // ---------------------------------------------------------------------

  private def bits(instr: Int, hi: Int, lo: Int): Int =
    (instr >>> lo) & ((1 << (hi - lo + 1)) - 1)

  private def signExtend(value: Int, width: Int): Int = {
    val shift = 32 - width
    (value << shift) >> shift
  }

  def opcode(instr: Int): Int = bits(instr, OP_HI, OP_LO)
  def opClass(instr: Int): Int = opcode(instr) >>> 4
  def aluFn(instr: Int): Int = opcode(instr) & 0xf
  def rd(instr: Int): Int = bits(instr, RD_HI, RD_LO)
  def ra(instr: Int): Int = bits(instr, RA_HI, RA_LO)
  def rb(instr: Int): Int = bits(instr, RB_HI, RB_LO)

  def imm18(instr: Int): Int = signExtend(instr & 0x3ffff, IMM18_BITS)
  def imm22(instr: Int): Int = signExtend(instr & 0x3fffff, IMM22_BITS)
  def off14(instr: Int): Int = signExtend(instr & 0x3fff, OFF14_BITS) << 2
  def off26(instr: Int): Int = signExtend(instr & 0x3ffffff, OFF26_BITS) << 2

  // ---------------------------------------------------------------------
  // Encoders. These are the single source of truth for how an instruction is
  // assembled; the assembler in Assembler.scala is a thin layer on top.
  // ---------------------------------------------------------------------

  private def check(cond: Boolean, msg: => String): Unit =
    if (!cond) throw new IllegalArgumentException(msg)

  private def checkReg(r: Int, what: String): Int = {
    check(r >= 0 && r < REG_COUNT, s"$what register $r out of range 0..${REG_COUNT - 1}")
    r
  }

  private def checkField(v: Int, width: Int, what: String): Int = {
    val lo = -(1 << (width - 1))
    val hi = (1 << width) - 1
    check(v >= lo && v <= hi, s"$what field $v does not fit in $width bits ($lo..$hi)")
    v & ((1 << width) - 1)
  }

  private def checkSigned(v: Int, width: Int, what: String): Int = {
    val lo = -(1 << (width - 1))
    val hi = (1 << (width - 1)) - 1
    check(v >= lo && v <= hi, s"$what immediate $v does not fit in $width signed bits ($lo..$hi)")
    v & ((1 << width) - 1)
  }

  /** Registers only: `rd = ra op rb`. */
  def encRRR(op: Int, rd: Int, ra: Int, rb: Int): Int =
    (op << OP_LO) | (checkReg(rd, "rd") << RD_LO) |
      (checkReg(ra, "ra") << RA_LO) | (checkReg(rb, "rb") << RB_LO)

  /** Two registers and an 18-bit signed immediate. */
  def encRRI(op: Int, rd: Int, ra: Int, imm: Int): Int =
    (op << OP_LO) | (checkReg(rd, "rd") << RD_LO) |
      (checkReg(ra, "ra") << RA_LO) | checkSigned(imm, IMM18_BITS, "imm18")

  /** One register and a 22-bit signed immediate. */
  def encRI(op: Int, rd: Int, imm: Int): Int =
    (op << OP_LO) | (checkReg(rd, "rd") << RD_LO) | checkSigned(imm, IMM22_BITS, "imm22")

  /** One register and a raw 22-bit field.
    *
    * MOVHI's immediate is a bit pattern spliced into the top of the register
    * rather than a number, so it accepts the full unsigned range as well as
    * the signed one.
    */
  def encRIField(op: Int, rd: Int, imm: Int): Int =
    (op << OP_LO) | (checkReg(rd, "rd") << RD_LO) | checkField(imm, IMM22_BITS, "imm22")

  /** Conditional branch: two source registers and a byte offset. */
  def encBR(op: Int, ra: Int, rb: Int, byteOffset: Int): Int = {
    check((byteOffset & 3) == 0, s"branch offset $byteOffset is not 4-byte aligned")
    (op << OP_LO) | (checkReg(ra, "ra") << RA_LO) | (checkReg(rb, "rb") << RB_LO) |
      checkSigned(byteOffset >> 2, OFF14_BITS, "branch offset word")
  }

  /** Jump: a byte offset only. */
  def encJ(op: Int, byteOffset: Int): Int = {
    check((byteOffset & 3) == 0, s"jump offset $byteOffset is not 4-byte aligned")
    (op << OP_LO) | checkSigned(byteOffset >> 2, OFF26_BITS, "jump offset word")
  }

  // ---------------------------------------------------------------------
  // Legality, shared with the RTL illegal-instruction check via tests.
  // ---------------------------------------------------------------------

  /** Every architecturally defined opcode. */
  val DEFINED_OPCODES: Set[Int] = Set(
    ADD, SUB, AND, OR, XOR, SHL, SHR, SAR, SLT, SLTU, MUL, MULH, MULHU, SEQ, SNE, ROR,
    ADDI, ANDI, ORI, XORI, SHLI, SHRI, SARI, SLTI, SLTUI, MOVI, MOVHI, ADDPC, SEQI, SNEI, RORI,
    LDW, LDH, LDHU, LDB, LDBU, STW, STH, STB, HALT,
    BEQ, BNE, BLT, BGE, BLTU, BGEU, JMP, CALL, JMPR, CALLR
  )

  def isLegal(instr: Int): Boolean = DEFINED_OPCODES.contains(opcode(instr))

  val MNEMONICS: Map[Int, String] = Map(
    ADD -> "add", SUB -> "sub", AND -> "and", OR -> "or", XOR -> "xor",
    SHL -> "shl", SHR -> "shr", SAR -> "sar", SLT -> "slt", SLTU -> "sltu",
    MUL -> "mul", MULH -> "mulh", MULHU -> "mulhu", SEQ -> "seq", SNE -> "sne", ROR -> "ror",
    ADDI -> "addi", ANDI -> "andi", ORI -> "ori", XORI -> "xori",
    SHLI -> "shli", SHRI -> "shri", SARI -> "sari", SLTI -> "slti", SLTUI -> "sltui",
    MOVI -> "movi", MOVHI -> "movhi", ADDPC -> "addpc", SEQI -> "seqi", SNEI -> "snei", RORI -> "rori",
    LDW -> "ldw", LDH -> "ldh", LDHU -> "ldhu", LDB -> "ldb", LDBU -> "ldbu",
    STW -> "stw", STH -> "sth", STB -> "stb", HALT -> "halt",
    BEQ -> "beq", BNE -> "bne", BLT -> "blt", BGE -> "bge", BLTU -> "bltu", BGEU -> "bgeu",
    JMP -> "jmp", CALL -> "call", JMPR -> "jmpr", CALLR -> "callr"
  )

  /** A one-line disassembly, used in simulation traces and failure messages. */
  def disassemble(instr: Int, pc: Int = 0): String = {
    val op = opcode(instr)
    val m  = MNEMONICS.getOrElse(op, f"<illegal 0x$op%02x>")
    def r(i: Int) = regName(i)
    op match {
      case HALT => "halt"
      case _ if opClass(instr) == CLS_ALU_R => s"$m ${r(rd(instr))}, ${r(ra(instr))}, ${r(rb(instr))}"
      case MOVI | MOVHI | ADDPC => s"$m ${r(rd(instr))}, ${imm22(instr)}"
      case _ if opClass(instr) == CLS_ALU_I => s"$m ${r(rd(instr))}, ${r(ra(instr))}, ${imm18(instr)}"
      case LDW | LDH | LDHU | LDB | LDBU | STW | STH | STB =>
        s"$m ${r(rd(instr))}, [${r(ra(instr))} ${if (imm18(instr) < 0) "-" else "+"} ${imm18(instr).abs}]"
      case BEQ | BNE | BLT | BGE | BLTU | BGEU =>
        f"$m ${r(ra(instr))}, ${r(rb(instr))}, 0x${pc + off14(instr)}%08x"
      case JMP | CALL => f"$m 0x${pc + off26(instr)}%08x"
      case JMPR       => s"$m ${r(ra(instr))}, ${imm18(instr)}"
      case CALLR      => s"$m ${r(rd(instr))}, ${r(ra(instr))}, ${imm18(instr)}"
      case _          => f"$m (0x$instr%08x)"
    }
  }
}
