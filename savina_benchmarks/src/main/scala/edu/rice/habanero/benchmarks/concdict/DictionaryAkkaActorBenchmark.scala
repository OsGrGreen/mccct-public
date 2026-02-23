package edu.rice.habanero.benchmarks.concdict

import java.util

import org.apache.pekko.actor.{ActorRef, Props}
import mccct.actors.{PekkoActor, PekkoActorState}
import mccct.given
import mccct.*
import edu.rice.habanero.benchmarks.concdict.DictionaryConfig.{DoWorkMessage, EndWorkMessage, ReadMessage, WriteMessage}
import edu.rice.habanero.benchmarks.{Benchmark, BenchmarkRunner}

/** @author
  *   <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
  */
object DictionaryPekkoActorBenchmark {

  @main def Dictionary(args: String*) = {
    BenchmarkRunner.runBenchmark(args.toArray, new DictionaryPekkoActorBenchmark)
  }

  private final class DictionaryPekkoActorBenchmark extends Benchmark {
    def initialize(args: Array[String]) = {
      DictionaryConfig.parseArgs(args)
    }

    def printArgInfo() = {
      DictionaryConfig.printArgs()
    }

    def runIteration() = {
      val numWorkers: Int           = DictionaryConfig.NUM_ENTITIES
      val numMessagesPerWorker: Int = DictionaryConfig.NUM_MSGS_PER_WORKER
      val system                    = PekkoActorState.newActorSystem("Dictionary")

      val master = system.actorOf(Props(new Master(numWorkers, numMessagesPerWorker)))
      PekkoActorState.startActor(master)

      PekkoActorState.awaitTermination(system)
    }

    def cleanupIteration(lastIteration: Boolean, execTimeMillis: Double) = {}
  }

  private class Master(numWorkers: Int, numMessagesPerWorker: Int) extends PekkoActor[AnyRef] {

    private final val workers             = new Array[ActorRef](numWorkers)
    private final val dictionary          = context.system.actorOf(Props(new Dictionary(DictionaryConfig.DATA_MAP)))
    private var numWorkersTerminated: Int = 0

    override def onPostStart() = {
      PekkoActorState.startActor(dictionary)

      var i: Int = 0
      while (i < numWorkers) {
        workers(i) = context.system.actorOf(Props(new Worker(self, dictionary, i, numMessagesPerWorker)))
        PekkoActorState.startActor(workers(i))
        sendTo(DoWorkMessage.ONLY, workers(i))
        i += 1
      }
    }

    override def process(msg: AnyRef) = {
      if (msg.isInstanceOf[DictionaryConfig.EndWorkMessage]) {
        numWorkersTerminated += 1
        if (numWorkersTerminated == numWorkers) {
          sendTo(EndWorkMessage.ONLY, dictionary)
          exit()
        }
      }
    }
  }

  private class Worker(master: ActorRef, dictionary: ActorRef, id: Int, numMessagesPerWorker: Int)
      extends PekkoActor[AnyRef] {

    private final val writePercent = DictionaryConfig.WRITE_PERCENTAGE
    private var messageCount: Int  = 0
    private final val random       = new util.Random(id + numMessagesPerWorker + writePercent)

    override def process(msg: AnyRef) = {
      messageCount += 1
      if (messageCount <= numMessagesPerWorker) {
        val anInt: Int = random.nextInt(100)
        if (anInt < writePercent) {
          sendTo(new WriteMessage(self, random.nextInt, random.nextInt), dictionary)
        } else {
          sendTo(new ReadMessage(self, random.nextInt), dictionary)
        }
      } else {
        sendTo(EndWorkMessage.ONLY, master)
        exit()
      }
    }
  }

  private class Dictionary(initialState: util.Map[Integer, Integer]) extends PekkoActor[AnyRef] {

    private[concdict] final val dataMap = new util.HashMap[Integer, Integer](initialState)

    override def process(msg: AnyRef) = {
      msg match {
        case writeMessage: DictionaryConfig.WriteMessage =>
          val key   = writeMessage.key
          val value = writeMessage.value
          dataMap.put(key, value)
          val sender = writeMessage.sender.asInstanceOf[ActorRef]
          sendTo(new DictionaryConfig.ResultMessage(self, value), sender)
        case readMessage: DictionaryConfig.ReadMessage =>
          val value  = dataMap.get(readMessage.key)
          val sender = readMessage.sender.asInstanceOf[ActorRef]
          sendTo(new DictionaryConfig.ResultMessage(self, value), sender)
        case _: DictionaryConfig.EndWorkMessage =>
          printf(BenchmarkRunner.argOutputFormat, "Dictionary Size", dataMap.size)
          exit()
        case _ =>
          System.err.println("Unsupported message: " + msg)
      }
    }
  }

}
