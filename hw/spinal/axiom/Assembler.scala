package axiom

import scala.collection.mutable

/** A two-pass assembler for Axiom-64.
  *
  * Programs are written as ordinary Scala. That choice is the same one the
  * CORE-32 assembler made and for the same reason: test programs get loops,
  * constants and comments from the host language, so a real toolchain does not
  * have to exist before the first program can be run. Labels are plain strings
  * and may be referenced before they are defined.
  *
  * The two passes are not separate traversals of a source text. Each emitted
  * instruction is stored as a closure from (its own address, the label table)
  * to a 32-bit word. Emission is the first pass and `assemble` is the second,
  * which is what lets a branch refer forwards. The consequence that matters
  * for correctness is that the number of instructions must be known at
  * emission time, before any label value is: that is why `la` always emits
  * four instructions even when the address would fit in one, and why `li`,
  * whose value is known immediately, is allowed to vary.
  *
  * {{{
  *   val prog = Assembler(0) { a => import a._
  *     li(a0, 10)
  *     mov(a1, zero)
  *     label("loop")
  *     add(a1, a1, a0)
  *     addi(a0, a0, -1)
  *     cmpiNe(p0, a0, 0)
  *     bp(p0, "loop")
  *     halt()
  *   }
  * }}}
  *
  * Every encoding goes through `Isa`, never through a literal bit pattern
  * built here, so that the assembler, the reference model and the RTL cannot
  * drift apart.
  */
class Assembler(val baseAddress: Long = 0) {

  private val words  = mutable.ArrayBuffer[(Long, Map[String, Long]) => Int]()
  private val labels = mutable.LinkedHashMap[String, Long]()

  // -- ABI register names, so programs read like assembly ------------------
  // Straight from the table in section 1.1 of the specification.
  val zero = 0
  val a0 = 1;  val a1 = 2;  val a2 = 3;  val a3 = 4;  val a4 = 5
  val a5 = 6;  val a6 = 7
  val t0 = 8;  val t1 = 9;  val t2 = 10; val t3 = 11; val t4 = 12
  val t5 = 13; val t6 = 14; val t7 = 15; val t8 = 16; val t9 = 17
  val s0 = 18; val s1 = 19; val s2 = 20; val s3 = 21; val s4 = 22
  val s5 = 23; val s6 = 24; val s7 = 25; val s8 = 26; val s9 = 27
  val s10 = 28; val s11 = 29
  val lr = Isa.LR
  val sp = Isa.SP

  // -- Predicate names ------------------------------------------------------
  val p0 = 0; val p1 = 1; val p2 = 2; val p3 = 3
  val p4 = 4; val p5 = 5; val p6 = 6; val p7 = 7

  /** Address of the next instruction to be emitted. */
  def pc: Long = baseAddress + words.length.toLong * 4

  /** Define a label at the current address. */
  def label(name: String): Unit = {
    require(!labels.contains(name), s"duplicate label '$name'")
    labels(name) = pc
  }

  /** Emit a fully resolved instruction word. */
  def word(value: Int): Unit = words += ((_, _) => value)

  /** Emit an instruction whose encoding depends on its own address or on
    * labels that may not be defined yet. This is the whole of the second pass
    * mechanism; nothing else about the program is deferred.
    */
  private def deferred(f: (Long, Map[String, Long]) => Int): Unit = words += f

  private def target(name: String, map: Map[String, Long]): Long =
    map.getOrElse(name, throw new NoSuchElementException(s"undefined label '$name'"))

  /** Resolve every instruction. Safe to call more than once, and cheap, so
    * tests may call it after adding more code.
    */
  def assemble(): Array[Int] = {
    val map = labels.toMap
    words.zipWithIndex.map { case (f, i) => f(baseAddress + i.toLong * 4, map) }.toArray
  }

  /** The resolved label table, handy for pointing a test at a routine. */
  def symbols: Map[String, Long] = labels.toMap

  def sizeBytes: Long = words.length.toLong * 4

  // =====================================================================
  // Opcode 0x00: register ALU
  // =====================================================================

  private def aluR(fn: Int, rd: Int, rn: Int, rm: Int): Unit = word(Isa.encAluR(fn, rd, rn, rm))

  def add(rd: Int, rn: Int, rm: Int): Unit   = aluR(Isa.Fn.ADD, rd, rn, rm)
  def sub(rd: Int, rn: Int, rm: Int): Unit   = aluR(Isa.Fn.SUB, rd, rn, rm)
  def and(rd: Int, rn: Int, rm: Int): Unit   = aluR(Isa.Fn.AND, rd, rn, rm)
  def or(rd: Int, rn: Int, rm: Int): Unit    = aluR(Isa.Fn.OR, rd, rn, rm)
  def xor(rd: Int, rn: Int, rm: Int): Unit   = aluR(Isa.Fn.XOR, rd, rn, rm)
  def andn(rd: Int, rn: Int, rm: Int): Unit  = aluR(Isa.Fn.ANDN, rd, rn, rm)
  def orn(rd: Int, rn: Int, rm: Int): Unit   = aluR(Isa.Fn.ORN, rd, rn, rm)
  def xnor(rd: Int, rn: Int, rm: Int): Unit  = aluR(Isa.Fn.XNOR, rd, rn, rm)
  def shl(rd: Int, rn: Int, rm: Int): Unit   = aluR(Isa.Fn.SHL, rd, rn, rm)
  def shr(rd: Int, rn: Int, rm: Int): Unit   = aluR(Isa.Fn.SHR, rd, rn, rm)
  def sar(rd: Int, rn: Int, rm: Int): Unit   = aluR(Isa.Fn.SAR, rd, rn, rm)
  def ror(rd: Int, rn: Int, rm: Int): Unit   = aluR(Isa.Fn.ROR, rd, rn, rm)
  def slt(rd: Int, rn: Int, rm: Int): Unit   = aluR(Isa.Fn.SLT, rd, rn, rm)
  def sltu(rd: Int, rn: Int, rm: Int): Unit  = aluR(Isa.Fn.SLTU, rd, rn, rm)
  def mul(rd: Int, rn: Int, rm: Int): Unit   = aluR(Isa.Fn.MUL, rd, rn, rm)
  def mulh(rd: Int, rn: Int, rm: Int): Unit  = aluR(Isa.Fn.MULH, rd, rn, rm)
  def mulhu(rd: Int, rn: Int, rm: Int): Unit = aluR(Isa.Fn.MULHU, rd, rn, rm)
  def addw(rd: Int, rn: Int, rm: Int): Unit  = aluR(Isa.Fn.ADDW, rd, rn, rm)
  def subw(rd: Int, rn: Int, rm: Int): Unit  = aluR(Isa.Fn.SUBW, rd, rn, rm)
  def mulw(rd: Int, rn: Int, rm: Int): Unit  = aluR(Isa.Fn.MULW, rd, rn, rm)
  def shlw(rd: Int, rn: Int, rm: Int): Unit  = aluR(Isa.Fn.SHLW, rd, rn, rm)
  def shrw(rd: Int, rn: Int, rm: Int): Unit  = aluR(Isa.Fn.SHRW, rd, rn, rm)
  def sarw(rd: Int, rn: Int, rm: Int): Unit  = aluR(Isa.Fn.SARW, rd, rn, rm)
  def rorw(rd: Int, rn: Int, rm: Int): Unit  = aluR(Isa.Fn.RORW, rd, rn, rm)
  def min(rd: Int, rn: Int, rm: Int): Unit   = aluR(Isa.Fn.MIN, rd, rn, rm)
  def max(rd: Int, rn: Int, rm: Int): Unit   = aluR(Isa.Fn.MAX, rd, rn, rm)
  def minu(rd: Int, rn: Int, rm: Int): Unit  = aluR(Isa.Fn.MINU, rd, rn, rm)
  def maxu(rd: Int, rn: Int, rm: Int): Unit  = aluR(Isa.Fn.MAXU, rd, rn, rm)

  // =====================================================================
  // Opcode 0x01: shift by immediate
  // =====================================================================

  private def aluShift(fn: Int, rd: Int, rn: Int, shamt: Int): Unit =
    word(Isa.encAluShift(fn, rd, rn, shamt))

  def shli(rd: Int, rn: Int, shamt: Int): Unit   = aluShift(Isa.ShiftFn.SHL, rd, rn, shamt)
  def shri(rd: Int, rn: Int, shamt: Int): Unit   = aluShift(Isa.ShiftFn.SHR, rd, rn, shamt)
  def sari(rd: Int, rn: Int, shamt: Int): Unit   = aluShift(Isa.ShiftFn.SAR, rd, rn, shamt)
  def rori(rd: Int, rn: Int, shamt: Int): Unit   = aluShift(Isa.ShiftFn.ROR, rd, rn, shamt)
  def shlwi(rd: Int, rn: Int, shamt: Int): Unit  = aluShift(Isa.ShiftFn.SHLW, rd, rn, shamt)
  def shrwi(rd: Int, rn: Int, shamt: Int): Unit  = aluShift(Isa.ShiftFn.SHRW, rd, rn, shamt)
  def sarwi(rd: Int, rn: Int, shamt: Int): Unit  = aluShift(Isa.ShiftFn.SARW, rd, rn, shamt)
  def rorwi(rd: Int, rn: Int, shamt: Int): Unit  = aluShift(Isa.ShiftFn.RORW, rd, rn, shamt)

  // =====================================================================
  // Opcodes 0x02 to 0x07: immediate ALU
  // =====================================================================

  def addi(rd: Int, rn: Int, imm: Int): Unit  = word(Isa.encAluImm(Isa.ADDI, rd, rn, imm))
  def andi(rd: Int, rn: Int, imm: Int): Unit  = word(Isa.encAluImm(Isa.ANDI, rd, rn, imm))
  def ori(rd: Int, rn: Int, imm: Int): Unit   = word(Isa.encAluImm(Isa.ORI, rd, rn, imm))
  def xori(rd: Int, rn: Int, imm: Int): Unit  = word(Isa.encAluImm(Isa.XORI, rd, rn, imm))
  def slti(rd: Int, rn: Int, imm: Int): Unit  = word(Isa.encAluImm(Isa.SLTI, rd, rn, imm))
  def sltui(rd: Int, rn: Int, imm: Int): Unit = word(Isa.encAluImm(Isa.SLTUI, rd, rn, imm))

  // =====================================================================
  // Opcodes 0x08 to 0x0b: constant formation
  // =====================================================================

  /** `shift` is the lane number 0 to 3, not a bit count; the encoding stores
    * the lane and the hardware multiplies by sixteen.
    */
  def movz(rd: Int, imm: Int, shift: Int = 0): Unit = word(Isa.encMove(Isa.MOVZ, rd, imm, shift))
  def movn(rd: Int, imm: Int, shift: Int = 0): Unit = word(Isa.encMove(Isa.MOVN, rd, imm, shift))
  def movk(rd: Int, imm: Int, shift: Int = 0): Unit = word(Isa.encMove(Isa.MOVK, rd, imm, shift))

  def addpc(rd: Int, imm: Int): Unit = word(Isa.encAddPc(rd, imm))

  // =====================================================================
  // Opcodes 0x0c to 0x0e: compare and select
  // =====================================================================

  def cmp(cc: Int, pd: Int, rn: Int, rm: Int): Unit  = word(Isa.encCmpR(cc, pd, rn, rm))
  def cmpi(cc: Int, pd: Int, rn: Int, imm: Int): Unit = word(Isa.encCmpI(cc, pd, rn, imm))

  def cmpEq(pd: Int, rn: Int, rm: Int): Unit  = cmp(Isa.Cc.EQ, pd, rn, rm)
  def cmpNe(pd: Int, rn: Int, rm: Int): Unit  = cmp(Isa.Cc.NE, pd, rn, rm)
  def cmpLt(pd: Int, rn: Int, rm: Int): Unit  = cmp(Isa.Cc.LT, pd, rn, rm)
  def cmpGe(pd: Int, rn: Int, rm: Int): Unit  = cmp(Isa.Cc.GE, pd, rn, rm)
  def cmpLtu(pd: Int, rn: Int, rm: Int): Unit = cmp(Isa.Cc.LTU, pd, rn, rm)
  def cmpGeu(pd: Int, rn: Int, rm: Int): Unit = cmp(Isa.Cc.GEU, pd, rn, rm)
  def cmpLe(pd: Int, rn: Int, rm: Int): Unit  = cmp(Isa.Cc.LE, pd, rn, rm)
  def cmpGt(pd: Int, rn: Int, rm: Int): Unit  = cmp(Isa.Cc.GT, pd, rn, rm)
  def cmpLeu(pd: Int, rn: Int, rm: Int): Unit = cmp(Isa.Cc.LEU, pd, rn, rm)
  def cmpGtu(pd: Int, rn: Int, rm: Int): Unit = cmp(Isa.Cc.GTU, pd, rn, rm)

  def cmpEqi(pd: Int, rn: Int, imm: Int): Unit  = cmpi(Isa.Cc.EQ, pd, rn, imm)
  def cmpNei(pd: Int, rn: Int, imm: Int): Unit  = cmpi(Isa.Cc.NE, pd, rn, imm)
  def cmpLti(pd: Int, rn: Int, imm: Int): Unit  = cmpi(Isa.Cc.LT, pd, rn, imm)
  def cmpGei(pd: Int, rn: Int, imm: Int): Unit  = cmpi(Isa.Cc.GE, pd, rn, imm)
  def cmpLtui(pd: Int, rn: Int, imm: Int): Unit = cmpi(Isa.Cc.LTU, pd, rn, imm)
  def cmpGeui(pd: Int, rn: Int, imm: Int): Unit = cmpi(Isa.Cc.GEU, pd, rn, imm)
  def cmpLei(pd: Int, rn: Int, imm: Int): Unit  = cmpi(Isa.Cc.LE, pd, rn, imm)
  def cmpGti(pd: Int, rn: Int, imm: Int): Unit  = cmpi(Isa.Cc.GT, pd, rn, imm)
  def cmpLeui(pd: Int, rn: Int, imm: Int): Unit = cmpi(Isa.Cc.LEU, pd, rn, imm)
  def cmpGtui(pd: Int, rn: Int, imm: Int): Unit = cmpi(Isa.Cc.GTU, pd, rn, imm)

  /** `rd = (p xor invert) ? rn : rm`. */
  def sel(rd: Int, rn: Int, rm: Int, p: Int, invert: Boolean = false): Unit =
    word(Isa.encSel(rd, rn, rm, p, invert))

  // =====================================================================
  // Opcodes 0x10 to 0x1d: memory
  // =====================================================================

  /** Section 2.3 makes an indexed access whose data register is also its base
    * register architecturally undefined, because the data write and the base
    * write collide. Refusing to encode it here is the cheapest possible place
    * to catch the mistake, and it keeps the undefined case out of every test
    * program that is later compared against the RTL.
    */
  private def rejectCollision(rt: Int, rn: Int, mode: Int, what: String): Unit =
    if (mode != Isa.Mode.OFFSET && rt == rn)
      throw new IllegalArgumentException(
        s"$what writes both ${Isa.regName(rt)} and its base ${Isa.regName(rn)}, which section 2.3 leaves undefined")

  private def mem(op: Int, rt: Int, rn: Int, off: Int, mode: Int): Unit = {
    rejectCollision(rt, rn, mode, Isa.MNEMONICS(op))
    word(Isa.encMem(op, rt, rn, off, mode))
  }

  def ldb(rt: Int, rn: Int, off: Int = 0, mode: Int = Isa.Mode.OFFSET): Unit  = mem(Isa.LDB, rt, rn, off, mode)
  def ldbu(rt: Int, rn: Int, off: Int = 0, mode: Int = Isa.Mode.OFFSET): Unit = mem(Isa.LDBU, rt, rn, off, mode)
  def ldh(rt: Int, rn: Int, off: Int = 0, mode: Int = Isa.Mode.OFFSET): Unit  = mem(Isa.LDH, rt, rn, off, mode)
  def ldhu(rt: Int, rn: Int, off: Int = 0, mode: Int = Isa.Mode.OFFSET): Unit = mem(Isa.LDHU, rt, rn, off, mode)
  def ldw(rt: Int, rn: Int, off: Int = 0, mode: Int = Isa.Mode.OFFSET): Unit  = mem(Isa.LDW, rt, rn, off, mode)
  def ldwu(rt: Int, rn: Int, off: Int = 0, mode: Int = Isa.Mode.OFFSET): Unit = mem(Isa.LDWU, rt, rn, off, mode)
  def ldd(rt: Int, rn: Int, off: Int = 0, mode: Int = Isa.Mode.OFFSET): Unit  = mem(Isa.LDD, rt, rn, off, mode)
  def stb(rt: Int, rn: Int, off: Int = 0, mode: Int = Isa.Mode.OFFSET): Unit  = mem(Isa.STB, rt, rn, off, mode)
  def sth(rt: Int, rn: Int, off: Int = 0, mode: Int = Isa.Mode.OFFSET): Unit  = mem(Isa.STH, rt, rn, off, mode)
  def stw(rt: Int, rn: Int, off: Int = 0, mode: Int = Isa.Mode.OFFSET): Unit  = mem(Isa.STW, rt, rn, off, mode)
  def std(rt: Int, rn: Int, off: Int = 0, mode: Int = Isa.Mode.OFFSET): Unit  = mem(Isa.STD, rt, rn, off, mode)

  /** Load a pair. Both data registers collide with the base in an indexed
    * form, and the two of them collide with each other in any form, because
    * LDP writes both.
    */
  def ldp(rt1: Int, rt2: Int, rn: Int, off: Int = 0, mode: Int = Isa.Mode.OFFSET): Unit = {
    rejectCollision(rt1, rn, mode, "ldp")
    rejectCollision(rt2, rn, mode, "ldp")
    if (rt1 == rt2)
      throw new IllegalArgumentException(
        s"ldp writes ${Isa.regName(rt1)} twice, which leaves the result undefined")
    word(Isa.encPair(Isa.LDP, rt1, rt2, rn, off, mode))
  }

  /** Store a pair. Two equal data registers are harmless here, since a store
    * only reads them; only a collision with an updated base is rejected.
    */
  def stp(rt1: Int, rt2: Int, rn: Int, off: Int = 0, mode: Int = Isa.Mode.OFFSET): Unit = {
    rejectCollision(rt1, rn, mode, "stp")
    rejectCollision(rt2, rn, mode, "stp")
    word(Isa.encPair(Isa.STP, rt1, rt2, rn, off, mode))
  }

  // =====================================================================
  // Opcodes 0x20 to 0x23: control transfer
  // =====================================================================

  def b(name: String): Unit  = deferred((at, map) => Isa.encBranch(Isa.B, (target(name, map) - at).toInt))
  def bl(name: String): Unit = deferred((at, map) => Isa.encBranch(Isa.BL, (target(name, map) - at).toInt))

  def bp(p: Int, name: String, invert: Boolean = false): Unit =
    deferred((at, map) => Isa.encBp(p, invert, (target(name, map) - at).toInt))

  def jalr(rd: Int, rn: Int, imm: Int = 0): Unit = word(Isa.encJalr(rd, rn, imm))

  // =====================================================================
  // Opcodes 0x30 to 0x34: ordered accesses, atomics and system
  // =====================================================================

  def ldOrd(rt: Int, rn: Int, size: Int, ord: Int): Unit = word(Isa.encOrdered(Isa.LD_ORD, rt, rn, size, ord))
  def stOrd(rt: Int, rn: Int, size: Int, ord: Int): Unit = word(Isa.encOrdered(Isa.ST_ORD, rt, rn, size, ord))

  def atomic(fn: Int, rd: Int, rn: Int, rs: Int, size: Int, ord: Int): Unit =
    word(Isa.encAtomic(fn, rd, rn, rs, size, ord))

  def swp(rd: Int, rn: Int, rs: Int, size: Int = Isa.SIZE_D, ord: Int = Isa.Ord.PLAIN): Unit =
    atomic(Isa.AtomicFn.SWP, rd, rn, rs, size, ord)
  def ldadd(rd: Int, rn: Int, rs: Int, size: Int = Isa.SIZE_D, ord: Int = Isa.Ord.PLAIN): Unit =
    atomic(Isa.AtomicFn.ADD, rd, rn, rs, size, ord)
  def ldand(rd: Int, rn: Int, rs: Int, size: Int = Isa.SIZE_D, ord: Int = Isa.Ord.PLAIN): Unit =
    atomic(Isa.AtomicFn.AND, rd, rn, rs, size, ord)
  def ldor(rd: Int, rn: Int, rs: Int, size: Int = Isa.SIZE_D, ord: Int = Isa.Ord.PLAIN): Unit =
    atomic(Isa.AtomicFn.OR, rd, rn, rs, size, ord)
  def ldxor(rd: Int, rn: Int, rs: Int, size: Int = Isa.SIZE_D, ord: Int = Isa.Ord.PLAIN): Unit =
    atomic(Isa.AtomicFn.XOR, rd, rn, rs, size, ord)
  /** `cas rd, rs, [rn]`: rd is compared against memory and receives the old
    * value, so it is both a source and a destination.
    */
  def cas(rd: Int, rn: Int, rs: Int, size: Int = Isa.SIZE_D, ord: Int = Isa.Ord.PLAIN): Unit =
    atomic(Isa.AtomicFn.CAS, rd, rn, rs, size, ord)
  def ldmin(rd: Int, rn: Int, rs: Int, size: Int = Isa.SIZE_D, ord: Int = Isa.Ord.PLAIN): Unit =
    atomic(Isa.AtomicFn.MIN, rd, rn, rs, size, ord)
  def ldmax(rd: Int, rn: Int, rs: Int, size: Int = Isa.SIZE_D, ord: Int = Isa.Ord.PLAIN): Unit =
    atomic(Isa.AtomicFn.MAX, rd, rn, rs, size, ord)
  def ldminu(rd: Int, rn: Int, rs: Int, size: Int = Isa.SIZE_D, ord: Int = Isa.Ord.PLAIN): Unit =
    atomic(Isa.AtomicFn.MINU, rd, rn, rs, size, ord)
  def ldmaxu(rd: Int, rn: Int, rs: Int, size: Int = Isa.SIZE_D, ord: Int = Isa.Ord.PLAIN): Unit =
    atomic(Isa.AtomicFn.MAXU, rd, rn, rs, size, ord)

  def fence(kind: Int = Isa.FenceKind.FULL): Unit = word(Isa.encFence(kind))

  def halt(): Unit = word(Isa.encSystem(Isa.SystemFn.HALT))

  // =====================================================================
  // Aliases, section 7
  // =====================================================================

  def nop(): Unit                 = add(zero, zero, zero)
  def mov(rd: Int, rn: Int): Unit = add(rd, rn, zero)
  def neg(rd: Int, rn: Int): Unit = sub(rd, zero, rn)
  def not(rd: Int, rn: Int): Unit = xori(rd, rn, -1)
  def ret(): Unit                 = jalr(zero, lr, 0)
  def br(rn: Int): Unit           = jalr(zero, rn, 0)

  /** Load any 64-bit constant in the shortest sequence section 7 allows.
    *
    * A MOVZ seed zeroes the three lanes it does not write, so every lane that
    * is already zero is free; a MOVN seed sets them to all ones, so every lane
    * that is all ones is free. Counting both and taking the cheaper is exactly
    * the rule in the specification, and it bounds the sequence at four
    * instructions for any constant, which is why the architecture needs no
    * prefix instruction. The `max(_, 1)` is what makes 0 and -1 work: they
    * have no lane to seed from, so an explicit zero seed is emitted.
    */
  def li(rd: Int, value: Long): Unit = {
    val lanes = lanesOf(value)
    val nonZero = (0 until 4).filter(i => lanes(i) != 0)
    val nonOnes = (0 until 4).filter(i => lanes(i) != 0xffff)
    if (nonZero.length <= nonOnes.length) {
      // MOVZ seed. Lane 0 is the seed when the value is zero.
      val seed = if (nonZero.isEmpty) 0 else nonZero.head
      movz(rd, lanes(seed), seed)
      nonZero.drop(1).foreach(i => movk(rd, lanes(i), i))
    } else {
      val seed = if (nonOnes.isEmpty) 0 else nonOnes.head
      movn(rd, (~lanes(seed)) & 0xffff, seed)
      nonOnes.drop(1).foreach(i => movk(rd, lanes(i), i))
    }
  }

  /** Number of instructions `li` would emit. Kept beside `li` so the two
    * cannot drift; tests assert that they agree for random constants.
    */
  def liSize(value: Long): Int = {
    val lanes = lanesOf(value)
    val nonZero = (0 until 4).count(i => lanes(i) != 0)
    val nonOnes = (0 until 4).count(i => lanes(i) != 0xffff)
    math.max(math.min(nonZero, nonOnes), 1)
  }

  private def lanesOf(value: Long): Array[Int] =
    Array.tabulate(4)(i => ((value >>> (16 * i)) & 0xffffL).toInt)

  /** Load the address of a label.
    *
    * Always four instructions, whatever the address turns out to be. A shorter
    * sequence would have to know the address during the first pass, and the
    * address is not known until every instruction ahead of it has a size, so
    * the shorter sequence would move the labels it depends on. Fixing the
    * length breaks that circularity at the cost of up to three wasted words.
    */
  def la(rd: Int, name: String): Unit = {
    deferred((_, map) => Isa.encMove(Isa.MOVZ, rd, lane(target(name, map), 0), 0))
    for (i <- 1 to 3) deferred((_, map) => Isa.encMove(Isa.MOVK, rd, lane(target(name, map), i), i))
  }

  private def lane(value: Long, i: Int): Int = ((value >>> (16 * i)) & 0xffffL).toInt
}

/** Convenience entry points, mirroring the CORE-32 assembler so that a reader
  * moving between the two projects does not have to learn a second shape.
  */
object Assembler {

  /** Build a program with the assembler in scope. */
  def apply(base: Long = 0)(body: Assembler => Unit): Array[Int] = {
    val a = new Assembler(base)
    body(a)
    a.assemble()
  }

  /** Build a program and keep the assembler, for access to the label table. */
  def build(base: Long = 0)(body: Assembler => Unit): Assembler = {
    val a = new Assembler(base)
    body(a)
    a
  }

  /** Render a program as an annotated listing. */
  def listing(program: Array[Int], base: Long = 0): String =
    program.zipWithIndex.map { case (w, i) =>
      val at = base + i.toLong * 4
      f"$at%08x: $w%08x    ${Isa.disassemble(w, at.toInt)}"
    }.mkString("\n")
}
