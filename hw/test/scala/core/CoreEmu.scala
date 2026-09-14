package core

/** A reference model of CORE-32, written straight from docs/isa.md.
  *
  * This exists to be compared against the RTL. It is deliberately written in a
  * different style from the hardware — a plain interpreter loop rather than a
  * pipeline — so that a misreading of the specification is unlikely to appear
  * identically in both.
  *
  * Memory wraps within `memWords` exactly as the tightly coupled memory in
  * CoreSoc does.
  */
class CoreEmu(val memWords: Int = 4096, val resetVector: Int = 0) {

  val mem  = new Array[Int](memWords)
  val regs = new Array[Int](Isa.REG_COUNT)

  var pc      = resetVector
  var halted  = false
  var trapped = false
  var cause   = Isa.Cause.NONE
  var trapPc  = 0

  /** Instructions that completed. A HALT or a trapping instruction does not
    * count, matching the retire counter in the hardware.
    */
  var retired = 0L

  private val indexMask = memWords - 1

  def loadProgram(program: Array[Int], atByte: Int = 0): Unit =
    program.zipWithIndex.foreach { case (w, i) => mem(((atByte >>> 2) + i) & indexMask) = w }

  def loadData(words: Map[Int, Int]): Unit =
    words.foreach { case (index, value) => mem(index & indexMask) = value }

  // ---------------------------------------------------------------------
  // Memory
  // ---------------------------------------------------------------------

  private def wordIndex(byteAddr: Int): Int = (byteAddr >>> 2) & indexMask

  def readWord(byteAddr: Int): Int = mem(wordIndex(byteAddr))

  private def readHalf(byteAddr: Int): Int =
    (mem(wordIndex(byteAddr)) >>> (16 * ((byteAddr >>> 1) & 1))) & 0xffff

  private def readByte(byteAddr: Int): Int =
    (mem(wordIndex(byteAddr)) >>> (8 * (byteAddr & 3))) & 0xff

  private def writeWord(byteAddr: Int, value: Int): Unit =
    mem(wordIndex(byteAddr)) = value

  private def writeHalf(byteAddr: Int, value: Int): Unit = {
    val i = wordIndex(byteAddr)
    val shift = 16 * ((byteAddr >>> 1) & 1)
    mem(i) = (mem(i) & ~(0xffff << shift)) | ((value & 0xffff) << shift)
  }

  private def writeByte(byteAddr: Int, value: Int): Unit = {
    val i = wordIndex(byteAddr)
    val shift = 8 * (byteAddr & 3)
    mem(i) = (mem(i) & ~(0xff << shift)) | ((value & 0xff) << shift)
  }

  // ---------------------------------------------------------------------
  // Registers
  // ---------------------------------------------------------------------

  private def read(r: Int): Int = if (r == 0) 0 else regs(r)

  private def write(r: Int, value: Int): Unit = if (r != 0) regs(r) = value

  private def stop(c: Int, at: Int): Unit = {
    halted = true
    trapped = c != Isa.Cause.NONE
    cause = c
    trapPc = at
  }

  // ---------------------------------------------------------------------
  // Execution
  // ---------------------------------------------------------------------

  /** Execute one instruction. Does nothing once halted. */
  def step(): Unit = {
    if (halted) return

    val here  = pc
    val instr = readWord(here)
    val op    = Isa.opcode(instr)

    if (!Isa.isLegal(instr)) { stop(Isa.Cause.ILLEGAL, here); return }

    val rd  = Isa.rd(instr)
    val ra  = Isa.ra(instr)
    val rb  = Isa.rb(instr)
    val a   = read(ra)
    val b   = read(rb)
    val imm = Isa.imm18(instr)

    var next = here + 4

    def alu(x: Int, y: Int, fn: Int): Int = fn match {
      case Isa.Fn.ADD   => x + y
      case Isa.Fn.SUB   => x - y
      case Isa.Fn.AND   => x & y
      case Isa.Fn.OR    => x | y
      case Isa.Fn.XOR   => x ^ y
      case Isa.Fn.SHL   => x << (y & 31)
      case Isa.Fn.SHR   => x >>> (y & 31)
      case Isa.Fn.SAR   => x >> (y & 31)
      case Isa.Fn.SLT   => if (x < y) 1 else 0
      case Isa.Fn.SLTU  => if (Integer.compareUnsigned(x, y) < 0) 1 else 0
      case Isa.Fn.MUL   => x * y
      case Isa.Fn.MULH  => ((x.toLong * y.toLong) >> 32).toInt
      case Isa.Fn.MULHU => (((x.toLong & 0xffffffffL) * (y.toLong & 0xffffffffL)) >>> 32).toInt
      case Isa.Fn.SEQ   => if (x == y) 1 else 0
      case Isa.Fn.SNE   => if (x != y) 1 else 0
      case Isa.Fn.ROR   => Integer.rotateRight(x, y & 31)
      case other        => throw new IllegalStateException(s"no ALU function $other")
    }

    def loadOp(size: Int, signed: Boolean): Unit = {
      val address = a + imm
      val bad = (size == Isa.SIZE_W && (address & 3) != 0) || (size == Isa.SIZE_H && (address & 1) != 0)
      if (bad) { stop(Isa.Cause.MISALIGNED_LOAD, here) }
      else {
        val value = size match {
          case Isa.SIZE_W => readWord(address)
          case Isa.SIZE_H => if (signed) (readHalf(address) << 16) >> 16 else readHalf(address)
          case _          => if (signed) (readByte(address) << 24) >> 24 else readByte(address)
        }
        write(rd, value)
      }
    }

    def storeOp(size: Int): Unit = {
      val address = a + imm
      val bad = (size == Isa.SIZE_W && (address & 3) != 0) || (size == Isa.SIZE_H && (address & 1) != 0)
      if (bad) { stop(Isa.Cause.MISALIGNED_STORE, here) }
      else {
        val value = read(rd) // stores take their data from the rd field
        size match {
          case Isa.SIZE_W => writeWord(address, value)
          case Isa.SIZE_H => writeHalf(address, value)
          case _          => writeByte(address, value)
        }
      }
    }

    def branchIf(taken: Boolean): Unit = if (taken) next = here + Isa.off14(instr)

    op match {
      // class 0: register ALU
      case o if o >= 0x00 && o <= 0x0f => write(rd, alu(a, b, o))

      // class 1: immediate ALU and constant formation
      case Isa.MOVI  => write(rd, Isa.imm22(instr))
      case Isa.MOVHI => write(rd, (Isa.imm22(instr) << 10) | (read(rd) & 0x3ff))
      case Isa.ADDPC => write(rd, here + Isa.imm22(instr))
      case o if o >= 0x10 && o <= 0x1f => write(rd, alu(a, imm, o & 0xf))

      // class 2: memory and system
      case Isa.LDW  => loadOp(Isa.SIZE_W, signed = false)
      case Isa.LDH  => loadOp(Isa.SIZE_H, signed = true)
      case Isa.LDHU => loadOp(Isa.SIZE_H, signed = false)
      case Isa.LDB  => loadOp(Isa.SIZE_B, signed = true)
      case Isa.LDBU => loadOp(Isa.SIZE_B, signed = false)
      case Isa.STW  => storeOp(Isa.SIZE_W)
      case Isa.STH  => storeOp(Isa.SIZE_H)
      case Isa.STB  => storeOp(Isa.SIZE_B)
      case Isa.HALT => stop(Isa.Cause.NONE, here)

      // class 3: control transfer
      case Isa.BEQ  => branchIf(a == b)
      case Isa.BNE  => branchIf(a != b)
      case Isa.BLT  => branchIf(a < b)
      case Isa.BGE  => branchIf(a >= b)
      case Isa.BLTU => branchIf(Integer.compareUnsigned(a, b) < 0)
      case Isa.BGEU => branchIf(Integer.compareUnsigned(a, b) >= 0)
      case Isa.JMP  => next = here + Isa.off26(instr)
      case Isa.CALL => write(Isa.LR, here + 4); next = here + Isa.off26(instr)
      case Isa.JMPR => next = (a + imm) & ~3
      case Isa.CALLR =>
        val target = (a + imm) & ~3
        write(rd, here + 4)
        next = target

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

  def registerSnapshot: Array[Int] = regs.clone()
}
