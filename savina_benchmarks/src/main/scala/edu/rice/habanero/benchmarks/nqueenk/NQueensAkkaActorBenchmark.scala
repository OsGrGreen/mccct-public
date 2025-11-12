package edu.rice.habanero.benchmarks.nqueenk

import org.apache.pekko.actor.{ActorRef, Props}
import mccct.actors.{PekkoActor, PekkoActorState}
import mccct.given
import mccct.*
import edu.rice.habanero.benchmarks.nqueenk.NQueensConfig.{DoneMessage, ResultMessage, StopMessage}
import edu.rice.habanero.benchmarks.{Benchmark, BenchmarkRunner}

/** @author
  *   <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
  */
object NQueensPekkoActorBenchmark {

  @main def NQueens(args: String*) = {
    BenchmarkRunner.runBenchmark(args.toArray, new NQueensPekkoActorBenchmark)
  }

  private final class NQueensPekkoActorBenchmark extends Benchmark {
    def initialize(args: Array[String]) = {
      NQueensConfig.parseArgs(args)
    }

    def printArgInfo() = {
      NQueensConfig.printArgs()
    }

    def runIteration() = {
      val numWorkers: Int         = NQueensConfig.NUM_WORKERS
      val priorities: Int         = NQueensConfig.PRIORITIES
      val master: Array[ActorRef] = Array(null)

      val system = PekkoActorState.newActorSystem("NQueens")

      master(0) = system.actorOf(Props(new Master(numWorkers, priorities)))
      PekkoActorState.startActor(master(0))

      PekkoActorState.awaitTermination(system)

      val expSolution    = NQueensConfig.SOLUTIONS(NQueensConfig.SIZE - 1)
      val actSolution    = Master.resultCounter
      val solutionsLimit = NQueensConfig.SOLUTIONS_LIMIT
      val valid          = actSolution >= solutionsLimit && actSolution <= expSolution
    }

    def cleanupIteration(lastIteration: Boolean, execTimeMillis: Double) = {
      Master.resultCounter = 0
    }
  }

  object Master {
    var resultCounter: Long = 0
  }

  private class Master(numWorkers: Int, priorities: Int) extends PekkoActor[AnyRef] {

    private val solutionsLimit            = NQueensConfig.SOLUTIONS_LIMIT
    private final val workers             = new Array[ActorRef](numWorkers)
    private var messageCounter: Int       = 0
    private var numWorkersTerminated: Int = 0
    private var numWorkSent: Int          = 0
    private var numWorkCompleted: Int     = 0

    override def onPostStart() = {
      var i: Int = 0
      while (i < numWorkers) {
        workers(i) = context.system.actorOf(Props(new Worker(self, i)))
        PekkoActorState.startActor(workers(i))
        i += 1
      }
      val inArray: Array[Int] = new Array[Int](0)
      val workMessage         = new NQueensConfig.WorkMessage(priorities, inArray, 0)
      sendWork(workMessage)
    }

    private def sendWork(workMessage: NQueensConfig.WorkMessage) = {
      sendTo(workMessage, workers(messageCounter))
      messageCounter = (messageCounter + 1) % numWorkers
      numWorkSent += 1
    }

    override def process(theMsg: AnyRef) = {
      theMsg match {
        case workMessage: NQueensConfig.WorkMessage =>
          sendWork(workMessage)
        case _: NQueensConfig.ResultMessage =>
          Master.resultCounter += 1
          if (Master.resultCounter == solutionsLimit) {
            requestWorkersToTerminate()
          }
        case _: NQueensConfig.DoneMessage =>
          numWorkCompleted += 1
          if (numWorkCompleted == numWorkSent) {
            requestWorkersToTerminate()
          }
        case _: NQueensConfig.StopMessage =>
          numWorkersTerminated += 1
          if (numWorkersTerminated == numWorkers) {
            exit()
          }
        case _ =>
      }
    }

    def requestWorkersToTerminate() = {
      var i: Int = 0
      while (i < numWorkers) {
        sendTo(StopMessage.ONLY, workers(i))
        i += 1
      }
    }
  }

  private class Worker(master: ActorRef, id: Int) extends PekkoActor[AnyRef] {

    private final val threshold: Int = NQueensConfig.THRESHOLD
    private final val size: Int      = NQueensConfig.SIZE

    override def process(theMsg: AnyRef) = {
      theMsg match {
        case workMessage: NQueensConfig.WorkMessage =>
          nqueensKernelPar(workMessage)
          sendTo(DoneMessage.ONLY, master)
        case _: NQueensConfig.StopMessage =>
          sendTo(theMsg, master)
          exit()
        case _ =>
      }
    }

    private[nqueenk] def nqueensKernelPar(workMessage: NQueensConfig.WorkMessage) = {
      val a: Array[Int] = workMessage.data
      val depth: Int    = workMessage.depth
      if (size == depth) {
        sendTo(ResultMessage.ONLY, master)
      } else if (depth >= threshold) {
        nqueensKernelSeq(a, depth)
      } else {
        val newPriority: Int = workMessage.priority - 1
        val newDepth: Int    = depth + 1
        var i: Int           = 0
        while (i < size) {
          val b: Array[Int] = new Array[Int](newDepth)
          System.arraycopy(a, 0, b, 0, depth)
          b(depth) = i
          if (NQueensConfig.boardValid(newDepth, b)) {
            sendTo(new NQueensConfig.WorkMessage(newPriority, b, newDepth), master)
          }
          i += 1
        }
      }
    }

    private[nqueenk] def nqueensKernelSeq(a: Array[Int], depth: Int): Unit = {
      if (size == depth) {
        sendTo(ResultMessage.ONLY, master)
      } else {
        val b: Array[Int] = new Array[Int](depth + 1)

        var i: Int = 0
        while (i < size) {
          System.arraycopy(a, 0, b, 0, depth)
          b(depth) = i
          if (NQueensConfig.boardValid(depth + 1, b)) {
            nqueensKernelSeq(b, depth + 1)
          }

          i += 1
        }
      }
    }
  }

}
