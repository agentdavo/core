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

  private var perfRedirect: Bool = null

  val setupLogic = during setup new Area {
    perfRedirect = host[PerfService].newCounter("redirect")
  }

  /** A Handle so a plugin may ask before this one has built. */
  private val generation = spinal.core.fiber.Handle[UInt]()

  override def generationOk(node: NodeApi): Bool = node(Global.FETCH_GEN) === generation.get

  private val redirecting = spinal.core.fiber.Handle[Bool]()

  override def redirectNow: Bool = redirecting.get

  private val offered = spinal.core.fiber.Handle[PcOffer]()

  override def offer: PcOffer = offered.get

  override val accepted = spinal.core.fiber.Handle[Bool]()

  val logic = during build new Area {
    val pc  = Reg(UInt(AxiomParam.PC_WIDTH bits)) init AxiomParam.RESET_VECTOR.get
    val gen = Reg(UInt(2 bits)) init 0
    generation.load(gen)

    // Which address comes next is asked of the branch unit rather than
    // computed as a plus four here. It is a guess made without having read the
    // instruction, and it travels with the instruction so that decode can
    // check it.
    val next = host[PredictorService].nextPc(pc)
    offered.load(PcOffer(pc, gen, next))

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
    redirecting.load(anyRedirect)
    perfRedirect := anyRedirect

    // The counter moves when fetch has a command out for it. A command
    // accepted on the cycle of a redirect is for the address being left, and
    // the redirect wins.
    //
    // Fetch loads the accept only after it has read the redirect above, so
    // this waits on it last: the two builds meet in the middle.
    when(accepted.get && !anyRedirect) {
      pc := next
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

    /** The instruction that asked for a redirect is not part of the shadow it
      * created.
      *
      * A branch folded in decode redirects as soon as it knows where it is
      * going, which may be several cycles before it is allowed to leave the
      * stage. From the next cycle its own fetch generation is one behind, and
      * the compare below would throw it: the branch would vanish, taking a
      * link register write with it, and the retired instruction stream would
      * be missing the instruction that caused the jump.
      *
      * So a redirect from decode exempts whatever is in decode until it
      * leaves, whichever way it leaves. Nothing else is exempted: the
      * instruction that arrives after it was fetched before the redirect and
      * is exactly what the compare is for.
      */
    val exempt = Reg(Bool()) init False
    val fromDecode = ordered.filter(_.from == Stages.DECODE).map(_.port.valid)
    if (fromDecode.nonEmpty) when(fromDecode.reduce(_ || _)) { exempt := True }
    when(decodeNode.up.isMoving) { exempt := False }

    decodeNode.throwWhen(decodeNode.up(Global.FETCH_GEN) =/= gen && !exempt)
  }
}
