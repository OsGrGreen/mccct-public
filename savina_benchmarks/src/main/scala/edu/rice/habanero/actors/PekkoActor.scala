package mccct.actors

import java.util.concurrent.atomic.AtomicBoolean

import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, CoordinatedShutdown}
import com.typesafe.config.{Config, ConfigFactory}

import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}
import scala.util.Properties._
import scala.util.{Failure, Success}
import java.util.concurrent.CountDownLatch
import scala.compiletime.uninitialized

import mccct._
import mccct.given

import gears.async
import gears.async.{Cancellable, Scheduler}
import gears.async.Async.await

import gears.async.default.given

trait ExitMsg

/** @author
  *   <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
  */
abstract class PekkoActor[MsgType] extends Actor {

  private val startTracker = new AtomicBoolean(false)
  private val exitTracker  = new AtomicBoolean(false)

  val controller: Controller = new Controller(Controller.rootController, false, ControllerType.Actor)

  final def receive = {
    case msg: StartPekkoActorMessage =>
      if (hasStarted()) {
        msg.resolve(value = false)
      } else {
        start()
        msg.resolve(value = true)
      }
    case msg: Any =>
      if (!exitTracker.get()) {
        Scheduler.submit(controller)
        controller.await()
        process(msg.asInstanceOf[MsgType])
        Scheduler.finish(controller)
      }
  }

  def process(msg: MsgType): Unit

  def sendTo(msg: MsgType, to: ActorRef) = msg match {
    case m: (StartPekkoActorMessage) =>
      to ! m
    case m: Any =>
      Scheduler.submit(controller)
      controller.await()
      to ! m
      Scheduler.finish(controller)
  }

  def send(msg: MsgType) = {
    self ! msg
  }

  final def hasStarted() = {
    startTracker.get()
  }

  final def start() = {
    if (!hasStarted()) {
      Scheduler.runningActors.getAndIncrement()
      onPreStart()
      onPostStart()
      startTracker.set(true)
    }
  }

  /** Convenience: specify code to be executed before actor is started
    */
  protected def onPreStart() = {}

  /** Convenience: specify code to be executed after actor is started
    */
  protected def onPostStart() = {}

  final def hasExited() = {
    exitTracker.get()
  }

  final def exit() = {
    val success = exitTracker.compareAndSet(false, true)
    if (success) {
      onPreExit()
      context.stop(self)
      onPostExit()
    }
  }

  /** Convenience: specify code to be executed before actor is terminated
    */
  protected def onPreExit() = {}

  /** Convenience: specify code to be executed after actor is terminated
    */
  protected def onPostExit() = {
    Scheduler.runningActors.getAndDecrement()
    Scheduler.finish(controller, false)
  }

  override def toString(): String = s"$controller"
}

protected class StartPekkoActorMessage(promise: Promise[Boolean]) {
  def await() = {
    Await.result(promise.future, Duration.Inf)
  }

  def resolve(value: Boolean) = {
    promise.success(value)
  }
}

object PekkoActorState {

  private var actorGate      = new CountDownLatch(1)
  private val mailboxTypeKey = "actors.mailboxType"
  private var config: Config = null

  def setPriorityMailboxType(value: String) = {
    System.setProperty(mailboxTypeKey, value)
  }

  def reset(): Unit =
    actorGate = new CountDownLatch(1)

  def initialize(): Unit = {

    val corePoolSize        = getNumWorkers("actors.corePoolSize", 4)
    val maxPoolSize         = getNumWorkers("actors.maxPoolSize", corePoolSize)
    val priorityMailboxType = getStringProp(mailboxTypeKey, "Pekko.dispatch.SingleConsumerOnlyUnboundedMailbox")

    val customConfigStr = """
    pekko {
      log-dead-letters-during-shutdown = off
      log-dead-letters = off

      actor {
        creation-timeout = 6000s
        default-dispatcher {
          fork-join-executor {
            parallelism-min = %s
            parallelism-max = %s
            parallelism-factor = 1.0
          }
        }
        default-mailbox {
          mailbox-type = "org.apache.pekko.dispatch.SingleConsumerOnlyUnboundedMailbox"
        }
        prio-dispatcher {
          mailbox-type = "%s"
        }
        typed {
          timeout = 10000s
        }
      }
    }
                          """.format(corePoolSize, maxPoolSize, priorityMailboxType)

    val customConf = ConfigFactory.parseString(customConfigStr)
    config = ConfigFactory.load(customConf)
  }

  private def getNumWorkers(propertyName: String, minNumThreads: Int): Int = {
    val rt: Runtime = java.lang.Runtime.getRuntime

    getIntegerProp(propertyName) match {
      case Some(i) if i > 0 => i
      case _                => {
        val byCores = rt.availableProcessors() * 2
        if (byCores > minNumThreads) byCores else minNumThreads
      }
    }
  }

  private def getIntegerProp(propName: String): Option[Int] = {
    try {
      propOrNone(propName) map (_.toInt)
    } catch {
      case _: SecurityException | _: NumberFormatException => None
    }
  }

  private def getStringProp(propName: String, defaultVal: String): String = {
    propOrElse(propName, defaultVal)
  }

  def newActorSystem(name: String): ActorSystem = {
    ActorSystem(name, config)
  }

  def startActor(actorRef: ActorRef) = {

    val promise                         = Promise[Boolean]()
    val message: StartPekkoActorMessage = new StartPekkoActorMessage(promise)
    actorRef ! message

    val f = promise.future
    f.onComplete {
      case Success(value) => actorGate.countDown()
      case Failure(e)     => e.printStackTrace
    }

  }

  def awaitTermination(system: ActorSystem) = {
    try {
      actorGate.await()
      Scheduler.awaitTermination()
      assert(Scheduler.getSchedule().size > 0)
      assert(Scheduler.runningActors.get() == 0)
      val cs         = CoordinatedShutdown(system)
      val terminated = cs.run(CoordinatedShutdown.ActorSystemTerminateReason)
      Await.result(terminated, 30.seconds)
      terminated.onComplete {
        case Success(_) =>
          println("Terminated successfully")
        case Failure(ex) =>
          println(s"Failed to terminate: $ex")
      }
    } catch {
      case ex: InterruptedException => {
        ex.printStackTrace()
      }
    }
  }
}
