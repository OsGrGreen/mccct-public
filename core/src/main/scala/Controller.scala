package mccct

import java.util.concurrent.CyclicBarrier

class Controller(val parent: Controller, val isEnd: Boolean = false) {
  val id: Id = Id(parent, isEnd)

  private val schedulerBarrier = new CyclicBarrier(2)

  def await() = schedulerBarrier.await()

  def reset() = schedulerBarrier.reset()

  private val conditionBarrier = new CyclicBarrier(2)

  def awaitCondition() = conditionBarrier.await()

  def resetCondition() = conditionBarrier.reset()

  final def isRoot = parent == null // Signifies if it is root, which means that it controls the main thread

}
