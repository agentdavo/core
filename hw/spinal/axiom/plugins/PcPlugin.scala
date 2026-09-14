package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._
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

  case class Redirect(from: Int, port: Flow[UInt])

  private val redirectPorts = ArrayBuffer[Redirect]()

  override def newRedirect(from: Int): Flow[UInt] = {
    val port = Flow(UInt(AxiomParam.PC_WIDTH bits))
    redirectPorts += Redirect(from, port)
    port
  }

  /** A Handle so a plugin may ask before this one has built. */
  private val generation = spinal.core.fiber.Handle[UInt]()

  override def generationOk(node: NodeApi): Bool = node(Global.FETCH_GEN) === generation.get

  val logic = during build new Area {
    val node = ctrl(Stages.FETCH)

    val pc  = Reg(UInt(AxiomParam.PC_WIDTH bits)) init AxiomParam.RESET_VECTOR.get
    val gen = Reg(UInt(2 bits)) init 0
    generation.load(gen)

    // Fetch always has something to offer: there is always a next address.
    node.up.valid := True
    node.up(Global.PC) := pc
    node.up(Global.FETCH_GEN) := gen

    when(node.up.isFiring) {
      pc := pc + 4
    }

    // Shallowest first, so a redirect from a deeper stage is assigned last and
    // therefore wins. The deeper instruction is the older one, and an older
    // instruction's control flow decision is the one that happened.
    val ordered = redirectPorts.sortBy(_.from)

    val anyRedirect = False
    for (redirect <- ordered) {
      when(redirect.port.valid) {
        pc := redirect.port.payload
        anyRedirect := True
      }
    }
    when(anyRedirect) {
      gen := gen + 1
    }

    // Kill the branch shadow. Every instruction already inside the pipeline
    // and younger than the redirecting one needs an explicit throw, because it
    // was fetched before the redirect and still carries the current
    // generation. Everything still in flight behind them is caught by the
    // generation compare as it arrives at decode.
    //
    // A redirect only throws the stages in front of its own, which is what
    // lets an unconditional branch resolve in decode without discarding
    // itself.
    for (stage <- Stages.DECODE until Stages.WRITEBACK) {
      val younger = ordered.filter(_.from > stage).map(_.port.valid)
      if (younger.nonEmpty) ctrl(stage).throwWhen(younger.reduce(_ || _))
    }

    val decodeNode = ctrl(Stages.DECODE)
    decodeNode.throwWhen(decodeNode.up(Global.FETCH_GEN) =/= gen)
  }
}
