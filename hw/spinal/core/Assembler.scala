package core

import scala.collection.mutable

/** A small two-pass assembler for CORE-32.
  *
  * Programs are written as ordinary Scala, which means test programs get
  * loops, constants and comments from the host language without a separate
  * toolchain having to exist first. Labels are plain strings and may be
  * referenced before they are defined.
  *
  * {{{
  *   val prog = Assembler(0x0) { a => import a._
  *     li(a0, 10)
  *     mov(a1, zero)
  *     label("loop")
  *     add(a1, a1, a0)
  *     addi(a0, a0, -1)
  *     bnez(a0, "loop")
  *     halt()
  *   }
  * }}}
  */
class Assembler(val baseAddress: Int = 0) {
  import Isa._

  private val words  = mutable.ArrayBuffer[(Int, Map[String, Int]) => Int]()
  private val labels = mutable.LinkedHashMap[String, Int]()

  // -- ABI register names, so programs read like assembly ------------------
  val zero = 0; val a0 = 1;  val a1 = 2;  val a2 = 3;  val a3 = 4
  val t0   = 5; val t1 = 6;  val t2 = 7
  val s0   = 8; val s1 = 9;  val s2 = 10; val s3 = 11
  val gp   = 12; val fp = 13; val sp = 14; val lr = 15

  /** Address of the next instruction to be emitted. */
  def pc: Int = baseAddress + words.length * 4

  /** Define a label at the current address. */
  def label(name: String): Unit = {
    require(!labels.contains(name), s"duplicate label '$name'")
    labels(name) = pc
  }

  /** Emit a fully resolved instruction word. */
  def word(value: Int): Unit = words += ((_, _) => value)

  /** Emit an instruction whose encoding depends on its own address and on
    * labels that may not be defined yet.
    */
  private def deferred(f: (Int, Map[String, Int]) => Int): Unit = words += f

  private def target(name: String, labelMap: Map[String, Int]): Int =
    labelMap.getOrElse(name, throw new NoSuchElementException(s"undefined label '$name'"))

  /** Resolve every instruction. Safe to call more than once. */
  def assemble(): Array[Int] = {
    val map = labels.toMap
    words.zipWithIndex.map { case (f, i) => f(baseAddress + i * 4, map) }.toArray
  }

  /** The resolved label table, handy for pointing a test at a routine. */
  def symbols: Map[String, Int] = labels.toMap

  def sizeBytes: Int = words.length * 4

  // -- Class 0: register ALU ----------------------------------------------
  def add(rd: Int, ra: Int, rb: Int): Unit   = word(encRRR(ADD, rd, ra, rb))
  def sub(rd: Int, ra: Int, rb: Int): Unit   = word(encRRR(SUB, rd, ra, rb))
  def and(rd: Int, ra: Int, rb: Int): Unit   = word(encRRR(AND, rd, ra, rb))
  def or(rd: Int, ra: Int, rb: Int): Unit    = word(encRRR(OR, rd, ra, rb))
  def xor(rd: Int, ra: Int, rb: Int): Unit   = word(encRRR(XOR, rd, ra, rb))
  def shl(rd: Int, ra: Int, rb: Int): Unit   = word(encRRR(SHL, rd, ra, rb))
  def shr(rd: Int, ra: Int, rb: Int): Unit   = word(encRRR(SHR, rd, ra, rb))
  def sar(rd: Int, ra: Int, rb: Int): Unit   = word(encRRR(SAR, rd, ra, rb))
  def slt(rd: Int, ra: Int, rb: Int): Unit   = word(encRRR(SLT, rd, ra, rb))
  def sltu(rd: Int, ra: Int, rb: Int): Unit  = word(encRRR(SLTU, rd, ra, rb))
  def mul(rd: Int, ra: Int, rb: Int): Unit   = word(encRRR(MUL, rd, ra, rb))
  def mulh(rd: Int, ra: Int, rb: Int): Unit  = word(encRRR(MULH, rd, ra, rb))
  def mulhu(rd: Int, ra: Int, rb: Int): Unit = word(encRRR(MULHU, rd, ra, rb))
  def seq(rd: Int, ra: Int, rb: Int): Unit   = word(encRRR(SEQ, rd, ra, rb))
  def sne(rd: Int, ra: Int, rb: Int): Unit   = word(encRRR(SNE, rd, ra, rb))
  def ror(rd: Int, ra: Int, rb: Int): Unit   = word(encRRR(ROR, rd, ra, rb))

  // -- Class 1: immediate ALU and constant formation ----------------------
  def addi(rd: Int, ra: Int, imm: Int): Unit  = word(encRRI(ADDI, rd, ra, imm))
  def andi(rd: Int, ra: Int, imm: Int): Unit  = word(encRRI(ANDI, rd, ra, imm))
  def ori(rd: Int, ra: Int, imm: Int): Unit   = word(encRRI(ORI, rd, ra, imm))
  def xori(rd: Int, ra: Int, imm: Int): Unit  = word(encRRI(XORI, rd, ra, imm))
  def shli(rd: Int, ra: Int, imm: Int): Unit  = word(encRRI(SHLI, rd, ra, imm))
  def shri(rd: Int, ra: Int, imm: Int): Unit  = word(encRRI(SHRI, rd, ra, imm))
  def sari(rd: Int, ra: Int, imm: Int): Unit  = word(encRRI(SARI, rd, ra, imm))
  def slti(rd: Int, ra: Int, imm: Int): Unit  = word(encRRI(SLTI, rd, ra, imm))
  def sltui(rd: Int, ra: Int, imm: Int): Unit = word(encRRI(SLTUI, rd, ra, imm))
  def seqi(rd: Int, ra: Int, imm: Int): Unit  = word(encRRI(SEQI, rd, ra, imm))
  def snei(rd: Int, ra: Int, imm: Int): Unit  = word(encRRI(SNEI, rd, ra, imm))
  def rori(rd: Int, ra: Int, imm: Int): Unit  = word(encRRI(RORI, rd, ra, imm))
  def movi(rd: Int, imm: Int): Unit           = word(encRI(MOVI, rd, imm))
  def movhi(rd: Int, imm: Int): Unit          = word(encRIField(MOVHI, rd, imm))
  def addpc(rd: Int, imm: Int): Unit          = word(encRI(ADDPC, rd, imm))

  // -- Class 2: memory and system -----------------------------------------
  def ldw(rd: Int, ra: Int, off: Int = 0): Unit  = word(encRRI(LDW, rd, ra, off))
  def ldh(rd: Int, ra: Int, off: Int = 0): Unit  = word(encRRI(LDH, rd, ra, off))
  def ldhu(rd: Int, ra: Int, off: Int = 0): Unit = word(encRRI(LDHU, rd, ra, off))
  def ldb(rd: Int, ra: Int, off: Int = 0): Unit  = word(encRRI(LDB, rd, ra, off))
  def ldbu(rd: Int, ra: Int, off: Int = 0): Unit = word(encRRI(LDBU, rd, ra, off))
  def stw(rs: Int, ra: Int, off: Int = 0): Unit  = word(encRRI(STW, rs, ra, off))
  def sth(rs: Int, ra: Int, off: Int = 0): Unit  = word(encRRI(STH, rs, ra, off))
  def stb(rs: Int, ra: Int, off: Int = 0): Unit  = word(encRRI(STB, rs, ra, off))
  def halt(): Unit                               = word(HALT << OP_LO)

  // -- Class 3: control transfer ------------------------------------------
  private def branch(op: Int, ra: Int, rb: Int, name: String): Unit =
    deferred((at, map) => encBR(op, ra, rb, target(name, map) - at))

  def beq(ra: Int, rb: Int, name: String): Unit  = branch(BEQ, ra, rb, name)
  def bne(ra: Int, rb: Int, name: String): Unit  = branch(BNE, ra, rb, name)
  def blt(ra: Int, rb: Int, name: String): Unit  = branch(BLT, ra, rb, name)
  def bge(ra: Int, rb: Int, name: String): Unit  = branch(BGE, ra, rb, name)
  def bltu(ra: Int, rb: Int, name: String): Unit = branch(BLTU, ra, rb, name)
  def bgeu(ra: Int, rb: Int, name: String): Unit = branch(BGEU, ra, rb, name)

  def jmp(name: String): Unit  = deferred((at, map) => encJ(JMP, target(name, map) - at))
  def call(name: String): Unit = deferred((at, map) => encJ(CALL, target(name, map) - at))
  def jmpr(ra: Int, off: Int = 0): Unit            = word(encRRI(JMPR, 0, ra, off))
  def callr(rd: Int, ra: Int, off: Int = 0): Unit  = word(encRRI(CALLR, rd, ra, off))

  // -- Assembler aliases ---------------------------------------------------
  def nop(): Unit                            = add(zero, zero, zero)
  def mov(rd: Int, ra: Int): Unit            = add(rd, ra, zero)
  def neg(rd: Int, ra: Int): Unit            = sub(rd, zero, ra)
  def not(rd: Int, ra: Int): Unit            = xori(rd, ra, -1)
  def ret(): Unit                            = jmpr(lr, 0)
  def beqz(ra: Int, name: String): Unit      = beq(ra, zero, name)
  def bnez(ra: Int, name: String): Unit      = bne(ra, zero, name)
  def bgt(ra: Int, rb: Int, name: String): Unit  = blt(rb, ra, name)
  def ble(ra: Int, rb: Int, name: String): Unit  = bge(rb, ra, name)
  def bgtu(ra: Int, rb: Int, name: String): Unit = bltu(rb, ra, name)
  def bleu(ra: Int, rb: Int, name: String): Unit = bgeu(rb, ra, name)

  /** Load the address of a label into a register.
    *
    * Always two instructions, whatever the address turns out to be, so that
    * adding one does not shift the labels that follow it.
    */
  def la(rd: Int, name: String): Unit = {
    deferred((_, map) => Isa.encRI(MOVI, rd, target(name, map) & 0x3ff))
    deferred((_, map) => Isa.encRIField(MOVHI, rd, (target(name, map) >>> 10) & 0x3fffff))
  }

  /** Load any 32-bit constant, using one instruction when it fits. */
  def li(rd: Int, value: Int): Unit = {
    if (value >= -(1 << 21) && value < (1 << 21)) {
      movi(rd, value)
    } else {
      movi(rd, value & 0x3ff)
      movhi(rd, (value >>> 10) & 0x3fffff)
    }
  }

  /** Number of instructions `li` would emit for this value. */
  def liSize(value: Int): Int = if (value >= -(1 << 21) && value < (1 << 21)) 1 else 2
}

object Assembler {

  /** Build a program with the assembler in scope. */
  def apply(baseAddress: Int = 0)(body: Assembler => Unit): Array[Int] = {
    val a = new Assembler(baseAddress)
    body(a)
    a.assemble()
  }

  /** Build a program and keep the assembler, for access to the label table. */
  def build(baseAddress: Int = 0)(body: Assembler => Unit): Assembler = {
    val a = new Assembler(baseAddress)
    body(a)
    a
  }

  /** Render a program as an annotated listing. */
  def listing(program: Array[Int], baseAddress: Int = 0): String =
    program.zipWithIndex.map { case (w, i) =>
      val at = baseAddress + i * 4
      f"$at%08x: $w%08x    ${Isa.disassemble(w, at)}"
    }.mkString("\n")
}
