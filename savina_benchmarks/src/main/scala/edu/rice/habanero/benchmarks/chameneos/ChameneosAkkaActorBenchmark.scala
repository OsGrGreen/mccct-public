package edu.rice.habanero.benchmarks.chameneos

import org.apache.pekko.actor.{ActorRef, Props}
import mccct.actors.{PekkoActor, PekkoActorState}
import mccct.given
import mccct.*
import edu.rice.habanero.benchmarks.{Benchmark, BenchmarkRunner}

/** @author
  *   <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
  */
object ChameneosPekkoActorBenchmark {

  @main def Chameneos(args: String*): Unit = {
    BenchmarkRunner.runBenchmark(args.toArray, new ChameneosPekkoActorBenchmark)
  }

  private final class ChameneosPekkoActorBenchmark extends Benchmark {
    def initialize(args: Array[String]) = {
      ChameneosConfig.parseArgs(args)
    }

    def printArgInfo() = {
      ChameneosConfig.printArgs()
    }

    def runIteration() = {

      val system = PekkoActorState.newActorSystem("Chameneos")

      val mallActor =
        system.actorOf(Props(new ChameneosMallActor(ChameneosConfig.numMeetings, ChameneosConfig.numChameneos)))

      PekkoActorState.startActor(mallActor)

      PekkoActorState.awaitTermination(system)
    }

    def cleanupIteration(lastIteration: Boolean, execTimeMillis: Double) = {}
  }

  private class ChameneosMallActor(var n: Int, numChameneos: Int) extends PekkoActor[ChameneosHelper.Message] {

    private var waitingChameneo: ActorRef = null
    private var sumMeetings: Int          = 0
    private var numFaded: Int             = 0

    protected override def onPostStart() = {
      startChameneos()
    }

    private def startChameneos() = {
      Array.tabulate[ActorRef](numChameneos)(i => {
        val color        = ChameneosHelper.Color.values()(i % 3)
        val loopChamenos = context.system.actorOf(Props(new ChameneosChameneoActor(self, color, i)))
        PekkoActorState.startActor(loopChamenos)
        loopChamenos
      })
    }

    override def process(msg: ChameneosHelper.Message) = {
      msg match {
        case message: ChameneosHelper.MeetingCountMsg =>
          numFaded = numFaded + 1
          sumMeetings = sumMeetings + message.count
          if (numFaded == numChameneos) {
            exit()
          }
        case message: ChameneosHelper.MeetMsg =>
          if (n > 0) {
            if (waitingChameneo == null) {
              val sender = message.sender.asInstanceOf[ActorRef]
              waitingChameneo = sender
            } else {
              n = n - 1
              sendTo(msg, waitingChameneo)
              waitingChameneo = null
            }
          } else {
            val sender = message.sender.asInstanceOf[ActorRef]
            sendTo(new ChameneosHelper.ExitMsg(self), sender)
          }
        case message =>
          val ex = new IllegalArgumentException("Unsupported message: " + message)
          ex.printStackTrace(System.err)
      }
    }
  }

  private class ChameneosChameneoActor(mall: ActorRef, var color: ChameneosHelper.Color, id: Int)
      extends PekkoActor[ChameneosHelper.Message] {

    private var meetings: Int = 0

    protected override def onPostStart() = {
      sendTo(new ChameneosHelper.MeetMsg(color, self), mall)
    }

    override def process(msg: ChameneosHelper.Message) = {
      msg match {
        case message: ChameneosHelper.MeetMsg =>
          val otherColor: ChameneosHelper.Color = message.color
          val sender                            = message.sender.asInstanceOf[ActorRef]
          color = ChameneosHelper.complement(color, otherColor)
          meetings = meetings + 1
          sendTo(new ChameneosHelper.ChangeMsg(color, self), sender)
          sendTo(new ChameneosHelper.MeetMsg(color, self), mall)
        case message: ChameneosHelper.ChangeMsg =>
          color = message.color
          meetings = meetings + 1
          sendTo(new ChameneosHelper.MeetMsg(color, self), mall)
        case message: ChameneosHelper.ExitMsg =>
          val sender = message.sender.asInstanceOf[ActorRef]
          color = ChameneosHelper.fadedColor
          sendTo(new ChameneosHelper.MeetingCountMsg(meetings, self), sender)
          exit()
        case message =>
          val ex = new IllegalArgumentException("Unsupported message: " + message)
          ex.printStackTrace(System.err)
      }
    }
  }

}
