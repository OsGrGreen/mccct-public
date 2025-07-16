package mccct

import gears.async
import gears.async.default.given
import java.util.concurrent.locks.{Lock, ReentrantLock, Condition}
import java.util.concurrent.CyclicBarrier

class SchedulerLock(val superLock: ReentrantLock) {

  val lockLock: Lock =
    new ReentrantLock // Lock making sure that only one task can check if it can acquire lock, and then acquire lock at the same time

  def lock(hasPrio: Boolean = false)(using ac: async.Async, task: Task): Unit =
    // If the scheduler is running in sequential mode it can become stuck if:
    // 1. One non-running thread has the lock
    // 2. Starts a new thread that locks the lock
    // This will make the running thread stall until the lock is unlocked (which will never happen in sequential execution)
    // For parallel execution we can ignore this and continue as usual
    lockLock.lock()
    // Some tasks, like the timeout timer should not get affected by the sequential execution, therefore they should be able to skip this step and continuously wait
    while (superLock.isLocked() && Scheduler.isSequential && !hasPrio) {
      // Decrement the counter, which can allow another task to start.
      Scheduler.decrementSequential(task)
      // Create a new taskController for making the thread wait.
      val taskController = async.Future.Promise[Boolean]()
      // Submit the task and its controller, sending a queuechange signal,
      // and allows the same thread to try to acquire the lock again
      Scheduler.submit(
        task,
        taskController,
        false
      ) // Since the cnt of this task has already been accounted for do not increase the cnt again when this task is resubmitted to the scheduler

      lockLock.unlock()
      // wait for scheduler to resume task
      taskController.awaitResult
      lockLock.lock()
    }
    // In parallel mode we can lock as usual, in sequential mode there is no chance for race condition since only one task is running at a time.
    superLock.lock()
    lockLock.unlock()

  def unlock()(using task: Task): Unit =
    lockLock.lock()
    try
      superLock.unlock()
    finally lockLock.unlock()

  def newCondition(): SchedulerCondition = new SchedulerCondition(this)

}

class SchedulerCondition(val superLock: SchedulerLock) {

  val queueLock: Lock = new ReentrantLock // Lock for the awaitQueue
  // Queue in which order to signal barriers, with a deterministic schedule should be deterministic
  var awaitQueue: List[CyclicBarrier] =
    List()

  def await()(using ac: async.Async, task: Task): Unit = {
    // Add the barrier to the queue of conditions that are waiting to be signaled
    addToQueue(task.barrier)
    // Unlock the lock that the condition is set to track
    superLock.unlock()
    // When await is called, another task should be able to be started
    Scheduler.decrementSequential(task)
    // Wait until this task has been signaled to continue
    task.barrier.await()
    // Create a new taskController and wait until the scheduler signals for this task to be resumed
    val taskController = async.Future.Promise[Boolean]()
    // inform CCT scheduler --> should move task to ready queue
    Scheduler.submit(
      task,
      taskController,
      false
    ) // Since the cnt of this task has already been accounted for do not increase the cnt again when this task is resubmitted to the scheduler
    // wait for scheduler to resume task
    taskController.awaitResult
    superLock.lock()
  }

  def addToQueue(barrier: CyclicBarrier): Unit =
    queueLock.lock()
    try
      if !awaitQueue.contains(barrier) then awaitQueue = barrier :: awaitQueue
    finally
      queueLock.unlock()

  /** Signals the first tasks barrier in the queue to stop waiting and continue execution
    */
  def signalFirstInQueue()(using task: Task): Unit =
    queueLock.lock()
    try
      if awaitQueue.nonEmpty then
        val bar = awaitQueue.head
        // Wait until both the lock and the condition has signaled the barrier
        // Should always be done after the condition has called `await`
        bar.await()
        // Only reset barrier in lock, otherwise lock can become stuck
        bar.reset()
        awaitQueue = awaitQueue.tail
    finally
      queueLock.unlock()

  /** A function that signals all waiting tasks to continue Is used to mirror the behaviour of `signalAll()`
    */
  def signalQueue(): Unit =
    queueLock.lock()
    try
      awaitQueue.map(b =>
        b.await() // Wait for the barrier
        b.reset() // Then reset it allowing it to be reused
      )
      awaitQueue = List()
    finally queueLock.unlock()

  def signal()(using task: Task): Unit = signalFirstInQueue()

  def signalAll(): Unit = signalQueue()

}
