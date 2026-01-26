package mccct

import java.util.concurrent.CyclicBarrier
import gears.async.Cancellable

class Controller(val parent: Controller, val isEnd: Boolean = false) {
  val id: Id = Id(parent, isEnd)

  var ready: Boolean = false

  var thread: Thread = null

  var timeoutTask: Option[Cancellable] = None

  def addTimeoutTask(timeout: Option[Cancellable]): Unit =
    timeoutTask.foreach(_.cancel())
    timeoutTask = timeout

  private val schedulerBarrier = new CyclicBarrier(2)

  def await() = schedulerBarrier.await()

  def reset() = schedulerBarrier.reset()

  private val conditionBarrier = new CyclicBarrier(2)

  def awaitCondition() =
    if !conditionBarrier.isBroken then conditionBarrier.await()

  def resetCondition() = conditionBarrier.reset()

  final def isRoot = parent == null // Signifies if it is root, which means that it controls the main thread

  override def toString(): String = s"[${id.getId()}, ${thread}]"

  override def equals(x: Any): Boolean = x match
    case ctrl: Controller => ctrl.id.getId() == this.id.getId()
    case _                => false

  private[mccct] def startThread(task: Task): Thread =
    thread = Thread.ofVirtual().start(task)
    thread

}
