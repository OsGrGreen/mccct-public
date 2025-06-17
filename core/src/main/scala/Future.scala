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

  def submitChild(parent: Task)(using a: async.Async): Unit = {
      val childController = async.Future.Promise[Boolean]()
      val endChild =  new Task(parent, isEnd = true) {
        def run() = {
          childController.awaitResult
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

  override def toString(): String = s"[${id.getId()}]"
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
  private var targetSchedule: List[String] = List()

  private var schedule: List[String] = List()

  def start(initCnt: Int, initTargetSchedule: List[String]) =
    cnt = initCnt
    targetSchedule = initTargetSchedule
    val shouldFollow = !targetSchedule.isEmpty
    val schedulerTask = new Runnable {
      def run() =
        while (!done) {
          lock.lock()
          try
            if (readyTasks.size == 0){
              println("scheduler waiting for change in task queue...")
              queueChange.awaitUninterruptibly()
            }
            println(s"scheduler: size of task queue = ${readyTasks.size}")


            val (task, ctrl) = if shouldFollow then
              followSchedule()
            else
              val tmp = readyTasks.head
              readyTasks = readyTasks.tail
              tmp

            println(s"scheduler signalled (cnt=$cnt) with first task: ${task.toString()}")

            cnt -= 1
            if cnt == 0 then
              done = true
              termination.signal() // signal main thread

            // let task start
            println(s"scheduler signalling task $task to continue")
            ctrl.complete(Success(true))
            schedule = task.id.getId() :: schedule
          finally
            lock.unlock()
        }
    }
    Thread.ofPlatform().start(schedulerTask)

  def followSchedule(): (Task, async.Future.Promise[Boolean]) =
    val targetTask = targetSchedule.head //Take the id of the task we want to execute.

    // log first task in queue
    var target:List[(Task, Controller)] = readyTasks.filter((t,c) => t.id.getId() == targetTask)
    while (target.size == 0){ //If we did not currently find the target task, then it has not arrived yet
      queueChange.awaitUninterruptibly() //Wait until queue is updated
      target = readyTasks.filter((t,c) => t.id.getId() == targetTask) //See if the update added the target, otherwise repeat
      //In an ideal world we would only need to check the head, but I think it is possible that multiple tasks are added at the same time.
    }
    readyTasks = readyTasks diff target //Remove target from queue 
    targetSchedule = targetSchedule.tail //Remove head from schedule
    target.head //Take target task and control


  def awaitTermination() =
    lock.lock()
    try
      if !done then termination.awaitUninterruptibly()
    finally
      schedule = schedule.reverse
      lock.unlock()

  def submit(task: Task, taskController: async.Future.Promise[Boolean]): Unit =
    lock.lock()
    try
      readyTasks = (task, taskController) :: readyTasks
      queueChange.signal()
    finally
      lock.unlock()

  def getSchedule(): List[String] = schedule
}
