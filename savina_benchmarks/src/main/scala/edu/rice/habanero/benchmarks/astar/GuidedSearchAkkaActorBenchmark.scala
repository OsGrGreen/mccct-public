package edu.rice.habanero.benchmarks.astar

import java.util

import org.apache.pekko.actor.{ActorRef, Props}
import mccct.actors.{PekkoActor, PekkoActorState}
import mccct.given
import mccct.*
import edu.rice.habanero.benchmarks.astar.GuidedSearchConfig._
import edu.rice.habanero.benchmarks.{Benchmark, BenchmarkRunner}

/** @author
  *   <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
  */
object GuidedSearchPekkoActorBenchmark {

  def GuidedSearch(args: String*) = {
    BenchmarkRunner.runBenchmark(args.toArray, new GuidedSearchPekkoActorBenchmark)
  }

  private final class GuidedSearchPekkoActorBenchmark extends Benchmark {
    def initialize(args: Array[String]) = {
      GuidedSearchConfig.parseArgs(args)
    }

    def printArgInfo() = {
      GuidedSearchConfig.printArgs()
    }

    def runIteration() = {

      val system = PekkoActorState.newActorSystem("GuidedSearch")

      val master = system.actorOf(Props(new Master()))
      PekkoActorState.startActor(master)

      PekkoActorState.awaitTermination(system)

      val nodesProcessed = GuidedSearchConfig.nodesProcessed()
      track("Nodes Processed", nodesProcessed)
    }

    def cleanupIteration(lastIteration: Boolean, execTimeMillis: Double) = {
      val valid = GuidedSearchConfig.validate()
      printf(BenchmarkRunner.argOutputFormat, "Result valid", valid)
      GuidedSearchConfig.initializeData()
    }
  }

  private class Master extends PekkoActor[AnyRef] {

    private final val numWorkers          = GuidedSearchConfig.NUM_WORKERS
    private final val workers             = new Array[ActorRef](numWorkers)
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
      sendWork(new WorkMessage(originNode, targetNode))
    }

    private def sendWork(workMessage: WorkMessage) = {
      val workerIndex: Int = numWorkSent % numWorkers
      numWorkSent += 1
      sendTo(workMessage, workers(workerIndex))
    }

    override def process(theMsg: AnyRef) = {
      theMsg match {
        case workMessage: WorkMessage =>
          sendWork(workMessage)
        case _: ReceivedMessage =>
          numWorkCompleted += 1
          if (numWorkCompleted == numWorkSent) {
            requestWorkersToStop()
          }
        case _: DoneMessage =>
          requestWorkersToStop()
        case _: StopMessage =>
          numWorkersTerminated += 1
          if (numWorkersTerminated == numWorkers) {
            exit()
          }
        case _ =>
      }
    }

    private def requestWorkersToStop() = {
      var i: Int = 0
      while (i < numWorkers) {
        sendTo(StopMessage.ONLY, workers(i))
        i += 1
      }
    }
  }

  private class Worker(master: ActorRef, id: Int) extends PekkoActor[AnyRef] {

    private final val threshold = GuidedSearchConfig.THRESHOLD

    override def process(theMsg: AnyRef) = {
      theMsg match {
        case workMessage: WorkMessage =>
          search(workMessage)
          sendTo(ReceivedMessage.ONLY, master)
        case _: StopMessage =>
          sendTo(theMsg, master)
          exit()
        case _ =>
      }
    }

    private def search(workMessage: WorkMessage): Unit = {

      val targetNode = workMessage.target
      val workQueue  = new util.LinkedList[GridNode]
      workQueue.add(workMessage.node)

      var nodesProcessed: Int = 0
      while (!workQueue.isEmpty && nodesProcessed < threshold) {

        nodesProcessed += 1
        GuidedSearchConfig.busyWait()

        val loopNode          = workQueue.poll
        val numNeighbors: Int = loopNode.numNeighbors

        var i: Int = 0
        while (i < numNeighbors) {
          val loopNeighbor     = loopNode.neighbor(i)
          val success: Boolean = loopNeighbor.setParent(loopNode)
          if (success) {
            if (loopNeighbor eq targetNode) {
              sendTo(DoneMessage.ONLY, master)
              return
            } else {
              workQueue.add(loopNeighbor)
            }
          }
          i += 1
        }
      }

      while (!workQueue.isEmpty) {
        val loopNode       = workQueue.poll
        val newWorkMessage = new WorkMessage(loopNode, targetNode)
        sendTo(newWorkMessage, master)
      }
    }
  }

}
