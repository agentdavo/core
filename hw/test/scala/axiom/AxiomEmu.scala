package axiom

/** A reference model of Axiom-64, written straight from docs/axiom/isa.md.
  *
  * This exists to be compared against the RTL, so it is written from the
  * specification and never from the hardware. It is deliberately in a
  * different style from an implementation, a plain interpreter loop rather
  * than a pipeline, so that a misreading of the specification is unlikely to
  * appear identically in both and cancel out.
  *
  * Memory is modelled as an array of 64-bit doublewords rather than bytes.
  * That matches the natural width of the machine and of a tightly coupled
  * memory, and it makes the alignment rules visible: because every access that
  * gets as far as the memory model is naturally aligned, no access can ever
  * straddle two entries, and the lane arithmetic below never has to stitch
  * together a split. Addresses wrap within `memWords`, which must be a power
  * of two.
  */
class AxiomEmu(val memWords: Int = 4096, val resetVector: Long = 0) {

  require((memWords & (memWords - 1)) == 0, "memWords must be a power of two")

  /** Main memory, one 64-bit little-endian doubleword per entry. Byte address
    * `a` lives in entry `(a >>> 3) & (memWords - 1)` at byte lane `a & 7`.
    */
  val mem = new Array[Long](memWords)

  /** The thirty-two general registers. x0 is stored but never read or written
    * through the accessors below, so a stray write cannot be observed.
    */
  val regs = new Array[Long](Isa.REG_COUNT)

  /** The eight predicate registers. There is no hardwired-true predicate,
    * exactly as section 1.2 says.
    */
  val preds = new Array[Boolean](Isa.PRED_COUNT)

  var pc: Long    = resetVector
  var halted      = false
  var trapped     = false
  var cause       = Isa.Cause.NONE
  var trapPc: Long = 0

  /** Instructions that completed. A HALT or a trapping instruction does not
    * count, because section 6 says a trapping or halting instruction does not
    * commit. This is the same convention the CORE-32 retire counter uses, so
    * the two models can be compared against hardware the same way.
    */
  var retired = 0L

  private val indexMask = memWords - 1

  private def index(byteAddr: Long): Int = ((byteAddr >>> 3) & indexMask.toLong).toInt

  /** Place instruction words in memory. Two instructions share a doubleword,
    * the one at the lower address occupying the low half, because the machine
    * is little endian.
    */
  def loadProgram(program: Array[Int], atByte: Long = 0): Unit =
    program.zipWithIndex.foreach { case (w, i) =>
      val addr = atByte + i.toLong * 4
      val shift = 32 * (((addr >>> 2) & 1L).toInt)
      val i64 = index(addr)
      mem(i64) = (mem(i64) & ~(0xffffffffL << shift)) | ((w.toLong & 0xffffffffL) << shift)
    }

  /** Preload data, keyed by doubleword index rather than by byte address,
    * since that is the unit the model stores.
    */
  def loadData(words: Map[Int, Long]): Unit =
    words.foreach { case (i, value) => mem(i & indexMask) = value }

  // ---------------------------------------------------------------------
  // Memory
  // ---------------------------------------------------------------------

  private def fetch(byteAddr: Long): Int = {
    val shift = 32 * (((byteAddr >>> 2) & 1L).toInt)
    ((mem(index(byteAddr)) >>> shift) & 0xffffffffL).toInt
  }

  /** Read `1 << size` bytes. Only ever called for a naturally aligned address,
    * so the access lies wholly inside one doubleword.
    */
  private def readSized(byteAddr: Long, size: Int): Long = {
    val word = mem(index(byteAddr))
    if (size == Isa.SIZE_D) word
    else {
      val shift = 8 * ((byteAddr & 7L).toInt)
      val mask = (1L << (8 * Isa.sizeBytes(size))) - 1
      (word >>> shift) & mask
    }
  }

  private def writeSized(byteAddr: Long, size: Int, value: Long): Unit = {
    val i = index(byteAddr)
    if (size == Isa.SIZE_D) mem(i) = value
    else {
      val shift = 8 * ((byteAddr & 7L).toInt)
      val mask = (1L << (8 * Isa.sizeBytes(size))) - 1
      mem(i) = (mem(i) & ~(mask << shift)) | ((value & mask) << shift)
    }
  }

  /** Sign extend a value that occupies the low `1 << size` bytes. */
  private def signExtend(value: Long, size: Int): Long = {
    val bits = 8 * Isa.sizeBytes(size)
    if (bits == 64) value else (value << (64 - bits)) >> (64 - bits)
  }

  private def misaligned(byteAddr: Long, size: Int): Boolean =
    (byteAddr & (Isa.sizeBytes(size) - 1).toLong) != 0

  // ---------------------------------------------------------------------
  // Registers
  // ---------------------------------------------------------------------

  private def read(r: Int): Long = if (r == 0) 0L else regs(r)

  private def write(r: Int, value: Long): Unit = if (r != 0) regs(r) = value

  private def stop(c: Int, at: Long): Unit = {
    halted = true
    trapped = c != Isa.Cause.NONE
    cause = c
    trapPc = at
  }

  // ---------------------------------------------------------------------
  // Arithmetic
  // ---------------------------------------------------------------------

  /** The full ALU function table of section 2.2.
    *
    * The W forms compute in 32 bits and sign extend, which is what stops every
    * C `int` expression costing a pair of shifts. Their shift amount comes
    * from five bits and the 64-bit forms take six, so a W shift by 32 is a
    * shift by zero rather than a shift that clears the register.
    */
  private def alu(fn: Int, x: Long, y: Long): Long = fn match {
    case Isa.Fn.ADD   => x + y
    case Isa.Fn.SUB   => x - y
    case Isa.Fn.AND   => x & y
    case Isa.Fn.OR    => x | y
    case Isa.Fn.XOR   => x ^ y
    case Isa.Fn.ANDN  => x & ~y
    case Isa.Fn.ORN   => x | ~y
    case Isa.Fn.XNOR  => ~(x ^ y)
    case Isa.Fn.SHL   => x << (y & 63)
    case Isa.Fn.SHR   => x >>> (y & 63)
    case Isa.Fn.SAR   => x >> (y & 63)
    case Isa.Fn.ROR   => java.lang.Long.rotateRight(x, (y & 63).toInt)
    case Isa.Fn.SLT   => if (x < y) 1L else 0L
    case Isa.Fn.SLTU  => if (java.lang.Long.compareUnsigned(x, y) < 0) 1L else 0L
    case Isa.Fn.MUL   => x * y
    case Isa.Fn.MULH  => java.lang.Math.multiplyHigh(x, y)
    // The unsigned high half is the signed one corrected by the two sign
    // terms: treating a negative operand as unsigned adds 2^64 times the other
    // operand, which lands entirely in the high half.
    case Isa.Fn.MULHU => java.lang.Math.multiplyHigh(x, y) + ((x >> 63) & y) + ((y >> 63) & x)
    case Isa.Fn.ADDW  => (x.toInt + y.toInt).toLong
    case Isa.Fn.SUBW  => (x.toInt - y.toInt).toLong
    case Isa.Fn.MULW  => (x.toInt * y.toInt).toLong
    case Isa.Fn.SHLW  => (x.toInt << (y & 31).toInt).toLong
    case Isa.Fn.SHRW  => (x.toInt >>> (y & 31).toInt).toLong
    case Isa.Fn.SARW  => (x.toInt >> (y & 31).toInt).toLong
    case Isa.Fn.RORW  => Integer.rotateRight(x.toInt, (y & 31).toInt).toLong
    case Isa.Fn.MIN   => if (x < y) x else y
    case Isa.Fn.MAX   => if (x > y) x else y
    case Isa.Fn.MINU  => if (java.lang.Long.compareUnsigned(x, y) < 0) x else y
    case Isa.Fn.MAXU  => if (java.lang.Long.compareUnsigned(x, y) > 0) x else y
    case other        => throw new IllegalStateException(f"no ALU function 0x$other%02x")
  }

  /** The shift-immediate sub-functions reuse the register shift semantics, so
    * the two cannot disagree about what a rotate by zero does.
    */
  private def shiftImm(fn: Int, x: Long, shamt: Int): Long = fn match {
    case Isa.ShiftFn.SHL  => alu(Isa.Fn.SHL, x, shamt.toLong)
    case Isa.ShiftFn.SHR  => alu(Isa.Fn.SHR, x, shamt.toLong)
    case Isa.ShiftFn.SAR  => alu(Isa.Fn.SAR, x, shamt.toLong)
    case Isa.ShiftFn.ROR  => alu(Isa.Fn.ROR, x, shamt.toLong)
    case Isa.ShiftFn.SHLW => alu(Isa.Fn.SHLW, x, shamt.toLong)
    case Isa.ShiftFn.SHRW => alu(Isa.Fn.SHRW, x, shamt.toLong)
    case Isa.ShiftFn.SARW => alu(Isa.Fn.SARW, x, shamt.toLong)
    case Isa.ShiftFn.RORW => alu(Isa.Fn.RORW, x, shamt.toLong)
    case other            => throw new IllegalStateException(s"no shift function $other")
  }

  /** Condition codes of section 2.4. */
  private def compare(cc: Int, x: Long, y: Long): Boolean = cc match {
    case Isa.Cc.EQ  => x == y
    case Isa.Cc.NE  => x != y
    case Isa.Cc.LT  => x < y
    case Isa.Cc.GE  => x >= y
    case Isa.Cc.LTU => java.lang.Long.compareUnsigned(x, y) < 0
    case Isa.Cc.GEU => java.lang.Long.compareUnsigned(x, y) >= 0
    case Isa.Cc.LE  => x <= y
    case Isa.Cc.GT  => x > y
    case Isa.Cc.LEU => java.lang.Long.compareUnsigned(x, y) <= 0
    case Isa.Cc.GTU => java.lang.Long.compareUnsigned(x, y) > 0
    case other      => throw new IllegalStateException(s"no condition code $other")
  }

  // ---------------------------------------------------------------------
  // Execution
  // ---------------------------------------------------------------------

  /** Execute one instruction. Does nothing once halted or trapped. */
  def step(): Unit = {
    if (halted) return

    val here  = pc
    val instr = fetch(here)

    // Both the primary opcode and the sub-function have to be defined.
    // Reserved space traps rather than aliasing, so that adding an instruction
    // later cannot silently change the meaning of an old binary.
    if (!Isa.isLegal(instr)) { stop(Isa.Cause.ILLEGAL, here); return }

    val op = Isa.opcode(instr)
    val rd = Isa.rd(instr)
    val rn = Isa.rn(instr)
    val rm = Isa.rm(instr)

    var next = here + 4

    /** A single-register load or store. The base update of an indexed form
      * happens after the access, which is the only order in which a post-index
      * load can be described at all.
      */
    def access(size: Int, isLoad: Boolean, signed: Boolean): Unit = {
      val mode = Isa.memMode(instr)
      val base = read(rn)
      val disp = Isa.memImm(instr).toLong * Isa.sizeBytes(size)
      val address = if (mode == Isa.Mode.POST) base else base + disp
      if (misaligned(address, size)) {
        stop(if (isLoad) Isa.Cause.MISALIGNED_LOAD else Isa.Cause.MISALIGNED_STORE, here)
      } else {
        if (isLoad) {
          val raw = readSized(address, size)
          write(rd, if (signed) signExtend(raw, size) else raw)
        } else {
          // Stores take their data from the rd field.
          writeSized(address, size, read(rd))
        }
        if (mode != Isa.Mode.OFFSET) write(rn, base + disp)
      }
    }

    def pair(isLoad: Boolean): Unit = {
      val mode = Isa.pairMode(instr)
      val rt2 = Isa.pairRt2(instr)
      val base = read(rn)
      val disp = Isa.pairImm(instr).toLong * 8
      val address = if (mode == Isa.Mode.POST) base else base + disp
      if (misaligned(address, Isa.SIZE_D)) {
        stop(if (isLoad) Isa.Cause.MISALIGNED_LOAD else Isa.Cause.MISALIGNED_STORE, here)
      } else {
        if (isLoad) {
          write(rd, readSized(address, Isa.SIZE_D))
          write(rt2, readSized(address + 8, Isa.SIZE_D))
        } else {
          writeSized(address, Isa.SIZE_D, read(rd))
          writeSized(address + 8, Isa.SIZE_D, read(rt2))
        }
        if (mode != Isa.Mode.OFFSET) write(rn, base + disp)
      }
    }

    /** An atomic read-modify-write. On a single-threaded model the ordering
      * annotation has no observable effect, but it still has to decode, and a
      * word-sized atomic still has to sign extend its result the way every
      * other 32-bit operation in this architecture does.
      */
    def atomic(): Unit = {
      val size = Isa.atomicSize(instr)
      val fn = Isa.atomicFn(instr)
      val address = read(rn)
      if (misaligned(address, size)) { stop(Isa.Cause.MISALIGNED_LOAD, here); return }
      val old = signExtend(readSized(address, size), size)
      val s = if (size == Isa.SIZE_W) signExtend(read(rm), size) else read(rm)
      val compareValue = if (size == Isa.SIZE_W) signExtend(read(rd), size) else read(rd)
      val stored: Option[Long] = fn match {
        case Isa.AtomicFn.SWP  => Some(s)
        case Isa.AtomicFn.ADD  => Some(old + s)
        case Isa.AtomicFn.AND  => Some(old & s)
        case Isa.AtomicFn.OR   => Some(old | s)
        case Isa.AtomicFn.XOR  => Some(old ^ s)
        case Isa.AtomicFn.CAS  => if (compareValue == old) Some(s) else None
        case Isa.AtomicFn.MIN  => Some(if (old < s) old else s)
        case Isa.AtomicFn.MAX  => Some(if (old > s) old else s)
        case Isa.AtomicFn.MINU => Some(if (java.lang.Long.compareUnsigned(old, s) < 0) old else s)
        case Isa.AtomicFn.MAXU => Some(if (java.lang.Long.compareUnsigned(old, s) > 0) old else s)
        case other             => throw new IllegalStateException(s"no atomic function $other")
      }
      stored.foreach(v => writeSized(address, size, v))
      write(rd, old)
    }

    op match {
      // -- arithmetic ---------------------------------------------------
      case Isa.ALU_R     => write(rd, alu(Isa.aluFn(instr), read(rn), read(rm)))
      case Isa.ALU_SHIFT => write(rd, shiftImm(Isa.shiftFn(instr), read(rn), Isa.shiftAmount(instr)))
      case Isa.ADDI      => write(rd, read(rn) + Isa.imm16s(instr).toLong)
      case Isa.ANDI      => write(rd, read(rn) & Isa.imm16s(instr).toLong)
      case Isa.ORI       => write(rd, read(rn) | Isa.imm16s(instr).toLong)
      case Isa.XORI      => write(rd, read(rn) ^ Isa.imm16s(instr).toLong)
      case Isa.SLTI      => write(rd, if (read(rn) < Isa.imm16s(instr).toLong) 1L else 0L)
      // The immediate is sign extended first and only then read as unsigned,
      // so sltui rd, rn, -1 compares against 0xffffffffffffffff.
      case Isa.SLTUI     =>
        write(rd, if (java.lang.Long.compareUnsigned(read(rn), Isa.imm16s(instr).toLong) < 0) 1L else 0L)

      // -- constant formation -------------------------------------------
      case Isa.MOVZ => write(rd, Isa.imm16(instr).toLong << (16 * Isa.movShift(instr)))
      case Isa.MOVN => write(rd, ~(Isa.imm16(instr).toLong << (16 * Isa.movShift(instr))))
      case Isa.MOVK =>
        // Only the addressed lane changes; the other three keep their value,
        // which is the whole point of having MOVK as well as MOVZ.
        val shift = 16 * Isa.movShift(instr)
        val cleared = read(rd) & ~(0xffffL << shift)
        write(rd, cleared | (Isa.imm16(instr).toLong << shift))

      // ADDPC reads the address of this instruction, not the next one.
      case Isa.ADDPC => write(rd, here + Isa.imm21(instr).toLong)

      // -- compare and select -------------------------------------------
      case Isa.CMP_R => preds(Isa.pd(instr)) = compare(Isa.ccR(instr), read(rn), read(rm))
      case Isa.CMP_I => preds(Isa.pd(instr)) = compare(Isa.ccI(instr), read(rn), Isa.imm12(instr).toLong)
      case Isa.SEL =>
        val taken = preds(Isa.selP(instr)) ^ Isa.selInvert(instr)
        write(rd, if (taken) read(rn) else read(rm))

      // -- memory ---------------------------------------------------------
      case Isa.LDB  => access(Isa.SIZE_B, isLoad = true, signed = true)
      case Isa.LDBU => access(Isa.SIZE_B, isLoad = true, signed = false)
      case Isa.LDH  => access(Isa.SIZE_H, isLoad = true, signed = true)
      case Isa.LDHU => access(Isa.SIZE_H, isLoad = true, signed = false)
      case Isa.LDW  => access(Isa.SIZE_W, isLoad = true, signed = true)
      case Isa.LDWU => access(Isa.SIZE_W, isLoad = true, signed = false)
      case Isa.LDD  => access(Isa.SIZE_D, isLoad = true, signed = false)
      case Isa.STB  => access(Isa.SIZE_B, isLoad = false, signed = false)
      case Isa.STH  => access(Isa.SIZE_H, isLoad = false, signed = false)
      case Isa.STW  => access(Isa.SIZE_W, isLoad = false, signed = false)
      case Isa.STD  => access(Isa.SIZE_D, isLoad = false, signed = false)
      case Isa.LDP  => pair(isLoad = true)
      case Isa.STP  => pair(isLoad = false)

      // -- control --------------------------------------------------------
      case Isa.B  => next = here + Isa.off26(instr).toLong
      case Isa.BL =>
        write(Isa.LR, here + 4)
        next = here + Isa.off26(instr).toLong
      case Isa.BP =>
        if (preds(Isa.bpPred(instr)) ^ Isa.bpInvert(instr)) next = here + Isa.off22(instr).toLong
      case Isa.JALR =>
        // rn is read before rd is written, so jalr lr, lr works. The low two
        // bits are masked rather than trapped, which is what makes returning
        // through a tagged pointer safe.
        val t = (read(rn) + Isa.imm16s(instr).toLong) & ~3L
        write(rd, here + 4)
        next = t

      // -- ordered accesses, atomics, system -------------------------------
      // On one thread an ordered access behaves exactly like a plain one; it
      // still has to decode and to trap on a misaligned address. The encoding
      // carries a size but no sign bit, and section 4 describes these as
      // touching a lock or a flag, so a sub-doubleword ordered load is taken
      // to zero extend. That is an assumption, not something the text states.
      case Isa.LD_ORD =>
        val size = Isa.ordSize(instr)
        val address = read(rn)
        if (misaligned(address, size)) stop(Isa.Cause.MISALIGNED_LOAD, here)
        else write(rd, readSized(address, size))
      case Isa.ST_ORD =>
        val size = Isa.ordSize(instr)
        val address = read(rn)
        if (misaligned(address, size)) stop(Isa.Cause.MISALIGNED_STORE, here)
        else writeSized(address, size, read(rd))
      case Isa.ATOMIC => atomic()
      case Isa.FENCE  => () // nothing to order on a single-threaded model
      case Isa.SYSTEM => stop(Isa.Cause.NONE, here)

      case other => throw new IllegalStateException(f"unhandled opcode 0x$other%02x")
    }

    if (!halted) {
      retired += 1
      pc = next
    }
  }

  /** Run until the machine halts or `maxSteps` instructions have executed.
    * Returns true if it halted.
    */
  def run(maxSteps: Int = 1000000): Boolean = {
    var steps = 0
    while (!halted && steps < maxSteps) { step(); steps += 1 }
    halted
  }

  def registerSnapshot: Array[Long] = regs.clone()
}
