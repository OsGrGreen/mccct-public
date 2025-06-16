package mucct

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.{Lock, ReentrantLock, Condition}

import scala.util.{Try, Success, Failure}

import gears.async
import gears.async.default.given

given rootTask: Task = new Task(null) {
  def run() = ???
}

object Future {

  def apply[T](body: Task ?=> T)(using a: async.Async, parent: Task): Future[T] =
    val p = async.Future.Promise[T]()
    val taskController = async.Future.Promise[Boolean]()
    val task = new Task(parent) {
      def run() =
        taskController.awaitResult  // wait for scheduler to let the task start
        // using a promise is enough, since a task is started only once
        val result = body(using this)
        p.complete(Success(result))
    }
    // start task on virtual thread
    Thread.ofVirtual().start(task)
    // submit new ready task to CCT scheduler
    Scheduler.submit(task, taskController)
    new Future(p.asFuture)

}

abstract class Task(val parent: Task, init: Boolean = true) extends Runnable {
  val ready = AtomicBoolean(init)
  final def isRoot = parent == null
  final def isReady(): Boolean = ready.get()
  def run(): Unit
}

class Future[T](underlying: async.Future[T]) {
  def await(using ac: async.Async, task: Task): T = {
    val resultOrFailure = underlying.awaitResult
    // surrounding task is now ready
    task.ready.set(true)

    val taskController = async.Future.Promise[Boolean]()

    // inform CCT scheduler --> should move task to ready queue
    Scheduler.submit(task, taskController)

    // wait for scheduler to resume task
    taskController.awaitResult

    resultOrFailure.get
  }
}

object Scheduler {
  type Controller = async.Future.Promise[Boolean]

  private var done = false
  private var cnt = 1
  private val lock: Lock = new ReentrantLock
  private val queueChange = lock.newCondition()
  private val termination = lock.newCondition()

  private var readyTasks: List[(Task, Controller)] = List()

  def start(initCnt: Int) =
    cnt = initCnt
    val schedulerTask = new Runnable {
      def run() =
        while (!done) {
          lock.lock()
          try
            // wait for new tasks to be submitted
            println("scheduler waiting for change in task queue...")
            queueChange.awaitUninterruptibly()
            println(s"scheduler: size of task queue = ${readyTasks.size}")

            // log first task in queue
            val (task, ctrl) = readyTasks.head
            readyTasks = readyTasks.tail // remove task
            println(s"scheduler signalled (cnt=$cnt) with first task: ${task.toString()}")

            cnt -= 1
            if cnt == 0 then
              done = true
              termination.signal() // signal main thread

            // let task start
            println(s"scheduler signalling task $task to continue")
            ctrl.complete(Success(true))
          finally
            lock.unlock()
        }
    }
    Thread.ofPlatform().start(schedulerTask)

  def awaitTermination() =
    lock.lock()
    try
      if !done then termination.awaitUninterruptibly()
    finally
      lock.unlock()

  def submit(task: Task, taskController: async.Future.Promise[Boolean]): Unit =
    lock.lock()
    try
      readyTasks = (task, taskController) :: readyTasks
      queueChange.signal()
    finally
      lock.unlock()
}
