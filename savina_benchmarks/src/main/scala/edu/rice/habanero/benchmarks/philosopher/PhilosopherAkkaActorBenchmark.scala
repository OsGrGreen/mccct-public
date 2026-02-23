package edu.rice.habanero.benchmarks.philosopher

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import org.apache.pekko.actor.{ActorRef, Props}
import mccct.actors.{PekkoActor, PekkoActorState}
import edu.rice.habanero.benchmarks.{Benchmark, BenchmarkRunner}

import mccct.given
import mccct.*

/** @author
  *   <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
  */
object PhilosopherPekkoActorBenchmark {

  @main def Philosopher(args: String*) = {
    BenchmarkRunner.runBenchmark(args.toArray, new PhilosopherPekkoActorBenchmark)
  }

  private final class PhilosopherPekkoActorBenchmark extends Benchmark {
    def initialize(args: Array[String]) = {
      PhilosopherConfig.parseArgs(args)
    }

    def printArgInfo() = {
      PhilosopherConfig.printArgs()
    }

    def runIteration() = {

      val system = PekkoActorState.newActorSystem("Philosopher")

      val counter = new AtomicLong(0)

      val arbitrator = system.actorOf(Props(new ArbitratorActor(PhilosopherConfig.N)))
      PekkoActorState.startActor(arbitrator)

      val philosophers = Array.tabulate[ActorRef](PhilosopherConfig.N)(i => {
        val loopActor = system.actorOf(Props(new PhilosopherActor(i, PhilosopherConfig.M, counter, arbitrator)))
        PekkoActorState.startActor(loopActor)
        loopActor
      })

      philosophers.foreach(loopActor => {
        loopActor ! StartMessage()
      })

      PekkoActorState.awaitTermination(system)

      println("  Num retries: " + counter.get())
      track("Avg. Retry Count", counter.get())
    }

    def cleanupIteration(lastIteration: Boolean, execTimeMillis: Double) = {}
  }

  case class StartMessage()

  case class ExitMessage()

  case class HungryMessage(philosopher: ActorRef, philosopherId: Int)

  case class DoneMessage(philosopherId: Int)

  case class EatMessage()

  case class DeniedMessage()

  private class PhilosopherActor(id: Int, rounds: Int, counter: AtomicLong, arbitrator: ActorRef)
      extends PekkoActor[AnyRef] {

    private var localCounter = 0L
    private var roundsSoFar  = 0

    private val myHungryMessage = HungryMessage(self, id)
    private val myDoneMessage   = DoneMessage(id)

    override def process(msg: AnyRef) = {
      msg match {

        case dm: DeniedMessage =>

          localCounter += 1
          sendTo(myHungryMessage, arbitrator)

        case em: EatMessage =>

          roundsSoFar += 1
          counter.addAndGet(localCounter)

          sendTo(myDoneMessage, arbitrator)
          if (roundsSoFar < rounds) {
            sendTo(StartMessage(), self)
          } else {
            sendTo(ExitMessage(), arbitrator)
            exit()
          }

        case sm: StartMessage =>
          sendTo(myHungryMessage, arbitrator)

      }
    }
  }

  private class ArbitratorActor(numForks: Int) extends PekkoActor[AnyRef] {

    private val forks                 = Array.tabulate(numForks)(i => new AtomicBoolean(false))
    private var numExitedPhilosophers = 0

    override def process(msg: AnyRef) = {
      msg match {
        case hm: HungryMessage =>
          val leftFork  = forks(hm.philosopherId)
          val rightFork = forks((hm.philosopherId + 1) % numForks)

          if (leftFork.get() || rightFork.get()) {
            // someone else has access to the fork
            sendTo(DeniedMessage(), hm.philosopher)
          } else {
            leftFork.set(true)
            rightFork.set(true)
            sendTo(EatMessage(), hm.philosopher)
          }

        case dm: DoneMessage =>

          val leftFork  = forks(dm.philosopherId)
          val rightFork = forks((dm.philosopherId + 1) % numForks)
          leftFork.set(false)
          rightFork.set(false)

        case em: ExitMessage =>

          numExitedPhilosophers += 1
          if (numForks == numExitedPhilosophers) {
            exit()
          }
      }
    }
  }

}
