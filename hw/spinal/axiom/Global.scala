package axiom

import spinal.core._
import spinal.lib.misc.pipeline._

/** Payload keys shared between plugins.
  *
  * A [[Payload]] is a typed key, not a signal. The hardware for it is created
  * on the first node that asks for it, and the pipeline links propagate it
  * between the nodes that use it, which means two plugins can agree on a value
  * without either knowing the other exists and without the value being carried
  * through stages that do not read it.
  *
  * Widths are read from [[AxiomParam]] lazily, because `Payload` takes its type
  * by name. That is what lets this be an object rather than something that has
  * to be constructed inside a database scope.
  */
object Global extends AreaObject {

  /** Address of the instruction. */
  val PC = Payload(UInt(AxiomParam.PC_WIDTH bits))

  /** Fetch generation, bumped on every redirect.
    *
    * An instruction fetched under a stale generation is killed at decode. This
    * is how the branch shadow is cleaned up without the branch having to know
    * how many stages are in front of it.
    */
  val FETCH_GEN = Payload(UInt(2 bits))

  val INSTRUCTION = Payload(Bits(Isa.INSTR_BITS bits))

  // -- decoded register addresses ---------------------------------------
  val RD_ADDR = Payload(UInt(AxiomParam.REG_ADDR_BITS bits))
  val RN_ADDR = Payload(UInt(AxiomParam.REG_ADDR_BITS bits))
  val RM_ADDR = Payload(UInt(AxiomParam.REG_ADDR_BITS bits))
  val PD_ADDR = Payload(UInt(AxiomParam.PRED_ADDR_BITS bits))

  // -- what the instruction touches, for hazard detection -----------------
  val WRITES_RD = Payload(Bool())
  val WRITES_PD = Payload(Bool())
  val READS_RN  = Payload(Bool())
  val READS_RM  = Payload(Bool())

  /** Reads the register named by the `rd` field. Stores, MOVK, CAS and the
    * second half of a pair all do this, which is why `rd` needs a read port.
    */
  val READS_RD = Payload(Bool())

  /** The value this instruction will write, as far down the pipeline as it has
    * been decided.
    *
    * Every plugin that produces a result offers it to the register file with a
    * select of its own, and something has to choose between them. Choosing
    * once per consumer means a multiplexer over every producer in every stage
    * that forwards, and on a part where a wide multiplexer is a LUT and a long
    * wire that was the largest single block of logic in the core.
    *
    * So the choice is made once, in the stage where each producer finishes,
    * and the answer travels with the instruction. A stage adds only what
    * finishes in it: execute picks between the arithmetic results, memory
    * replaces it for a shift or a multiply, writeback for a load. Forwarding
    * then reads a wire rather than a multiplexer.
    */
  val RESULT = Payload(Bits(AxiomParam.XLEN bits))

  /** Where the front end went after fetching this instruction.
    *
    * The instruction itself is not read until decode, so the address fetched
    * after it is a guess. Carrying the guess with the instruction is what lets
    * decode check it: decode knows what the instruction is and where it
    * actually goes, and a guess that does not match is corrected there. That
    * check is what makes an untagged prediction table safe — the worst an
    * entry belonging to some other address can do is cost the cycles of a
    * redirect.
    */
  val PREDICTED_NEXT = Payload(UInt(AxiomParam.PC_WIDTH bits))

  /** The `rd` operand is not needed until the memory stage.
    *
    * A store's data is the one operand in the machine that nothing computes
    * with: it is handed to the bus two stages after it is read. So a store may
    * start while the instruction that produces its data is still in execute,
    * and pick the value up in memory, where that producer has reached
    * writeback. The register file's interlock reads this to know it may let
    * the store past, and the load/store unit reads it to know it has to
    * collect the value rather than trust what it read.
    *
    * It applies to a single store only. A pair's second half reads `rm`, and
    * an atomic computes with its operand, so both want it when everything else
    * does.
    */
  val LATE_RD = Payload(Bool())

  // -- register file read data -------------------------------------------
  val RS_N = Payload(Bits(AxiomParam.XLEN bits))
  val RS_M = Payload(Bits(AxiomParam.XLEN bits))
  val RS_D = Payload(Bits(AxiomParam.XLEN bits))

  /** The instruction's constant, already sign extended and, for memory
    * accesses, already scaled by the access size.
    */
  val IMM = Payload(Bits(AxiomParam.XLEN bits))

  /** The instruction updates its base register as well as its destination.
    *
    * This is what pre-index, post-index and the pair forms cost: a second
    * architectural write, which needs a second write port and a second
    * forwarding comparator on every operand.
    */
  val WRITES_BASE = Payload(Bool())
  val BASE_VALUE  = Payload(Bits(AxiomParam.XLEN bits))

  /** The instruction still owes a write to the register named by `RM_ADDR`.
    *
    * A load pair writes two registers from one instruction, and it does so by
    * taking a second pass through the memory stage with `RD_ADDR` overridden
    * to the rm field. Before that second pass exists there is nothing in the
    * pipeline naming the second register, so the interlock would let a
    * consumer of it read a stale value straight out of the file. This flag
    * names it from decode and the load/store unit clears it once the pass
    * that performs the write is the one in flight.
    */
  val WRITES_RM = Payload(Bool())

  /** The final transaction of an instruction.
    *
    * A pair goes through the memory stage twice and so puts two transactions
    * down the pipeline, one per register it writes. Both must commit, but the
    * instruction has only retired once, so the counter and the retire trace
    * look at this.
    */
  val LAST_BEAT = Payload(Bool())

  /** Sign extended, word scaled program counter relative displacement. */
  val BRANCH_OFF = Payload(UInt(AxiomParam.PC_WIDTH bits))

  /** No execution unit claimed this instruction.
    *
    * That covers an undefined primary opcode, an undefined sub-function inside
    * a defined one, and an instruction whose unit was configured out of this
    * build. All three are the same thing architecturally: an illegal
    * instruction trap.
    */
  val ILLEGAL = Payload(Bool())
}
