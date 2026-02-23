package edu.rice.habanero.benchmarks.big

import org.apache.pekko.actor.{ActorRef, Props}
import mccct.actors.{PekkoActor, PekkoActorState}
import mccct.given
import mccct.*
import edu.rice.habanero.benchmarks.big.BigConfig.{ExitMessage, Message, PingMessage, PongMessage}
import edu.rice.habanero.benchmarks.{Benchmark, BenchmarkRunner, PseudoRandom}

/** @author
  *   <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
  */
object BigPekkoActorBenchmark {

  @main def Big(args: String*) = {
    BenchmarkRunner.runBenchmark(args.toArray, new BigPekkoActorBenchmark)
  }

  private final class BigPekkoActorBenchmark extends Benchmark {
    def initialize(args: Array[String]) = {
      BigConfig.parseArgs(args)
    }

    def printArgInfo() = {
      BigConfig.printArgs()
    }

    def runIteration() = {

      val system = PekkoActorState.newActorSystem("Big")

      val sinkActor = system.actorOf(Props(new SinkActor(BigConfig.W)))
      PekkoActorState.startActor(sinkActor)

      val bigActors = Array.tabulate[ActorRef](BigConfig.W)(i => {
        val loopActor = system.actorOf(Props(new BigActor(i, BigConfig.N, sinkActor)))
        PekkoActorState.startActor(loopActor)
        loopActor
      })

      val neighborMessage = new NeighborMessage(bigActors)
      sinkActor ! neighborMessage
      bigActors.foreach(loopActor => {
        loopActor ! neighborMessage
      })

      bigActors.foreach(loopActor => {
        loopActor ! new PongMessage(-1)
      })

      PekkoActorState.awaitTermination(system)
    }

    def cleanupIteration(lastIteration: Boolean, execTimeMillis: Double) = {}
  }

  private case class NeighborMessage(neighbors: Array[ActorRef]) extends Message

  private class BigActor(id: Int, numMessages: Int, sinkActor: ActorRef) extends PekkoActor[AnyRef] {

    private var numPings                   = 0
    private var expPinger                  = -1
    private val random                     = new PseudoRandom(id)
    private var neighbors: Array[ActorRef] = null

    private val myPingMessage = new PingMessage(id)
    private val myPongMessage = new PongMessage(id)

    override def process(msg: AnyRef) = {
      msg match {
        case pm: PingMessage =>

          val sender = neighbors(pm.sender)
          sendTo(myPongMessage, sender)

        case pm: PongMessage =>

          if (pm.sender != expPinger) {
            println("ERROR: Expected: " + expPinger + ", but received ping from " + pm.sender)
          }
          if (numPings == numMessages) {
            sendTo(ExitMessage.ONLY, sinkActor)
          } else {
            sendPing()
            numPings += 1
          }

        case em: ExitMessage =>

          exit()

        case nm: NeighborMessage =>

          neighbors = nm.neighbors
      }
    }

    private def sendPing(): Unit = {
      val target      = random.nextInt(neighbors.size)
      val targetActor = neighbors(target)

      expPinger = target
      sendTo(myPingMessage, targetActor)
    }
  }

  private class SinkActor(numWorkers: Int) extends PekkoActor[AnyRef] {

    private var numMessages                = 0
    private var neighbors: Array[ActorRef] = null

    override def process(msg: AnyRef) = {
      msg match {
        case em: ExitMessage =>

          numMessages += 1
          if (numMessages == numWorkers) {
            neighbors.foreach(loopWorker => sendTo(ExitMessage.ONLY, loopWorker))
            exit()
          }

        case nm: NeighborMessage =>

          neighbors = nm.neighbors
      }
    }
  }

}
