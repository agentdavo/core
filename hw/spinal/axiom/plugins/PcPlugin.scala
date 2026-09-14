package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._
import scala.collection.mutable.ArrayBuffer

/** Owns the program counter and arbitrates redirects.
  *
  * Redirects are handled with a fetch generation counter rather than by
  * throwing a fixed number of stages. Every fetched instruction carries the
  * generation it was fetched under, and decode kills anything whose generation
  * is stale. The branch therefore does not need to know how many stages sit in
  * front of it, which means adding a fetch stage later costs nothing here.
  */
class PcPlugin extends AxiomPlugin with PcService {

  private val redirectPorts = ArrayBuffer[Flow[UInt]]()

  override def newRedirect(): Flow[UInt] = {
    val port = Flow(UInt(AxiomParam.PC_WIDTH bits))
    redirectPorts += port
    port
  }

  val logic = during build new Area {
    val node = ctrl(Stages.FETCH)

    val pc  = Reg(UInt(AxiomParam.PC_WIDTH bits)) init AxiomParam.RESET_VECTOR.get
    val gen = Reg(UInt(2 bits)) init 0

    // Fetch always has something to offer: there is always a next address.
    node.up.valid := True
    node.up(Global.PC) := pc
    node.up(Global.FETCH_GEN) := gen

    when(node.up.isFiring) {
      pc := pc + 4
    }

    // Later ports win, which is the right priority if a deeper stage ever
    // gains the ability to redirect.
    val anyRedirect = False
    for (port <- redirectPorts) {
      when(port.valid) {
        pc := port.payload
        anyRedirect := True
      }
    }
    when(anyRedirect) {
      gen := gen + 1
    }

    // Kill the branch shadow. The instruction sitting in decode this cycle was
    // fetched before the redirect but still carries the current generation, so
    // it needs the explicit term; everything already in flight behind it is
    // caught by the generation compare when it arrives.
    val decodeNode = ctrl(Stages.DECODE)
    decodeNode.throwWhen(anyRedirect || decodeNode.up(Global.FETCH_GEN) =/= gen)
  }
}
