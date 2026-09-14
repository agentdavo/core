package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib.misc.pipeline._
import scala.collection.mutable.ArrayBuffer

/** Decodes the parts of an instruction that every plugin agrees on, and routes
  * each instruction to the unit that claimed it.
  *
  * The division of labour is deliberate. This plugin knows the *encoding*: it
  * knows that the opcode is always in the same six bits, that register fields
  * never move, and which instructions read or write which register. Execution
  * plugins know their own *sub-fields* and slice them out of the raw
  * instruction themselves. Neither knows anything about the other.
  *
  * That split is only affordable because the encoding is flat. There is no
  * secondary function field to look up, so this stays a handful of comparators
  * rather than a microcode table.
  */
class DecoderPlugin extends AxiomPlugin with DecoderService {

  private case class Claim(sel: Payload[Bool], opcodes: Seq[Int], subFunctionLegal: Bits => Bool)

  private val claims = ArrayBuffer[Claim]()

  override def claim(sel: Payload[Bool], opcodes: Seq[Int], subFunctionLegal: Bits => Bool = null): Unit = {
    require(opcodes.nonEmpty, "a claim must name at least one opcode")
    claims += Claim(sel, opcodes, subFunctionLegal)
  }

  val logic = during build new Area {
    val node = ctrl(Stages.DECODE)
    val instr = node(Global.INSTRUCTION)
    val opcode = instr(Isa.OP_HI downto Isa.OP_LO)

    // Named opIs rather than `is` so it does not shadow the `is` of `switch`.
    def opIs(values: Iterable[Int]): Bool =
      values.map(v => opcode === B(v, 6 bits)).reduceOption(_ || _).getOrElse(False)
    def opIs(value: Int): Bool = opcode === B(value, 6 bits)

    // ---- routing ------------------------------------------------------
    for (claim <- claims) {
      val opcodeMatches = opIs(claim.opcodes)
      val subFunctionOk = if (claim.subFunctionLegal == null) True else claim.subFunctionLegal(instr)
      node(claim.sel) := opcodeMatches && subFunctionOk
    }

    // Nothing claimed it, so it is illegal. That covers an undefined opcode,
    // an undefined sub-function inside a defined one, and a unit configured
    // out of this build; all three trap identically.
    node(Global.ILLEGAL) := !claims.map(c => node(c.sel)).reduceOption(_ || _).getOrElse(False)

    // ---- register addresses -------------------------------------------
    val rdField = instr(Isa.RD_HI downto Isa.RD_LO).asUInt
    val rnField = instr(Isa.RN_HI downto Isa.RN_LO).asUInt
    val rmField = instr(Isa.RM_HI downto Isa.RM_LO).asUInt

    // BL writes the link register implicitly, which is what keeps 26 bits of
    // displacement available in its encoding.
    node(Global.RD_ADDR) := Mux(opIs(Isa.BL), U(Isa.LR, Isa.REG_ADDR_BITS bits), rdField)
    node(Global.RN_ADDR) := rnField
    node(Global.RM_ADDR) := rmField
    node(Global.PD_ADDR) := instr(23 downto 21).asUInt

    // ---- what the instruction touches ----------------------------------
    val aluOpcodes = Seq(Isa.ALU_R, Isa.ALU_SHIFT, Isa.ADDI, Isa.ANDI, Isa.ORI,
      Isa.XORI, Isa.SLTI, Isa.SLTUI)
    val constOpcodes = Seq(Isa.MOVZ, Isa.MOVN, Isa.MOVK, Isa.ADDPC)

    node(Global.WRITES_RD) := opIs(aluOpcodes) || opIs(constOpcodes) || opIs(Isa.SEL) ||
      opIs(Isa.LOADS) || opIs(Isa.LDP) || opIs(Isa.BL) || opIs(Isa.JALR) ||
      opIs(Isa.LD_ORD) || opIs(Isa.ATOMIC)

    // A load pair is the only instruction that writes a second general
    // register, and it always names it in the rm field.
    node(Global.WRITES_RM) := opIs(Isa.LDP)

    node(Global.WRITES_PD) := opIs(Isa.CMP_R) || opIs(Isa.CMP_I)

    node(Global.READS_RN) := opIs(aluOpcodes) || opIs(Isa.CMP_R) || opIs(Isa.CMP_I) ||
      opIs(Isa.SEL) || opIs(Isa.LOADS) || opIs(Isa.STORES) || opIs(Isa.LDP) || opIs(Isa.STP) ||
      opIs(Isa.JALR) || opIs(Isa.LD_ORD) || opIs(Isa.ST_ORD) || opIs(Isa.ATOMIC)

    // The rm field is a source for three-operand arithmetic, for the second
    // register of a store pair, and for the operand of an atomic.
    node(Global.READS_RM) := opIs(Isa.ALU_R) || opIs(Isa.CMP_R) || opIs(Isa.SEL) ||
      opIs(Isa.STP) || opIs(Isa.ATOMIC)

    // MOVK reads the register it also writes, and every store takes its data
    // from the rd field. CAS reads it as the comparison value.
    node(Global.READS_RD) := opIs(Isa.MOVK) || opIs(Isa.STORES) || opIs(Isa.STP) ||
      opIs(Isa.ST_ORD) || opIs(Isa.ATOMIC)

    // ---- constants ------------------------------------------------------
    val imm16 = instr(15 downto 0).asSInt.resize(AxiomParam.XLEN.get).asBits
    val imm12 = instr(11 downto 0).asSInt.resize(AxiomParam.XLEN.get).asBits
    val imm21 = instr(20 downto 0).asSInt.resize(AxiomParam.XLEN.get).asBits
    val imm14 = instr(13 downto 0).asSInt
    val imm9  = instr(8 downto 0).asSInt

    /** Memory displacements are scaled by the access size, which is a constant
      * of the opcode, so the shift is free.
      */
    def scaled(value: SInt, shift: Int): Bits =
      (value << shift).resize(AxiomParam.XLEN.get).asBits

    val imm = Bits(AxiomParam.XLEN bits)
    imm := B(0, AxiomParam.XLEN bits)
    switch(opcode) {
      is(B(Isa.ADDI, 6 bits), B(Isa.ANDI, 6 bits), B(Isa.ORI, 6 bits),
         B(Isa.XORI, 6 bits), B(Isa.SLTI, 6 bits), B(Isa.SLTUI, 6 bits),
         B(Isa.JALR, 6 bits)) {
        imm := imm16
      }
      is(B(Isa.CMP_I, 6 bits)) { imm := imm12 }
      is(B(Isa.ADDPC, 6 bits)) { imm := imm21 }
      is(B(Isa.LDB, 6 bits), B(Isa.LDBU, 6 bits), B(Isa.STB, 6 bits)) { imm := scaled(imm14, Isa.SIZE_B) }
      is(B(Isa.LDH, 6 bits), B(Isa.LDHU, 6 bits), B(Isa.STH, 6 bits)) { imm := scaled(imm14, Isa.SIZE_H) }
      is(B(Isa.LDW, 6 bits), B(Isa.LDWU, 6 bits), B(Isa.STW, 6 bits)) { imm := scaled(imm14, Isa.SIZE_W) }
      is(B(Isa.LDD, 6 bits), B(Isa.STD, 6 bits)) { imm := scaled(imm14, Isa.SIZE_D) }
      is(B(Isa.LDP, 6 bits), B(Isa.STP, 6 bits)) { imm := scaled(imm9, 3) }
    }
    node(Global.IMM) := imm

    val branchOffset = UInt(AxiomParam.PC_WIDTH bits)
    val off26 = (instr(25 downto 0) ## B"00").asSInt.resize(AxiomParam.PC_WIDTH.get).asUInt
    val off22 = (instr(21 downto 0) ## B"00").asSInt.resize(AxiomParam.PC_WIDTH.get).asUInt
    branchOffset := U(0, AxiomParam.PC_WIDTH bits)
    switch(opcode) {
      is(B(Isa.B, 6 bits), B(Isa.BL, 6 bits)) { branchOffset := off26 }
      is(B(Isa.BP, 6 bits)) { branchOffset := off22 }
    }
    node(Global.BRANCH_OFF) := branchOffset

    // Single-transaction by default; the load/store unit overrides this for
    // the first pass of a pair.
    node(Global.LAST_BEAT) := True
  }
}
