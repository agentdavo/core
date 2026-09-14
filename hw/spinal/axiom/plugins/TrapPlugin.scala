package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib.misc.pipeline._
import scala.collection.mutable.ArrayBuffer

/** Collects everything that stops the machine, and reports why.
  *
  * Version 0.1 has no handler, so stopping means stopping. What matters is
  * that it stops *precisely*: the offending instruction is thrown out of
  * execute before it can commit, everything younger is discarded, and the two
  * instructions already past execute drain and commit normally. The state
  * visible afterwards is exactly the state after the last instruction before
  * the one that stopped.
  *
  * A HALT arrives through the same path with a cause of NONE, so there is one
  * mechanism rather than two.
  */
class TrapPlugin extends AxiomPlugin with TrapService {

  private val ports = ArrayBuffer[TrapCmd]()

  override def newTrapPort(): TrapCmd = {
    val cmd = TrapCmd()
    cmd.valid := False
    cmd.cause := U(Isa.Cause.NONE, Isa.Cause.WIDTH bits)
    ports += cmd
    cmd
  }

  val logic = during build new Area {
    val node = ctrl(Stages.EXECUTE)

    // Guarded on the up node only. Reading `down.isValid` here would close a
    // combinational loop, because throwing the instruction clears it.
    val present = node.isValid
    val illegal = present && node(Global.ILLEGAL)
    val requested = ports.map(port => port.valid).reduceOption(_ || _).getOrElse(False)

    val cause = UInt(Isa.Cause.WIDTH bits)
    cause := U(Isa.Cause.NONE, Isa.Cause.WIDTH bits)
    for (port <- ports) when(port.valid) { cause := port.cause }
    // An undefined instruction wins over anything a unit computed from it,
    // since the unit had no business decoding it in the first place.
    when(illegal) { cause := U(Isa.Cause.ILLEGAL, Isa.Cause.WIDTH bits) }

    val halted  = Reg(Bool()) init False
    val trapped = Reg(Bool()) init False
    val causeReg = Reg(UInt(Isa.Cause.WIDTH bits)) init Isa.Cause.NONE
    val pcReg = Reg(UInt(AxiomParam.PC_WIDTH bits)) init 0

    val stopNow = present && (illegal || requested) && !halted

    when(stopNow) {
      halted := True
      trapped := cause =/= Isa.Cause.NONE
      causeReg := cause
      pcReg := node(Global.PC)
    }

    val stopped = halted || stopNow

    // The offending instruction must not commit, and nothing behind it may
    // start. Older instructions are untouched and drain normally.
    node.throwWhen(stopNow)
    ctrl(Stages.DECODE).throwWhen(stopped)
    ctrl(Stages.FETCH).haltWhen(stopped)

    // ---- counters --------------------------------------------------------
    val cycles = Reg(UInt(32 bits)) init 0
    val retired = Reg(UInt(32 bits)) init 0
    when(!halted) { cycles := cycles + 1 }
    val writeback = ctrl(Stages.WRITEBACK)
    val committing = writeback.down.isFiring && writeback.down(Global.LAST_BEAT)
    when(committing) { retired := retired + 1 }

    // ---- reporting -------------------------------------------------------
    val io = host[InterfaceService].io
    io.halted := halted
    io.trapped := trapped
    io.cause := causeReg
    io.trapPc := pcReg
    io.cycleCount := cycles
    io.retireCount := retired
    io.dbgRetireValid := committing
    io.dbgRetirePc := writeback.down(Global.PC)
  }
}
