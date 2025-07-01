package mccct

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.{Lock, ReentrantLock, Condition}

import scala.util.{Try, Success, Failure}
import gears.async
import gears.async.default.given

given rootTask: Task = new Task(null) {
  def run() = ???
}

object Future {

  private def submitChild(parent: Task)(using a: async.Async): Unit = {
      val childController = async.Future.Promise[Boolean]()
      val endChild =  new Task(parent, isEnd = true) {
        def run() = {
          childController.awaitResult
          Scheduler.finish()
        }
      }
      Thread.ofVirtual().start(endChild)
      Scheduler.submit(endChild, childController)
  }

  def apply[T](body: Task ?=> T)(using a: async.Async, parent: Task): Future[T] =
    val p = async.Future.Promise[T]()
    val taskController = async.Future.Promise[Boolean]()
    val task = new Task(parent) {
      def run() =
        taskController.awaitResult  // wait for scheduler to let the task start
        // using a promise is enough, since a task is started only once
        val result = body(using this)
        submitChild(this) //Schedule the end child before completing this future. It seems to behave more consistently
        Scheduler.finish()
        p.complete(Success(result))
    }
    // start task on virtual thread
    Thread.ofVirtual().start(task)
    // submit new ready task to CCT scheduler
    Scheduler.submit(task, taskController)
    new Future(p.asFuture)
}

abstract class Task(val parent: Task, init: Boolean = true, isEnd: Boolean = false) extends Runnable {
  val ready = AtomicBoolean(init)
  final def isRoot = parent == null
  var id:Id = Id(parent, isEnd)
  final def isReady(): Boolean = ready.get()
  def run(): Unit
  final def isTop: Boolean = parent == rootTask

  override def toString(): String = s"[${id.getId()},${id.getNumChildren()} , ${isRoot}, ${isTop}]"
}

class Future[T](underlying: async.Future[T]) {
  def await(using ac: async.Async, task: Task): T = {
    /** Signal the scheduler that we are waiting for something
     *  If the task calling await is top-level, then `task` will be the root task
     *  Waiting for a top-level task means that the scheduler is in a "stuck" state
     *  and must execute the awaited task before it is able to continue
     */
    Scheduler.stuckSignal(task)
    val resultOrFailure = underlying.awaitResult
    // surrounding task is now ready
    task.ready.set(true)

    val taskController = async.Future.Promise[Boolean]()

    // inform CCT scheduler --> should move task to ready queue
    Scheduler.submit(task, taskController, false)

    // wait for scheduler to resume task
    taskController.awaitResult
    /** Signal the scheduler that it is no longer in a stuck state
     *  If a top level task was awaited then the Scheduler should now suspend execution
     *  until all top-level tasks have been submitted to the scheduler or
     *  the scheduler reaches another stuck state
     * 
     *  Also allows the scheduler to terminate 
     */  
    Scheduler.noLongerStuck(task)

    resultOrFailure.get
  }
}

object Scheduler {
  type Controller = async.Future.Promise[Boolean]

  private var done = false
  private var cnt = 0
  private val lock: Lock = new ReentrantLock
  private val queueChange = lock.newCondition()
  private val termination = lock.newCondition()
  /** Used to signal when execution from a stuck state must continue   */
  private val stuckState = lock.newCondition()

  private var readyTasks: List[(Task, Controller)] = List()

  private var schedule: List[String] = List()

  /** Determines if scheduler is allowed to continue execution */
  private var hasAllTasks: Boolean = false

  def start(alg: ExplorationAlgorithm = RandomWalk) =
    Scheduler.reset()

    val schedulerTask = new Runnable {
      def run() =
        while (true) {
          lock.lock()
          try
            if !hasAllTasks then
              println("scheduler waiting to get unstuck")
              stuckState.awaitUninterruptibly()
              
            // If we have tasks in the queue we do not need to wait
            // Since there is no guarantee that there will be other tasks added to the queue if it is non-empty
            // Therefore, the scheduler should make a choice
            // Furthermore, it is possible that the queueChange signal for termination has been sent at the end of the while loop
            // In this case we will get no more queueChange signals, therefore the scheduler must be able to skip the await (or it gets stuck)
            if (readyTasks.size == 0 && !hasFinished){
              println("Waiting for queueChange")
              queueChange.awaitUninterruptibly()
            }
            
            // If the scheduler reaches this one of two possbilities must be true
            // Either readyTasks is empty, which means that the queueChange signal was triggered because the scheduler should terminate
            // Or readyTasks is non-empty and the scheduler should continue execution
            if hasFinished then
              done = true
              termination.signal()
              return 
                
            println(s"scheduler: size of task queue = ${readyTasks.size}")

            val (task, ctrl) = getNextTask(alg)
                  
            readyTasks = readyTasks diff List((task, ctrl))

            println(s"scheduler signalled (cnt=$cnt) with first task: ${task.toString()}")

            // let task start
            println(s"scheduler signalling task $task to continue")
            ctrl.complete(Success(true))
            schedule = task.id.getId() :: schedule
          finally
            lock.unlock()
        }
    }
    Thread.ofPlatform().start(schedulerTask)

  private def getNextTask(alg: ExplorationAlgorithm): (Task, Controller) =
    alg.getNext(readyTasks) match
      case Some((t,c)) => (t,c)
      case None => { // There is non-empty queue, however it has the wrong elements
        queueChange.awaitUninterruptibly()
        if hasFinished then // Should not be possible
          assert(false) // Since readyTasks should always be non-empty if this line is reached
        getNextTask(alg)
      }

  def awaitTermination() =
    lock.lock()
    try
      // The end of the main thread has been reached
      hasAllTasks = true // All top level tasks must now be available for the scheduler
      stuckState.signal()
      if hasFinished then // If we have finished before calling awaitTermination Scheduler will be waiting for queueChange
          queueChange.signal() // Signal the Scheduler a queueChange to get termination signal
      termination.awaitUninterruptibly() // Since the main thread has the lock, the termination signal can not be sent before the await
    finally
      schedule = schedule.reverse
      lock.unlock()

  /** Signals the scheduler to execute if in stuck state
   *  
   *  If task is a root task, then the awaited task must be a top level task
   *  In this case signal the scheduler to execute until other instructions are given
   * @param task the task that has been suspended
   */
  private[mccct] def stuckSignal(task: Task) = 
    lock.lock()
    try
      // If parent is null, then this must be a root task
      if task.isRoot then // Which means that execution must continue until the awaited top-level task is completed
        hasAllTasks = true // Allow scheduler to execute until `hasAllTasks` is set to false
        cnt += 1 // Make sure that the scheduler can not finish while waiting for a top-level task
        stuckState.signal() // Signal the lock that the scheduler must continue
    finally
      lock.unlock()

  /** Signals the scheduler to wait until stuck or all top-level tasks have been loaded
   *  
   *  If task is a root task, then the awaited task must be a top level task
   *  In this case signal the scheduler to wait until notified otherwise
   * @param task the task that has been suspended
   */
  private[mccct] def noLongerStuck(task: Task) = 
    lock.lock()
    try
      // If parent is null, then this must be a root task
      if task.isRoot then // Which means that execution must continue until the awaited top-level task is completed
        hasAllTasks = false // In this case we have now executed the blocking task, and may once again wait until we have loaded all top-level tasks (or become stuck)
        cnt -= 1 // Scheduler may now finish when possible
    finally
      lock.unlock()

  private def hasFinished: Boolean = 
    cnt == 0 && readyTasks.size == 0 && hasAllTasks
  
  private[mccct] def submit(task: Task, taskController: async.Future.Promise[Boolean], shouldIncrement: Boolean = true): Unit =
    lock.lock()
    try
      if shouldIncrement then
        cnt += 1
      readyTasks = (task, taskController) :: readyTasks
      queueChange.signal()
    finally
      lock.unlock()

  private[mccct] def getSchedule(): List[String] = schedule

  private[mccct] def finish(): Unit =
    lock.lock()
    try
      cnt -= 1
      if hasFinished then
        queueChange.signal() //If the Scheduler is in a state which should terminate, signal the queueChange
    finally
      lock.unlock()

  def reset(): Unit = 
    lock.lock()
    try
      done = false
      readyTasks = List()
      cnt = 0
      rootTask.id.reset()
      schedule = List()
      hasAllTasks = false
    finally
      lock.unlock()

  def getDone(): Boolean = done
}

