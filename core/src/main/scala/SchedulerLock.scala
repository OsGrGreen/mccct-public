package mccct

import gears.async
import gears.async.default.given
import java.util.concurrent.locks.{Lock, ReentrantLock}
import java.util.concurrent.CyclicBarrier

class SchedulerLock(val superLock: ReentrantLock = new ReentrantLock) {

  // Lock making sure that only one task can *atomically*
  // (a) check if it can acquire the lock, and then
  // (b) acquire the lock
  val lockLock: Lock = new ReentrantLock

  def lock(hasPrio: Boolean = false)(using ac: async.Async, task: Task): Unit =
    // If the scheduler is running in sequential mode it can become stuck if:
    // 1. One non-running thread has the lock
    // 2. Starts a new thread that locks the lock
    // This will make the running thread stall until the lock is unlocked (which will never happen in sequential execution)
    // For parallel execution we can ignore this and continue as usual
    lockLock.lockInterruptibly()
    // Some tasks, like the timeout timer should not get affected by the sequential execution, therefore they should be able to skip this step and continuously wait
    while (superLock.isLocked() && Scheduler.isSequential && !hasPrio) {
      // Decrement the counter, which can allow another task to start.
      Scheduler.decrementSequential(task.controller)
      // Create a new taskController for making the thread wait.
      // Submit the task and its controller, sending a queuechange signal,
      // and allows the same thread to try to acquire the lock again
      Scheduler.submit(
        task.controller,
        false
      ) // Since the cnt of this task has already been accounted for do not increase the cnt again when this task is resubmitted to the scheduler

      lockLock.unlock()
      // wait for scheduler to resume task
      task.controller.await()
      lockLock.lockInterruptibly()
    }
    // In parallel mode we can lock as usual, in sequential mode there is no chance for race condition since only one task is running at a time.
    superLock.lockInterruptibly()
    lockLock.unlock()

  def unlock()(using task: Task): Unit =
    lockLock.lockInterruptibly()
    try
      superLock.unlock()
    finally lockLock.unlock()

  def newCondition(): SchedulerCondition = new SchedulerCondition(this)

}

class SchedulerCondition(val superLock: SchedulerLock) {

  val queueLock: Lock = new ReentrantLock // Lock for the awaitQueue
  // Queue in which order to signal barriers, with a deterministic schedule should be deterministic
  var awaitQueue: List[Controller] =
    List()

  def await()(using ac: async.Async, task: Task): Unit = {
    // Add the barrier to the queue of conditions that are waiting to be signaled
    addToQueue(task.controller)
    // Unlock the lock that the condition is set to track
    superLock.unlock()
    // When await is called, another task should be able to be started
    Scheduler.decrementSequential(task.controller)
    // Wait until this task has been signaled to continue
    task.controller.awaitCondition()
    // Create a new taskController and wait until the scheduler signals for this task to be resumed
    // inform CCT scheduler --> should move task to ready queue
    Scheduler.submit(
      task.controller,
      false
    ) // Since the cnt of this task has already been accounted for do not increase the cnt again when this task is resubmitted to the scheduler
    // wait for scheduler to resume task
    task.controller.await()
    superLock.lock()
  }

  def addToQueue(barrier: Controller): Unit =
    queueLock.lockInterruptibly()
    try
      if !awaitQueue.contains(barrier) then awaitQueue = barrier :: awaitQueue
    finally
      queueLock.unlock()

  /** Signals the first tasks barrier in the queue to stop waiting and continue execution
    */
  def signalFirstInQueue(): Unit =
    queueLock.lockInterruptibly()
    try
      if awaitQueue.nonEmpty then
        val bar = awaitQueue.head
        // Wait until both the lock and the condition has signaled the barrier
        // Should always be done after the condition has called `await`
        bar.awaitCondition()
        // Only reset barrier when holding `queueLock`, otherwise lock can become stuck
        bar.resetCondition()
        awaitQueue = awaitQueue.tail
    finally
      queueLock.unlock()

  /** A function that signals all waiting tasks to continue. Used to mirror the behaviour of `signalAll()`.
    */
  def signalQueue(): Unit =
    queueLock.lockInterruptibly()
    try
      awaitQueue.map(b =>
        b.awaitCondition() // Wait for the barrier
        b.resetCondition() // Then reset it allowing it to be reused
      )
      awaitQueue = List()
    finally queueLock.unlock()

  def signal(): Unit = signalFirstInQueue()

  def signalAll(): Unit = signalQueue()

}
