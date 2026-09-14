package axiom

import org.scalatest.funsuite.AnyFunSuite
import scala.util.Random

/** Encoding and reference-model tests for Axiom-64.
  *
  * No hardware and no simulation: these check that the bit layout in
  * Isa.scala, the sequences the assembler builds and the behaviour the
  * reference model implements all match docs/axiom/isa.md, independently of
  * whether any RTL exists yet. Getting this right first means a later
  * mismatch against the core is a bug in the core and nowhere else.
  */
class AssemblerSpec extends AnyFunSuite {

  // =====================================================================
  // Field positions
  // =====================================================================

  test("R format puts opcode, rd, rn, rm and fn where the manual says") {
    val w = Isa.encAluR(Isa.Fn.SUB, d = 3, n = 5, m = 9)
    assert(Isa.opcode(w) == Isa.ALU_R)
    assert(Isa.rd(w) == 3 && Isa.rn(w) == 5 && Isa.rm(w) == 9)
    assert(Isa.aluFn(w) == Isa.Fn.SUB)
    assert((w & 0x3f) == 0, "the low six bits of an R instruction are reserved")
    // Every function code must survive the round trip, not just one.
    for (fn <- Isa.Fn.ALL) assert(Isa.aluFn(Isa.encAluR(fn, 1, 2, 3)) == fn)
  }

  test("SHIFT format carries a six-bit amount and a three-bit function") {
    for (fn <- Isa.ShiftFn.NAMES.keys; shamt <- Seq(0, 1, 31, if (fn >= Isa.ShiftFn.SHLW) 31 else 63)) {
      val w = Isa.encAluShift(fn, 7, 11, shamt)
      assert(Isa.opcode(w) == Isa.ALU_SHIFT)
      assert(Isa.rd(w) == 7 && Isa.rn(w) == 11)
      assert(Isa.shiftFn(w) == fn && Isa.shiftAmount(w) == shamt)
    }
  }

  test("I format carries a signed sixteen-bit immediate") {
    for (imm <- Seq(0, 1, -1, 32767, -32768, 1234, -1234)) {
      val w = Isa.encAluImm(Isa.ADDI, 15, 1, imm)
      assert(Isa.opcode(w) == Isa.ADDI && Isa.rd(w) == 15 && Isa.rn(w) == 1)
      assert(Isa.imm16s(w) == imm, s"imm16 round trip failed for $imm")
    }
  }

  test("MOV format carries a raw sixteen-bit pattern and a lane number") {
    for (op <- Seq(Isa.MOVZ, Isa.MOVN, Isa.MOVK); s <- 0 to 3; imm <- Seq(0, 1, 0xffff, 0x8000)) {
      val w = Isa.encMove(op, 4, imm, s)
      assert(Isa.opcode(w) == op && Isa.rd(w) == 4)
      assert(Isa.imm16(w) == imm, "the move immediate is a bit pattern and is not extended")
      assert(Isa.movShift(w) == s)
    }
  }

  test("PC format carries a signed twenty-one-bit byte displacement") {
    for (imm <- Seq(0, 4, -4, (1 << 20) - 1, -(1 << 20))) {
      val w = Isa.encAddPc(2, imm)
      assert(Isa.opcode(w) == Isa.ADDPC && Isa.rd(w) == 2 && Isa.imm21(w) == imm)
    }
  }

  test("CMP and CMPI place the predicate in the top of the rd field") {
    for (cc <- Isa.Cc.ALL; p <- 0 until Isa.PRED_COUNT) {
      val r = Isa.encCmpR(cc, p, 6, 7)
      assert(Isa.opcode(r) == Isa.CMP_R && Isa.pd(r) == p && Isa.ccR(r) == cc)
      assert(Isa.rn(r) == 6 && Isa.rm(r) == 7)
      assert((r >>> 24 & 0x3) == 0, "bits 25 and 24 above the predicate are reserved")
      val i = Isa.encCmpI(cc, p, 6, -3)
      assert(Isa.opcode(i) == Isa.CMP_I && Isa.pd(i) == p && Isa.ccI(i) == cc)
      assert(Isa.rn(i) == 6 && Isa.imm12(i) == -3)
    }
  }

  test("SEL carries three source registers, a predicate and a sense bit") {
    for (p <- 0 until Isa.PRED_COUNT; inv <- Seq(false, true)) {
      val w = Isa.encSel(1, 2, 3, p, inv)
      assert(Isa.opcode(w) == Isa.SEL)
      assert(Isa.rd(w) == 1 && Isa.rn(w) == 2 && Isa.rm(w) == 3)
      assert(Isa.selP(w) == p && Isa.selInvert(w) == inv)
    }
  }

  test("MEM scales its displacement by the access size") {
    // Fourteen bits of element displacement reaches 8192 elements either way,
    // which is 64 KiB for a doubleword.
    val d = Isa.encMem(Isa.LDD, 3, 4, byteOffset = 8 * 8191)
    assert(Isa.memImm(d) == 8191 && Isa.memMode(d) == Isa.Mode.OFFSET)
    val b = Isa.encMem(Isa.STB, 3, 4, byteOffset = -8192, mode = Isa.Mode.POST)
    assert(Isa.opcode(b) == Isa.STB && Isa.memImm(b) == -8192 && Isa.memMode(b) == Isa.Mode.POST)
    for (mode <- Seq(Isa.Mode.OFFSET, Isa.Mode.PRE, Isa.Mode.POST)) {
      val w = Isa.encMem(Isa.LDH, 5, 6, byteOffset = -2, mode = mode)
      assert(Isa.memMode(w) == mode && Isa.memImm(w) == -1)
      assert(Isa.rd(w) == 5 && Isa.rn(w) == 6)
    }
  }

  test("PAIR carries two data registers and a nine-bit displacement scaled by eight") {
    val w = Isa.encPair(Isa.LDP, t1 = 9, t2 = 10, n = 31, byteOffset = -16, mode = Isa.Mode.PRE)
    assert(Isa.opcode(w) == Isa.LDP)
    assert(Isa.rd(w) == 9 && Isa.pairRt2(w) == 10 && Isa.rn(w) == 31)
    assert(Isa.pairImm(w) == -2 && Isa.pairMode(w) == Isa.Mode.PRE)
    assert(Isa.pairImm(Isa.encPair(Isa.STP, 1, 2, 3, 8 * 255)) == 255)
    assert(Isa.pairImm(Isa.encPair(Isa.STP, 1, 2, 3, 8 * -256)) == -256)
  }

  test("branch displacements are word scaled and reach what the manual claims") {
    assert(Isa.off26(Isa.encBranch(Isa.B, 4 * 33554431)) == 4 * 33554431, "128 MiB forwards")
    assert(Isa.off26(Isa.encBranch(Isa.BL, 4 * -33554432)) == 4 * -33554432, "128 MiB backwards")
    val p = Isa.encBp(5, invert = true, 4 * 2097151)
    assert(Isa.opcode(p) == Isa.BP && Isa.bpPred(p) == 5 && Isa.bpInvert(p))
    assert(Isa.off22(p) == 4 * 2097151, "8 MiB forwards")
    assert(Isa.off22(Isa.encBp(0, invert = false, 4 * -2097152)) == 4 * -2097152)
    val j = Isa.encJalr(30, 31, -8)
    assert(Isa.opcode(j) == Isa.JALR && Isa.rd(j) == 30 && Isa.rn(j) == 31 && Isa.imm16s(j) == -8)
  }

  test("ORD and ATOM formats round trip") {
    for (size <- 0 to 3; ord <- Seq(Isa.Ord.ACQREL, Isa.Ord.SEQ)) {
      val w = Isa.encOrdered(Isa.LD_ORD, 2, 3, size, ord)
      assert(Isa.ordSize(w) == size && Isa.ordKind(w) == ord)
      assert(Isa.rd(w) == 2 && Isa.rn(w) == 3)
      assert((w & 0xfff) == 0, "an ordered access has no displacement")
    }
    for (fn <- Isa.AtomicFn.ALL; size <- Seq(Isa.SIZE_W, Isa.SIZE_D); ord <- 0 to 3) {
      val w = Isa.encAtomic(fn, 1, 2, 3, size, ord)
      assert(Isa.atomicFn(w) == fn && Isa.atomicSize(w) == size && Isa.atomicOrd(w) == ord)
      assert(Isa.rd(w) == 1 && Isa.rn(w) == 2 && Isa.atomicRs(w) == 3)
    }
    for (k <- Isa.FenceKind.ALL) assert(Isa.fenceKind(Isa.encFence(k)) == k)
    assert(Isa.systemFn(Isa.encSystem(Isa.SystemFn.HALT)) == Isa.SystemFn.HALT)
  }

  // =====================================================================
  // Rejection of things that do not fit
  // =====================================================================

  test("out of range operands are rejected") {
    assertThrows[IllegalArgumentException](Isa.encAluR(Isa.Fn.ADD, 32, 0, 0))
    assertThrows[IllegalArgumentException](Isa.encAluR(0x1c, 0, 0, 0))
    assertThrows[IllegalArgumentException](Isa.encAluShift(Isa.ShiftFn.SHL, 0, 0, 64))
    assertThrows[IllegalArgumentException](Isa.encAluShift(Isa.ShiftFn.SHLW, 0, 0, 32))
    assertThrows[IllegalArgumentException](Isa.encAluImm(Isa.ADDI, 0, 0, 32768))
    assertThrows[IllegalArgumentException](Isa.encAluImm(Isa.ADDI, 0, 0, -32769))
    assertThrows[IllegalArgumentException](Isa.encMove(Isa.MOVZ, 0, 0x10000, 0))
    assertThrows[IllegalArgumentException](Isa.encMove(Isa.MOVZ, 0, 0, 4))
    assertThrows[IllegalArgumentException](Isa.encAddPc(0, 1 << 20))
    assertThrows[IllegalArgumentException](Isa.encCmpR(10, 0, 0, 0))
    assertThrows[IllegalArgumentException](Isa.encCmpI(Isa.Cc.EQ, 8, 0, 0))
    assertThrows[IllegalArgumentException](Isa.encCmpI(Isa.Cc.EQ, 0, 0, 2048))
    assertThrows[IllegalArgumentException](Isa.encSel(0, 0, 0, 8, false))
    assertThrows[IllegalArgumentException](Isa.encMem(Isa.LDD, 0, 0, 4), "displacement must be scaled")
    assertThrows[IllegalArgumentException](Isa.encMem(Isa.LDD, 0, 0, 8 * 8192))
    assertThrows[IllegalArgumentException](Isa.encMem(Isa.LDD, 0, 0, 0, mode = 3))
    assertThrows[IllegalArgumentException](Isa.encPair(Isa.LDP, 0, 1, 2, 4))
    assertThrows[IllegalArgumentException](Isa.encPair(Isa.LDP, 0, 1, 2, 8 * 256))
    assertThrows[IllegalArgumentException](Isa.encBranch(Isa.B, 2))
    assertThrows[IllegalArgumentException](Isa.encBranch(Isa.B, 4 * 33554432))
    assertThrows[IllegalArgumentException](Isa.encBp(0, false, 4 * 2097152))
    assertThrows[IllegalArgumentException](Isa.encOrdered(Isa.LD_ORD, 0, 0, 3, Isa.Ord.PLAIN))
    assertThrows[IllegalArgumentException](Isa.encAtomic(Isa.AtomicFn.SWP, 0, 0, 0, Isa.SIZE_B, 0))
    assertThrows[IllegalArgumentException](Isa.encFence(4))
    assertThrows[IllegalArgumentException](Isa.encSystem(1))
  }

  test("an indexed or pair access may not name its base as a data register") {
    val a = new Assembler()
    // Section 2.3: the data write and the base write would collide.
    assertThrows[IllegalArgumentException](a.ldd(5, 5, 8, Isa.Mode.PRE))
    assertThrows[IllegalArgumentException](a.stb(7, 7, 1, Isa.Mode.POST))
    assertThrows[IllegalArgumentException](a.ldp(3, 4, 3, 0, Isa.Mode.POST))
    assertThrows[IllegalArgumentException](a.stp(3, 4, 4, 0, Isa.Mode.PRE))
    assertThrows[IllegalArgumentException](a.ldp(6, 6, 8))
    // The plain offset forms write only their data register, so they are fine.
    a.ldd(5, 5, 8)
    a.stb(7, 7, 1)
    a.stp(9, 9, 10)
    assert(a.assemble().length == 3)
  }

  // =====================================================================
  // Constant formation
  // =====================================================================

  /** Interpret a MOVZ/MOVN/MOVK sequence exactly as section 2.1 defines it,
    * independently of the reference model, so that a shared mistake in the two
    * cannot hide a broken expansion.
    */
  private def evaluate(words: Array[Int]): Long = {
    var rd = 0L
    words.foreach { w =>
      val shift = 16 * Isa.movShift(w)
      val imm = Isa.imm16(w).toLong
      Isa.opcode(w) match {
        case Isa.MOVZ => rd = imm << shift
        case Isa.MOVN => rd = ~(imm << shift)
        case Isa.MOVK => rd = (rd & ~(0xffffL << shift)) | (imm << shift)
        case other    => fail(f"unexpected opcode 0x$other%02x in a constant expansion")
      }
    }
    rd
  }

  private val liCases: Seq[Long] = Seq(
    0L, 1L, -1L, 7L, -7L, 0xffffL, 0x10000L, -0x10000L,
    0x8000000000000000L, 0x7fffffffffffffffL,
    0xffffffffffffL, 0x1234L, 0x12340000L, 0x123400000000L, 0x1234000000000000L,
    0xffffffff00000000L, 0x00000000ffffffffL, 0xffff0000ffff0000L,
    0x0123456789abcdefL, 0xdeadbeefcafef00dL, 0xfffffffffffff000L,
    0xfffeffffffffffffL, 0x0000ffff0000ffffL, Long.MinValue + 1, Long.MaxValue - 1)

  test("li builds every 64-bit constant in at most four instructions") {
    val rng = new Random(20240914)
    val values = liCases ++ Seq.fill(200)(rng.nextLong())
    for (v <- values) {
      val a = new Assembler()
      a.li(1, v)
      val words = a.assemble()
      assert(words.length <= 4, f"li 0x$v%016x took ${words.length} instructions")
      assert(words.length == a.liSize(v), f"liSize disagrees with li for 0x$v%016x")
      assert(evaluate(words) == v, f"li failed for 0x$v%016x")
      assert(words.forall(Isa.isLegal), "every instruction in a li expansion must be legal")
    }
  }

  test("li picks the shortest sequence the manual describes") {
    val a = new Assembler()
    // One lane non-zero, so a single MOVZ; zero is the degenerate case.
    assert(a.liSize(0L) == 1 && a.liSize(0x1234L) == 1 && a.liSize(0x1234000000000000L) == 1)
    // One lane differing from all ones, so a single MOVN.
    assert(a.liSize(-1L) == 1 && a.liSize(0xffffffffffff1234L) == 1)
    assert(a.liSize(0x12340000abcdL) == 2)
    assert(a.liSize(0xffff1234ffffabcdL) == 2)
    assert(a.liSize(0x0001000200030004L) == 4)
    // The seed really is a MOVN when that is cheaper.
    val n = Assembler(0)(_.li(1, 0xffffffffffff1234L))
    assert(Isa.opcode(n.head) == Isa.MOVN)
    val z = Assembler(0)(_.li(1, 0x1234L))
    assert(Isa.opcode(z.head) == Isa.MOVZ)
  }

  test("la always takes four instructions so labels cannot shift") {
    val asm = Assembler.build(0x1000) { a =>
      a.la(a.a0, "here")
      a.nop()
      a.label("here")
      a.halt()
    }
    val words = asm.assemble()
    assert(words.length == 6)
    assert(asm.symbols("here") == 0x1000 + 20)
    assert(evaluate(words.take(4)) == 0x1000 + 20)
    // A far label costs the same four instructions, which is the point.
    val far = Assembler.build(0x7fff000000000000L) { a => a.la(a.a0, "x"); a.label("x"); a.halt() }
    assert(far.assemble().length == 5)
    assert(evaluate(far.assemble().take(4)) == 0x7fff000000000010L)
  }

  test("labels resolve forwards and backwards") {
    val asm = Assembler.build() { a =>
      a.label("top")
      a.nop()
      a.bp(a.p0, "bottom")
      a.nop()
      a.label("bottom")
      a.b("top")
      a.bl("bottom")
    }
    val words = asm.assemble()
    assert(asm.symbols("top") == 0 && asm.symbols("bottom") == 12)
    assert(4 + Isa.off22(words(1)) == 12, "forward predicated branch")
    assert(12 + Isa.off26(words(3)) == 0, "backward unconditional branch")
    assert(16 + Isa.off26(words(4)) == 12, "backward call")
    assertThrows[NoSuchElementException](Assembler(0)(_.b("nowhere")))
  }

  // =====================================================================
  // Legality
  // =====================================================================

  test("exactly the documented opcodes and sub-functions are legal") {
    // Thirty-eight slots: sixteen arithmetic, seven loads, four stores, the
    // two pair forms, four control transfers and five in the ordered and
    // system group. The rest trap, so that adding an instruction later cannot
    // change the meaning of an old binary — which is exactly what divide did,
    // taking the sixteenth arithmetic slot and turning a previously reserved
    // opcode into a defined one.
    assert(Isa.PRIMARY_OPCODES.size == 38)
    for (op <- 0 until 64 if !Isa.PRIMARY_OPCODES.contains(op))
      assert(!Isa.isLegal(op << Isa.OP_LO), f"reserved opcode 0x$op%02x must be illegal")

    for (fn <- 0 until 32)
      assert(Isa.isLegal((Isa.ALU_R << Isa.OP_LO) | (fn << 6)) == Isa.Fn.ALL.contains(fn),
        f"ALU function 0x$fn%02x legality")
    for (fn <- 0 until 8)
      assert(Isa.isLegal((Isa.ALU_SHIFT << Isa.OP_LO) | (fn << 7)), s"shift function $fn is defined")
    // Divide uses three bits and defines all eight, so nothing inside the
    // opcode is reserved; the check is that the field is read where the
    // encoding says it is.
    for (fn <- 0 until 8)
      assert(Isa.isLegal((Isa.ALU_DIV << Isa.OP_LO) | (fn << 6)) == Isa.DivFn.ALL.contains(fn),
        s"divide function $fn legality")
    for (cc <- 0 until 16) {
      assert(Isa.isLegal((Isa.CMP_R << Isa.OP_LO) | (cc << 7)) == Isa.Cc.ALL.contains(cc))
      assert(Isa.isLegal((Isa.CMP_I << Isa.OP_LO) | (cc << 12)) == Isa.Cc.ALL.contains(cc))
    }
    for (fn <- 0 until 16; size <- 0 until 4)
      assert(Isa.isLegal((Isa.ATOMIC << Isa.OP_LO) | (fn << 7) | (size << 3)) ==
        (Isa.AtomicFn.ALL.contains(fn) && (size == Isa.SIZE_W || size == Isa.SIZE_D)))
    for (k <- 0 until 16)
      assert(Isa.isLegal((Isa.FENCE << Isa.OP_LO) | k) == Isa.FenceKind.ALL.contains(k))
    for (f <- 0 until 32)
      assert(Isa.isLegal((Isa.SYSTEM << Isa.OP_LO) | f) == Isa.SystemFn.ALL.contains(f))
    for (op <- Seq(Isa.LD_ORD, Isa.ST_ORD); ord <- 0 until 4)
      assert(Isa.isLegal((op << Isa.OP_LO) | (ord << 12)) == (ord == Isa.Ord.ACQREL || ord == Isa.Ord.SEQ),
        "a plain ordered access is a contradiction and mode 3 is reserved")
    for (op <- Isa.LOADS ++ Isa.STORES; mode <- 0 until 4)
      assert(Isa.isLegal((op << Isa.OP_LO) | (mode << 14)) == (mode <= Isa.Mode.POST))
    for (op <- Seq(Isa.LDP, Isa.STP); mode <- 0 until 4)
      assert(Isa.isLegal((op << Isa.OP_LO) | (mode << 9)) == (mode <= Isa.Mode.POST))
  }

  /** A legal representative of each primary opcode. Several opcodes are only
    * legal once their sub-function is filled in, so a bare opcode field is not
    * enough to exercise the disassembler.
    */
  private val exemplars: Seq[(Int, Int)] = {
    val a = new Assembler()
    a.add(1, 2, 3); a.shli(1, 2, 3); a.addi(1, 2, -3); a.andi(1, 2, 3); a.ori(1, 2, 3)
    a.xori(1, 2, 3); a.slti(1, 2, 3); a.sltui(1, 2, 3)
    a.movz(1, 0x1234); a.movn(1, 0x1234, 1); a.movk(1, 0x1234, 3); a.addpc(1, 16)
    a.cmpEq(0, 1, 2); a.cmpEqi(0, 1, 2); a.sel(1, 2, 3, 0); a.div(1, 2, 3)
    a.ldb(1, 2); a.ldbu(1, 2); a.ldh(1, 2); a.ldhu(1, 2); a.ldw(1, 2); a.ldwu(1, 2); a.ldd(1, 2)
    a.stb(1, 2); a.sth(1, 2); a.stw(1, 2); a.std(1, 2)
    a.ldp(1, 2, 3); a.stp(1, 2, 3)
    a.label("x"); a.b("x"); a.bl("x"); a.bp(0, "x"); a.jalr(1, 2)
    a.ldOrd(1, 2, Isa.SIZE_D, Isa.Ord.SEQ); a.stOrd(1, 2, Isa.SIZE_W, Isa.Ord.ACQREL)
    a.swp(1, 2, 3); a.fence(); a.halt()
    a.assemble().toSeq.map(w => (Isa.opcode(w), w))
  }

  test("every primary opcode has a legal exemplar and disassembles") {
    assert(exemplars.map(_._1).toSet == Isa.PRIMARY_OPCODES,
      "the exemplar program must cover every defined primary opcode exactly once")
    for ((op, w) <- exemplars) {
      assert(Isa.isLegal(w), f"exemplar for opcode 0x$op%02x is not legal")
      val text = Isa.disassemble(w, 0)
      assert(text.nonEmpty && !text.contains("illegal"), f"opcode 0x$op%02x disassembled as '$text'")
    }
    // And the reserved space really does render as illegal.
    assert(Isa.disassemble(0x3f << Isa.OP_LO).contains("illegal"))
  }

  test("every ALU, shift, condition, atomic and fence name disassembles") {
    for (fn <- Isa.Fn.ALL) assert(Isa.disassemble(Isa.encAluR(fn, 1, 2, 3)).startsWith(Isa.Fn.NAMES(fn)))
    for (fn <- Isa.ShiftFn.NAMES.keys)
      assert(Isa.disassemble(Isa.encAluShift(fn, 1, 2, 1)).startsWith(Isa.ShiftFn.NAMES(fn)))
    for (cc <- Isa.Cc.ALL) assert(Isa.disassemble(Isa.encCmpR(cc, 0, 1, 2)).contains(Isa.Cc.NAMES(cc)))
    for (fn <- Isa.AtomicFn.ALL)
      assert(Isa.disassemble(Isa.encAtomic(fn, 1, 2, 3, Isa.SIZE_D, 0)).startsWith(Isa.AtomicFn.NAMES(fn)))
    for (k <- Isa.FenceKind.ALL) assert(Isa.disassemble(Isa.encFence(k)) == Isa.FenceKind.NAMES(k))
  }

  test("a listing renders every instruction of a program") {
    val prog = Assembler(0) { a => a.li(a.a0, 0x1234); a.halt() }
    val text = Assembler.listing(prog)
    assert(text.linesIterator.size == prog.length)
    assert(!text.contains("illegal"))
  }

  // =====================================================================
  // The reference model
  // =====================================================================

  /** Byte address of the scratch data area, chosen well clear of any program
    * these tests assemble at address zero.
    */
  private val DATA = 0x800L
  private val DATA_INDEX = (DATA >> 3).toInt

  private def run(data: Map[Int, Long] = Map.empty)(body: Assembler => Unit): AxiomEmu = {
    val prog = Assembler(0)(body)
    val e = new AxiomEmu()
    e.loadProgram(prog, 0)
    e.loadData(data)
    assert(e.run(10000), "program did not stop")
    e
  }

  /** Load two operands, apply one register ALU instruction, halt. */
  private def alu(x: Long, y: Long)(op: (Assembler, Int, Int, Int) => Unit): Long = {
    val e = run() { a =>
      a.li(a.t0, x)
      a.li(a.t1, y)
      op(a, a.a0, a.t0, a.t1)
      a.halt()
    }
    assert(!e.trapped)
    e.regs(1)
  }

  test("the model implements every 64-bit ALU function") {
    assert(alu(12, 5)(_.add(_, _, _)) == 17)
    assert(alu(12, 5)(_.sub(_, _, _)) == 7)
    assert(alu(0xf0, 0x3c)(_.and(_, _, _)) == 0x30)
    assert(alu(0xf0, 0x3c)(_.or(_, _, _)) == 0xfc)
    assert(alu(0xf0, 0x3c)(_.xor(_, _, _)) == 0xcc)
    assert(alu(0xf0, 0x3c)(_.andn(_, _, _)) == 0xc0)
    assert(alu(0xf0, 0x3c)(_.orn(_, _, _)) == 0xfffffffffffffff3L)
    assert(alu(0xf0, 0x3c)(_.xnor(_, _, _)) == 0xffffffffffffff33L)
    assert(alu(1, 63)(_.shl(_, _, _)) == 0x8000000000000000L)
    assert(alu(0x8000000000000000L, 4)(_.shr(_, _, _)) == 0x0800000000000000L)
    assert(alu(0x8000000000000000L, 4)(_.sar(_, _, _)) == 0xf800000000000000L)
    assert(alu(1, 1)(_.ror(_, _, _)) == 0x8000000000000000L)
    assert(alu(-1, 1)(_.slt(_, _, _)) == 1)
    assert(alu(-1, 1)(_.sltu(_, _, _)) == 0, "minus one is the largest unsigned value")
    assert(alu(1, -1)(_.sltu(_, _, _)) == 1)
    assert(alu(0x100000001L, 3)(_.mul(_, _, _)) == 0x300000003L)
    assert(alu(0x4000000000000000L, 4)(_.mulh(_, _, _)) == 1)
    assert(alu(-1, 2)(_.mulh(_, _, _)) == -1)
    assert(alu(-1, 2)(_.mulhu(_, _, _)) == 1, "two times 2^64 - 1 has a high half of one")
    assert(alu(-1, -1)(_.mulhu(_, _, _)) == -2)
    assert(alu(-5, 3)(_.min(_, _, _)) == -5)
    assert(alu(-5, 3)(_.max(_, _, _)) == 3)
    assert(alu(-5, 3)(_.minu(_, _, _)) == 3)
    assert(alu(-5, 3)(_.maxu(_, _, _)) == -5)
    // Shift amounts come from six bits, so a shift by 64 is a shift by zero.
    assert(alu(1, 64)(_.shl(_, _, _)) == 1)
    assert(alu(1, 65)(_.shl(_, _, _)) == 2)
  }

  test("the model implements the W forms with 32-bit wrap and sign extension") {
    assert(alu(0x7fffffffL, 1)(_.addw(_, _, _)) == -2147483648L, "overflow wraps into the sign bit")
    assert(alu(0x123456789abcdef0L, 0)(_.addw(_, _, _)) == 0xffffffff9abcdef0L)
    assert(alu(0, 1)(_.subw(_, _, _)) == -1)
    assert(alu(0x10000L, 0x10000L)(_.mulw(_, _, _)) == 0, "the product's low 32 bits are zero")
    assert(alu(0x12345L, 0x10L)(_.mulw(_, _, _)) == 0x123450L)
    assert(alu(1, 31)(_.shlw(_, _, _)) == -2147483648L)
    assert(alu(0x80000000L, 4)(_.shrw(_, _, _)) == 0x08000000L)
    assert(alu(0x80000000L, 4)(_.sarw(_, _, _)) == 0xfffffffff8000000L)
    assert(alu(1, 1)(_.rorw(_, _, _)) == -2147483648L)
    // A W shift amount comes from five bits only.
    assert(alu(1, 32)(_.shlw(_, _, _)) == 1)
    assert(alu(0xffffffff12345678L, 0)(_.shlw(_, _, _)) == 0x12345678L,
      "a W form ignores the high half of its source")
  }

  test("the model implements the shift immediate forms") {
    def shift(x: Long, s: Int)(op: (Assembler, Int, Int, Int) => Unit): Long = {
      val e = run() { a => a.li(a.t0, x); op(a, a.a0, a.t0, s); a.halt() }
      e.regs(1)
    }
    assert(shift(1, 63)(_.shli(_, _, _)) == 0x8000000000000000L)
    assert(shift(-1, 60)(_.shri(_, _, _)) == 0xfL)
    assert(shift(-16, 2)(_.sari(_, _, _)) == -4)
    assert(shift(1, 1)(_.rori(_, _, _)) == 0x8000000000000000L)
    assert(shift(1, 31)(_.shlwi(_, _, _)) == -2147483648L)
    assert(shift(0x80000000L, 31)(_.shrwi(_, _, _)) == 1)
    assert(shift(0x80000000L, 31)(_.sarwi(_, _, _)) == -1)
    assert(shift(0x80000001L, 1)(_.rorwi(_, _, _)) == 0xffffffffc0000000L)
  }

  test("the model implements the immediate ALU forms") {
    val e = run() { a =>
      a.li(a.t0, 0x1234_5678L)
      a.addi(a.a0, a.t0, -0x678)
      a.andi(a.a1, a.t0, 0xff)
      a.ori(a.a2, a.t0, -1)
      a.xori(a.a3, a.t0, -1)
      a.slti(a.a4, a.t0, -1)
      a.sltui(a.a5, a.t0, -1)
      a.halt()
    }
    assert(e.regs(1) == 0x1234_5000L)
    assert(e.regs(2) == 0x78)
    assert(e.regs(3) == -1L, "an immediate of minus one is sign extended before the or")
    assert(e.regs(4) == ~0x1234_5678L)
    assert(e.regs(5) == 0)
    assert(e.regs(6) == 1, "sltui compares against the sign extended immediate read as unsigned")
  }

  test("MOVK replaces one lane and keeps the other three") {
    val e = run() { a =>
      a.li(a.a0, 0x1111222233334444L)
      a.movk(a.a0, 0xabcd, 2)
      a.movz(a.a1, 0xbeef, 3)
      a.movn(a.a2, 0x0000, 0)
      a.movn(a.a3, 0xffff, 1)
      a.halt()
    }
    assert(e.regs(1) == 0x1111abcd33334444L)
    assert(e.regs(2) == 0xbeef000000000000L)
    assert(e.regs(3) == -1L)
    assert(e.regs(4) == 0xffffffff0000ffffL)
  }

  test("ADDPC uses the address of its own instruction") {
    val e = run() { a =>
      a.nop()
      a.addpc(a.a0, 0)
      a.addpc(a.a1, -4)
      a.halt()
    }
    assert(e.regs(1) == 4)
    assert(e.regs(2) == 4)
  }

  test("all three addressing modes behave as the table says") {
    val data = Map(DATA_INDEX -> 0x1111111111111111L, DATA_INDEX + 1 -> 0x2222222222222222L)
    val e = run(data) { a =>
      a.li(a.t0, DATA)
      a.ldd(a.a0, a.t0, 8)                      // offset: base unchanged
      a.mov(a.a4, a.t0)
      a.ldd(a.a1, a.t0, 8, Isa.Mode.PRE)        // pre-index: base becomes the address
      a.mov(a.a5, a.t0)
      a.ldd(a.a2, a.t0, -8, Isa.Mode.POST)      // post-index: access at the old base
      a.mov(a.a6, a.t0)
      a.halt()
    }
    assert(e.regs(1) == 0x2222222222222222L)
    assert(e.regs(5) == DATA, "an offset form leaves the base alone")
    assert(e.regs(2) == 0x2222222222222222L)
    assert(e.regs(6) == DATA + 8)
    assert(e.regs(3) == 0x2222222222222222L, "a post-index access uses the old base")
    assert(e.regs(7) == DATA)
  }

  test("sized loads sign or zero extend and stores write only their lanes") {
    val data = Map(DATA_INDEX -> 0xfedcba9876543210L)
    val e = run(data) { a =>
      a.li(a.t0, DATA)
      a.ldb(a.a0, a.t0, 1)     // 0x32
      a.ldb(a.a1, a.t0, 7)     // 0xfe, sign extended
      a.ldbu(a.a2, a.t0, 7)
      a.ldh(a.a3, a.t0, 6)     // halfword index 3 is 0xfedc
      a.ldhu(a.a4, a.t0, 6)
      a.ldw(a.a5, a.t0, 4)     // 0xfedcba98
      a.ldwu(a.a6, a.t0, 4)
      a.halt()
    }
    assert(e.regs(1) == 0x32)
    assert(e.regs(2) == -2L)
    assert(e.regs(3) == 0xfe)
    assert(e.regs(4) == 0xfffffffffffffedcL)
    assert(e.regs(5) == 0xfedc)
    assert(e.regs(6) == 0xfffffffffedcba98L)
    assert(e.regs(7) == 0xfedcba98L)

    val s = run(Map(DATA_INDEX -> 0L)) { a =>
      a.li(a.t0, DATA)
      a.li(a.t1, -1L)
      a.stb(a.t1, a.t0, 2)
      a.sth(a.t1, a.t0, 6)     // displacements are byte counts, scaled on encoding
      a.halt()
    }
    assert(s.mem(DATA_INDEX) == 0xffff0000_00ff0000L)

    val w = run(Map(DATA_INDEX -> -1L)) { a =>
      a.li(a.t0, DATA)
      a.stw(a.zero, a.t0, 4)
      a.halt()
    }
    assert(w.mem(DATA_INDEX) == 0x00000000ffffffffL)
  }

  test("LDP and STP move two doublewords and may update the base") {
    val data = Map(DATA_INDEX -> 0xaaaaL, DATA_INDEX + 1 -> 0xbbbbL)
    val e = run(data) { a =>
      a.li(a.t0, DATA)
      a.ldp(a.a0, a.a1, a.t0, 0)
      a.li(a.t1, DATA + 0x40)
      a.stp(a.a1, a.a0, a.t1, 8, Isa.Mode.PRE)
      a.halt()
    }
    assert(e.regs(1) == 0xaaaaL && e.regs(2) == 0xbbbbL)
    assert(e.mem(DATA_INDEX + 9) == 0xbbbbL, "rt1 goes to the address")
    assert(e.mem(DATA_INDEX + 10) == 0xaaaaL, "rt2 goes eight bytes later")
    assert(e.regs(9) == DATA + 0x48, "pre-index leaves the computed address in the base")
  }

  test("compare, select and predicated branch make a control structure") {
    // Compute max(x, y) with a select and then count down with a branch, which
    // exercises both readers of a predicate.
    def maxOf(x: Long, y: Long): Long = {
      val e = run() { a =>
        a.li(a.t0, x); a.li(a.t1, y)
        a.cmpGt(a.p3, a.t0, a.t1)
        a.sel(a.a0, a.t0, a.t1, a.p3)
        a.sel(a.a1, a.t0, a.t1, a.p3, invert = true)
        a.halt()
      }
      assert(e.regs(2) == (if (x > y) y else x), "an inverted select takes the other source")
      e.regs(1)
    }
    assert(maxOf(7, 3) == 7)
    assert(maxOf(-7, 3) == 3)

    val loop = run() { a =>
      a.li(a.a0, 5)
      a.li(a.a1, 0)
      a.label("loop")
      a.add(a.a1, a.a1, a.a0)
      a.addi(a.a0, a.a0, -1)
      a.cmpNei(a.p0, a.a0, 0)
      a.bp(a.p0, "loop")
      a.halt()
    }
    assert(loop.regs(2) == 15, "5 + 4 + 3 + 2 + 1")

    // The inverted sense of BP must branch when the predicate is false.
    val inverted = run() { a =>
      a.cmpEqi(a.p1, a.zero, 1)   // false
      a.bp(a.p1, "taken", invert = true)
      a.li(a.a0, 0)
      a.halt()
      a.label("taken")
      a.li(a.a0, 1)
      a.halt()
    }
    assert(inverted.regs(1) == 1)

    for (cc <- Isa.Cc.ALL) {
      val e = run() { a =>
        a.li(a.t0, -1L); a.li(a.t1, 1L)
        a.cmp(cc, a.p0, a.t0, a.t1)
        a.li(a.a0, 0)
        a.bp(a.p0, "no")
        a.li(a.a0, 1)
        a.label("no")
        a.halt()
      }
      // -1 against 1: not equal, signed less, unsigned greater.
      val expected = cc match {
        case Isa.Cc.EQ | Isa.Cc.GE | Isa.Cc.GT | Isa.Cc.LTU | Isa.Cc.LEU => false
        case _ => true
      }
      assert(e.preds(0) == expected, s"condition ${Isa.Cc.NAMES(cc)} on -1 against 1")
      assert((e.regs(1) == 0) == expected, "the branch must agree with the predicate")
    }
  }

  test("B, BL, JALR and the call aliases transfer control") {
    val e = run() { a =>
      a.b("start")
      a.halt()
      a.label("fn")
      a.addi(a.a0, a.a0, 1)
      a.ret()
      a.label("start")
      a.li(a.a0, 10)
      a.bl("fn")
      a.bl("fn")
      a.la(a.t0, "fn")
      a.jalr(a.lr, a.t0, 0)
      // A tagged pointer must be masked rather than trapped.
      a.la(a.t1, "fn")
      a.addi(a.t1, a.t1, 3)
      a.jalr(a.lr, a.t1, 0)
      a.halt()
    }
    assert(e.regs(1) == 14)
    assert(!e.trapped)
  }

  test("ordered accesses and fences execute and are functionally plain") {
    val e = run(Map(DATA_INDEX -> 0x0123456789abcdefL)) { a =>
      a.li(a.t0, DATA)
      a.ldOrd(a.a0, a.t0, Isa.SIZE_D, Isa.Ord.SEQ)
      a.ldOrd(a.a1, a.t0, Isa.SIZE_W, Isa.Ord.ACQREL)
      a.fence()
      a.li(a.t1, 0x55)
      a.stOrd(a.t1, a.t0, Isa.SIZE_B, Isa.Ord.ACQREL)
      a.fence(Isa.FenceKind.RELEASE)
      a.halt()
    }
    assert(e.regs(1) == 0x0123456789abcdefL)
    assert(e.regs(2) == 0x89abcdefL, "a sub-doubleword ordered load zero extends")
    assert(e.mem(DATA_INDEX) == 0x0123456789abcd55L)
  }

  test("every atomic reads the old value and writes the result back") {
    def atom(initial: Long, rdIn: Long, rs: Long, size: Int = Isa.SIZE_D)
            (op: (Assembler, Int, Int, Int, Int, Int) => Unit): (Long, Long) = {
      val e = run(Map(DATA_INDEX -> initial)) { a =>
        a.li(a.t0, DATA)
        a.li(a.a0, rdIn)
        a.li(a.t1, rs)
        op(a, a.a0, a.t0, a.t1, size, Isa.Ord.PLAIN)
        a.halt()
      }
      (e.regs(1), e.mem(DATA_INDEX))
    }
    assert(atom(10, 0, 7)(_.swp(_, _, _, _, _)) == ((10L, 7L)))
    assert(atom(10, 0, 7)(_.ldadd(_, _, _, _, _)) == ((10L, 17L)))
    assert(atom(0xf0, 0, 0x3c)(_.ldand(_, _, _, _, _)) == ((0xf0L, 0x30L)))
    assert(atom(0xf0, 0, 0x3c)(_.ldor(_, _, _, _, _)) == ((0xf0L, 0xfcL)))
    assert(atom(0xf0, 0, 0x3c)(_.ldxor(_, _, _, _, _)) == ((0xf0L, 0xccL)))
    assert(atom(-5, 0, 3)(_.ldmin(_, _, _, _, _)) == ((-5L, -5L)))
    assert(atom(-5, 0, 3)(_.ldmax(_, _, _, _, _)) == ((-5L, 3L)))
    assert(atom(-5, 0, 3)(_.ldminu(_, _, _, _, _)) == ((-5L, 3L)))
    assert(atom(-5, 0, 3)(_.ldmaxu(_, _, _, _, _)) == ((-5L, -5L)))
    // A word-sized atomic touches only the low word of the doubleword.
    assert(atom(0x1111111100000005L, 0, 3, Isa.SIZE_W)(_.ldadd(_, _, _, _, _)) ==
      ((5L, 0x1111111100000008L)))
  }

  test("CAS succeeds only when the comparison matches") {
    val ok = run(Map(DATA_INDEX -> 42L)) { a =>
      a.li(a.t0, DATA); a.li(a.a0, 42); a.li(a.t1, 99)
      a.cas(a.a0, a.t0, a.t1)
      a.halt()
    }
    assert(ok.regs(1) == 42, "cas returns the old value whether or not it succeeded")
    assert(ok.mem(DATA_INDEX) == 99)

    val fail = run(Map(DATA_INDEX -> 42L)) { a =>
      a.li(a.t0, DATA); a.li(a.a0, 7); a.li(a.t1, 99)
      a.cas(a.a0, a.t0, a.t1)
      a.halt()
    }
    assert(fail.regs(1) == 42)
    assert(fail.mem(DATA_INDEX) == 42, "a failed cas leaves memory alone")
  }

  test("x0 reads as zero and discards writes") {
    val e = run() { a =>
      a.li(a.zero, -1L)
      a.add(a.a0, a.zero, a.zero)
      a.halt()
    }
    assert(e.regs(0) == 0 && e.regs(1) == 0)
  }

  // =====================================================================
  // Traps
  // =====================================================================

  test("HALT stops with no trap and does not retire") {
    val e = run() { a => a.nop(); a.nop(); a.halt() }
    assert(e.halted && !e.trapped)
    assert(e.cause == Isa.Cause.NONE)
    assert(e.retired == 2, "a halting instruction does not commit")
    assert(e.trapPc == 8)
  }

  test("an undefined opcode or sub-function raises ILLEGAL") {
    for (bad <- Seq(0x3f << Isa.OP_LO,                       // reserved primary opcode
                    (Isa.ALU_R << Isa.OP_LO) | (0x1c << 6),  // reserved ALU function
                    (Isa.CMP_R << Isa.OP_LO) | (10 << 7),    // reserved condition code
                    (Isa.SYSTEM << Isa.OP_LO) | 1,           // reserved system function
                    (Isa.LDD << Isa.OP_LO) | (3 << 14))) {   // reserved addressing mode
      val e = run() { a => a.nop(); a.word(bad); a.halt() }
      assert(e.trapped && e.cause == Isa.Cause.ILLEGAL, f"0x$bad%08x should be illegal")
      assert(e.trapPc == 4)
      assert(e.retired == 1, "a trapping instruction does not commit")
    }
  }

  test("a misaligned load or store raises the matching cause") {
    val l = run() { a => a.li(a.t0, DATA + 1); a.ldh(a.a0, a.t0, 0); a.halt() }
    assert(l.trapped && l.cause == Isa.Cause.MISALIGNED_LOAD)
    val s = run() { a => a.li(a.t0, DATA + 2); a.std(a.zero, a.t0, 0); a.halt() }
    assert(s.trapped && s.cause == Isa.Cause.MISALIGNED_STORE)
    val p = run() { a => a.li(a.t0, DATA + 4); a.ldp(a.a0, a.a1, a.t0, 0); a.halt() }
    assert(p.trapped && p.cause == Isa.Cause.MISALIGNED_LOAD)
    val o = run() { a => a.li(a.t0, DATA + 1); a.ldOrd(a.a0, a.t0, Isa.SIZE_W, Isa.Ord.SEQ); a.halt() }
    assert(o.trapped && o.cause == Isa.Cause.MISALIGNED_LOAD)
    // A byte access is always aligned, so the same address is fine.
    val b = run() { a => a.li(a.t0, DATA + 1); a.ldb(a.a0, a.t0, 0); a.halt() }
    assert(!b.trapped)
  }

  test("MISALIGNED_FETCH cannot occur because JALR masks its target") {
    val e = run() { a =>
      a.li(a.t0, 12 + 3)   // a tagged pointer into the middle of this program
      a.jalr(a.zero, a.t0, 0)
      a.halt()
      a.li(a.a0, 5)        // at byte 12
      a.halt()
    }
    assert(!e.trapped && e.regs(1) == 5)
  }
}
