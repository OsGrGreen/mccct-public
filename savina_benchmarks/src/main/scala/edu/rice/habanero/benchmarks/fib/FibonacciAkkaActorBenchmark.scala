package edu.rice.habanero.benchmarks.fib

import org.apache.pekko.actor.{ActorRef, Props}
import mccct.actors.{PekkoActor, PekkoActorState}
import mccct.given
import mccct.*
import edu.rice.habanero.benchmarks.{Benchmark, BenchmarkRunner}

/** @author
  *   <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
  */
object FibonacciPekkoActorBenchmark {

  @main def Fibonacci(args: String*) = {
    BenchmarkRunner.runBenchmark(args.toArray, new FibonacciPekkoActorBenchmark)
  }

  private final class FibonacciPekkoActorBenchmark extends Benchmark {
    def initialize(args: Array[String]) = {
      FibonacciConfig.parseArgs(args)
    }

    def printArgInfo() = {
      FibonacciConfig.printArgs()
    }

    def runIteration() = {

      val system = PekkoActorState.newActorSystem("Fibonacci")

      val fjRunner = system.actorOf(Props(new FibonacciActor(null)))
      PekkoActorState.startActor(fjRunner)
      fjRunner ! Request(FibonacciConfig.N)

      PekkoActorState.awaitTermination(system)
    }

    def cleanupIteration(lastIteration: Boolean, execTimeMillis: Double) = {}
  }

  private case class Request(n: Int)

  private case class Response(value: Int)

  private val RESPONSE_ONE = Response(1)

  private class FibonacciActor(parent: ActorRef) extends PekkoActor[AnyRef] {

    private var result       = 0
    private var respReceived = 0

    override def process(msg: AnyRef) = {

      msg match {
        case req: Request =>

          if (req.n <= 2) {

            result = 1
            processResult(RESPONSE_ONE)

          } else {

            val f1 = context.system.actorOf(Props(new FibonacciActor(self)))
            PekkoActorState.startActor(f1)
            sendTo(Request(req.n - 1), f1)

            val f2 = context.system.actorOf(Props(new FibonacciActor(self)))
            PekkoActorState.startActor(f2)
            sendTo(Request(req.n - 2), f2)

          }

        case resp: Response =>

          respReceived += 1
          result += resp.value

          if (respReceived == 2) {
            processResult(Response(result))
          }
      }
    }

    private def processResult(response: Response) = {
      if (parent != null) {
        sendTo(response, parent)
      } else {
        println(" Result = " + result)
      }

      exit()
    }
  }

}
