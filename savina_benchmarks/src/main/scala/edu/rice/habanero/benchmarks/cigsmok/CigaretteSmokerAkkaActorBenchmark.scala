package edu.rice.habanero.benchmarks.cigsmok

import org.apache.pekko.actor.{ActorRef, Props}
import mccct.actors.{PekkoActor, PekkoActorState}
import mccct.given
import mccct.*
import edu.rice.habanero.benchmarks.cigsmok.CigaretteSmokerConfig.{
  ExitMessage,
  StartMessage,
  StartSmoking,
  StartedSmoking
}
import edu.rice.habanero.benchmarks.{Benchmark, BenchmarkRunner, PseudoRandom}

/** Based on solution in <a href="http://en.wikipedia.org/wiki/Cigarette_smokers_problem">Wikipedia</a> where resources
  * are acquired instantaneously.
  *
  * @author
  *   <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
  */
object CigaretteSmokerPekkoActorBenchmark {

  @main def CigaretteSmoker(args: String*) = {
    BenchmarkRunner.runBenchmark(args.toArray, new CigaretteSmokerPekkoActorBenchmark)
  }

  private final class CigaretteSmokerPekkoActorBenchmark extends Benchmark {
    def initialize(args: Array[String]) = {
      CigaretteSmokerConfig.parseArgs(args)
    }

    def printArgInfo() = {
      CigaretteSmokerConfig.printArgs()
    }

    def runIteration() = {

      val system = PekkoActorState.newActorSystem("CigaretteSmoker")

      val arbiterActor = system.actorOf(Props(new ArbiterActor(CigaretteSmokerConfig.R, CigaretteSmokerConfig.S)))
      PekkoActorState.startActor(arbiterActor)

      arbiterActor ! StartMessage.ONLY

      PekkoActorState.awaitTermination(system)
    }

    def cleanupIteration(lastIteration: Boolean, execTimeMillis: Double) = {}
  }

  private class ArbiterActor(numRounds: Int, numSmokers: Int) extends PekkoActor[AnyRef] {

    private val smokerActors =
      Array.tabulate[ActorRef](numSmokers)(i => context.system.actorOf(Props(new SmokerActor(self))))
    private val random      = new PseudoRandom(numRounds * numSmokers)
    private var roundsSoFar = 0

    override def onPostStart() = {
      smokerActors.foreach(loopActor => {
        PekkoActorState.startActor(loopActor)
      })
    }

    override def process(msg: AnyRef) = {
      msg match {
        case sm: StartMessage =>

          // choose a random smoker to start smoking
          notifyRandomSmoker()

        case sm: StartedSmoking =>

          // resources are off the table, can place new ones on the table
          roundsSoFar += 1
          if (roundsSoFar >= numRounds) {
            // had enough, now exit
            requestSmokersToExit()
            exit()
          } else {
            // choose a random smoker to start smoking
            notifyRandomSmoker()
          }
      }
    }

    private def notifyRandomSmoker() = {
      // assume resources grabbed instantaneously
      val newSmokerIndex = Math.abs(random.nextInt()) % numSmokers
      val busyWaitPeriod = random.nextInt(1000) + 10
      sendTo(new StartSmoking(busyWaitPeriod), smokerActors(newSmokerIndex))
    }

    private def requestSmokersToExit() = {
      smokerActors.foreach(loopActor => {
        sendTo(ExitMessage.ONLY, loopActor)
      })
    }
  }

  private class SmokerActor(arbiterActor: ActorRef) extends PekkoActor[AnyRef] {
    override def process(msg: AnyRef) = {
      msg match {
        case sm: StartSmoking =>

          // notify arbiter that started smoking
          sendTo(StartedSmoking.ONLY, arbiterActor)
          // now smoke cigarette
          CigaretteSmokerConfig.busyWait(sm.busyWaitPeriod)

        case em: ExitMessage =>

          exit()
      }
    }
  }

}
