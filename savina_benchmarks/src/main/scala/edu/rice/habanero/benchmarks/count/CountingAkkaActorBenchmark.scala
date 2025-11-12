package edu.rice.habanero.benchmarks.count

import org.apache.pekko.actor.{ActorRef, Props}
import mccct.actors.{PekkoActor, PekkoActorState}
import mccct.given
import mccct.*
import edu.rice.habanero.benchmarks.{Benchmark, BenchmarkRunner}

/** @author
  *   <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
  */
object CountingPekkoActorBenchmark {

  @main def Counting(args: String*) = {
    BenchmarkRunner.runBenchmark(args.toArray, new CountingPekkoActorBenchmark)
  }

  private final class CountingPekkoActorBenchmark extends Benchmark {
    def initialize(args: Array[String]) = {
      CountingConfig.parseArgs(args)
    }

    def printArgInfo() = {
      CountingConfig.printArgs()
    }

    def runIteration() = {

      val system = PekkoActorState.newActorSystem("Counting")

      val counter = system.actorOf(Props(new CountingActor()))
      PekkoActorState.startActor(counter)

      val producer = system.actorOf(Props(new ProducerActor(counter)))
      PekkoActorState.startActor(producer)

      producer ! IncrementMessage()

      PekkoActorState.awaitTermination(system)
    }

    def cleanupIteration(lastIteration: Boolean, execTimeMillis: Double) = {}
  }

  private case class IncrementMessage()

  private case class RetrieveMessage(sender: ActorRef)

  private case class ResultMessage(result: Int)

  private class ProducerActor(counter: ActorRef) extends PekkoActor[AnyRef] {

    override def process(msg: AnyRef) = {
      msg match {
        case m: IncrementMessage =>

          var i = 0
          while (i < CountingConfig.N) {
            sendTo(m, counter)
            i += 1
          }
          sendTo(RetrieveMessage(self), counter)

        case m: ResultMessage =>
          val result = m.result
          if (result != CountingConfig.N) {
            println("ERROR: expected: " + CountingConfig.N + ", found: " + result)
          } else {
            println("SUCCESS! received: " + result)
          }
          exit()
      }
    }
  }

  private class CountingActor extends PekkoActor[AnyRef] {

    private var count = 0

    override def process(msg: AnyRef) = {
      msg match {
        case m: IncrementMessage =>
          count += 1
        case m: RetrieveMessage =>
          sendTo(ResultMessage(count), m.sender)
          exit()
      }
    }
  }

}
