package mccct

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.locks.{Lock, ReentrantLock, Condition}

import scala.util.{Try, Success, Failure}
import gears.async
import gears.async.default.given

import scala.util.control.NonFatal
import java.io.{File, FileWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

given rootTask: Task = new Task(null) {
  def run() = ???
}

object Future {

  /** Function for parent task to add its .0. child task
    *
    * When the .0. task is completed the scheduler and user knows for sure that the parent is also complete (code wise)
    * This enables algorithms to force a future to complete before allowing other futures to run
    *
    * The `submitChild` function is almost a replica of the apply-function with the main difference being that the .0.
    * child does not do anything The added child will only await its execution, and when it is allowed to continue it
    * will finish()
    *
    * @param parent,
    *   the task that is submitting the child task (will have this as its last child task)
    * @param a
    *   async context
    */
  private def submitChild(parent: Task)(using a: async.Async): Unit = {
    val childController = async.Future.Promise[Boolean]()  // Controller for the scheduler to allow execution
    val endChild        = new Task(parent, isEnd = true) { // The task that is executed on a new thread
      def run() = {
        childController.awaitResult // Wait for scheduler to signal the controller to execute
        Scheduler.finish()          // Do nothing and finish()
      }
    }
    Thread.ofVirtual().start(endChild)          // Start this .0. child task on a new virtual thread
    Scheduler.submit(endChild, childController) // Submit the child task to the scheduler
  }

  def apply[T](body: Task ?=> T)(using a: async.Async, parent: Task): Future[T] =
    val p              = async.Future.Promise[T]()
    val taskController = async.Future.Promise[Boolean]()
    val task           = new Task(parent) {
      def run() =
        taskController.awaitResult // Wait for scheduler to let the task start
        // Using a promise is enough, since a task is started only once
        // Try to execute the body
        try
          val result = body(using this)
          // Schedule the end child before completing this future. It seems to behave more consistently
          submitChild(this)
          // Signal the scheduler that this function has finished. This will decrement the cnt by one and possibly terminate the scheduler
          Scheduler.finish()
          p.complete(Success(result))
        catch // If an error is encountered then notify the scheduler of this
          case NonFatal(e) =>
            // Call the throwError method, which increments the number of exceptions and finishes this task
            Scheduler.throwError(e)
            p.complete(Failure(e)) // Complete the promise/future as a failure
    }
    // Start task on virtual thread
    Thread.ofVirtual().start(task)
    // Submit new ready task to CCT scheduler
    Scheduler.submit(task, taskController)
    new Future(p.asFuture)
}

abstract class Task(val parent: Task, init: Boolean = true, isEnd: Boolean = false) extends Runnable {
  val ready                    = AtomicBoolean(init)
  final def isRoot             = parent == null // Signifies if it is root, which means that it is the main thread
  var id: Id                   = Id(parent, isEnd)
  final def isReady(): Boolean = ready.get()
  def run(): Unit
  final def isTop: Boolean = parent == rootTask

  override def toString(): String = s"[${id.getId()},${id.getNumChildren()} , ${isRoot}, ${isTop}]"
}

class Future[T](underlying: async.Future[T]) {

  def value: Option[Try[T]] = underlying.poll()

  def isCompleted: Boolean = underlying.poll().nonEmpty

  def await(using ac: async.Async, task: Task): T = {

    /** Signal the scheduler that we are waiting for something If the task calling await is top-level, then `task` will
      * be the root task Waiting for a top-level task means that the scheduler is in a "stuck" state and must execute
      * the awaited task before it is able to continue
      */
    Scheduler.stuckSignal(task)
    val resultOrFailure =
      underlying.awaitResult // Wait for the underlying future (the one that is awaited) to finish before continueing
    // surrounding task is now ready
    task.ready.set(true)

    val taskController = async.Future.Promise[Boolean]()

    // inform CCT scheduler --> should move task to ready queue
    Scheduler.submit(
      task,
      taskController,
      false
    ) // Since the cnt of this task has already been accounted for do not increase the cnt again when this task is resubmitted to the scheduler

    // wait for scheduler to resume task
    taskController.awaitResult

    /** Signal the scheduler that it is no longer in a stuck state If a top level task was awaited then the Scheduler
      * should now suspend execution until all top-level tasks have been submitted to the scheduler or the scheduler
      * reaches another stuck state
      *
      * Also allows the scheduler to terminate
      */
    Scheduler.noLongerStuck(task)

    resultOrFailure.get
  }
}

object Scheduler {
  type Controller = async.Future.Promise[Boolean]

  private var done        = false
  private var debug       = false
  private val numErrors   = AtomicInteger(0)
  private var cnt         = 0 // The number of currently running tasks
  private val lock: Lock  = new ReentrantLock
  private val queueChange = lock
    .newCondition() // Used to control the scheduler. Is either signaled when a new task is added to the readyTasks list or if the scheduler should terminate
  private val termination =
    lock.newCondition() // Used by the main thread to wait until all tasks have finished executing
  /** Used to signal when execution from a stuck state must continue */
  private val stuckState = lock.newCondition()

  private var readyTasks: List[(Task, Controller)] =
    List() // The list of readyTasks in which the exploration algorithm can choose one to execute

  private var schedule: List[String] = List() // The recorded schedule

  /** Determines if all top-level tasks have been loaded. If true, then the scheduler knows that it may terminate and
    * that all future tasks has to be the children of current tasks
    */
  private var hasAllTasks: Boolean = false

  def start(alg: ExplorationAlgorithm = RandomWalk, shouldPrint: Boolean = false) =
    Scheduler.reset() // In case the scheduler has been used before, reset it so no information is carried over
    debug = shouldPrint

    val schedulerTask = new Runnable {
      def run() =
        while (true) {
          lock.lock()
          try
            // If hasAllTasks is false, then the main thread can still load and submit more top-level tasks
            // In this case the scheduler will wait until it must execute, to give all top-level tasks an equal chance to be executed
            // If hasAllTasks is true, then no more top-level tasks will be started. This means that new tasks will only be a product/child of current tasks.
            // Therefore, we can continue execution until we terminate
            if !hasAllTasks then
              if debug then println("scheduler waiting to get unstuck")
              stuckState.awaitUninterruptibly()

            // If we have tasks in the queue we do not need to wait
            // Since there is no guarantee that there will be other tasks added to the queue if it is non-empty
            // Therefore, the scheduler should make a choice
            // Furthermore, it is possible that the queueChange signal for termination has been sent at the end of the while loop
            // In this case we will get no more queueChange signals, therefore the scheduler must be able to skip the await (or it gets stuck)
            if (readyTasks.size == 0 && !hasFinished) {
              if debug then println("Waiting for queueChange")
              queueChange.awaitUninterruptibly()
            }

            // If the scheduler reaches this one of two possbilities must be true
            // Either readyTasks is empty, which means that the queueChange signal was triggered because the scheduler should terminate
            // Or readyTasks is non-empty and the scheduler should continue execution
            if hasFinished then
              done = true
              termination.signal()
              return
            if debug then println(s"scheduler: size of task queue = ${readyTasks.size}")

            val executionTasks = getNextTask(alg) // Get the next task as specified by the algorithm and its controller
            readyTasks =
              readyTasks diff executionTasks // Remove the task (and its controller) from the readyTasks list, since the same task should not be allowed to be started more than once

            executionTasks.foreach { (task, ctrl) =>
              if debug then
                println(s"scheduler signalled (cnt=$cnt) with task: ${task.toString()}")

                println(s"scheduler signalling task $task to continue")

              // let task start
              ctrl.complete(Success(true)) // Complete the promise allowing the task to execute
              schedule = task.id
                .getId() :: schedule // Add the id of the task to the history/schedule of executed tasks (this run of the schedule)
            }
          finally lock.unlock()
        }
    }
    Thread.ofPlatform().start(schedulerTask) // Start the scheduler on a new thread

  /** Tail-recursive function that returns the next task to execute
    *
    * @param alg
    *   The algorithm which determines what task to execute and how to choose it
    * @return
    *   the task to be executed
    */
  private def getNextTask(alg: ExplorationAlgorithm): List[(Task, Controller)] =
    alg.getNext(readyTasks) match
      case Some(l) => l
      // If algorithm returns None it indicates that the algorithm is not satisfied with the readyTasks list.
      case None => { // There is non-empty queue, however it has the wrong elements
        queueChange
          .awaitUninterruptibly() // Therefore, the scheduler should wait for an update until the algorithm returns a non-empty option
        if hasFinished then // Should not be possible
          assert(false)     // Since readyTasks should always be non-empty if this line is reached
        getNextTask(alg)
      }

  def awaitTermination() =
    lock.lock()
    try
      // The end of the main thread has been reached
      hasAllTasks = true // All top level tasks must now be available for the scheduler
      stuckState.signal()
      if hasFinished
      then // If we have finished before calling awaitTermination Scheduler will be waiting for queueChange
        queueChange.signal() // Signal the Scheduler a queueChange to get termination signal
      termination
        .awaitUninterruptibly() // Since the main thread has the lock, the termination signal can not be sent before the await
    finally
      schedule =
        schedule.reverse // Since the tasks are prepended to the schedule history, the list must be reversed to get history in the correct order
      lock.unlock()

  /** Signals the scheduler to execute if in stuck state
    *
    * If task is a root task, then the awaited task must be a top level task In this case signal the scheduler to
    * execute until other instructions are given
    * @param task
    *   the task that has been suspended
    */
  private[mccct] def stuckSignal(task: Task) =
    lock.lock()
    try
      // If parent is null, then this must be a root task
      if task.isRoot then   // Which means that execution must continue until the awaited top-level task is completed
        hasAllTasks = true  // Allow scheduler to execute until `hasAllTasks` is set to false
        cnt += 1            // Make sure that the scheduler can not finish while waiting for a top-level task
        stuckState.signal() // Signal the lock that the scheduler must continue
    finally
      lock.unlock()

  /** Signals the scheduler to wait until stuck or all top-level tasks have been loaded
    *
    * If task is a root task, then the awaited task must be a top level task In this case signal the scheduler to wait
    * until notified otherwise
    * @param task
    *   the task that has been suspended
    */
  private[mccct] def noLongerStuck(task: Task) =
    lock.lock()
    try
      // If parent is null, then this must be a root task
      if task.isRoot then // Which means that execution must continue until the awaited top-level task is completed
        hasAllTasks =
          false // In this case we have now executed the blocking task, and may once again wait until we have loaded all top-level tasks (or become stuck)
        cnt -= 1 // Scheduler may now finish when possible
    finally
      lock.unlock()

  private def hasFinished: Boolean =
    cnt == 0 && readyTasks.size == 0 && hasAllTasks

  private[mccct] def submit(
      task: Task,
      taskController: async.Future.Promise[Boolean],
      shouldIncrement: Boolean = true
  ): Unit =
    lock.lock()
    try
      if shouldIncrement
      then // Should the task be counted as a new task or not (for example if it has already been started but had to wait)
        cnt += 1
      readyTasks = (task, taskController) :: readyTasks
      queueChange.signal()
    finally lock.unlock()

  private[mccct] def getSchedule(): List[String] = schedule

  private[mccct] def finish(): Unit =
    lock.lock()
    try
      cnt -= 1
      if hasFinished then    // If this was the last task to complete and all tasks have been loaded then
        queueChange.signal() // If the Scheduler is in a state which should terminate, signal the queueChange
    finally lock.unlock()

  def reset(): Unit =
    lock.lock()
    try
      done = false
      readyTasks = List()
      cnt = 0
      rootTask.id.reset()
      schedule = List()
      hasAllTasks = false
      debug = false
      numErrors.getAndSet(0)
    finally lock.unlock()

  def getDone(): Boolean = done

  def getNumErrors(): Int = numErrors.get()

  def checkErrors(assertion: Boolean = true, outputFile: String = "trace.txt"): Boolean = {
    try
      if debug then println("Checking user assertion")
      assert(assertion) // Check if input assertion holds

      if debug then println("Checking number of errors encountered")
      assert(numErrors.get() == 0) // Check that no errors were encountered
      true                         // True representing that no errors were encountered
    catch                          // If any of the assertions fails
      case NonFatal(e) =>
        if debug then
          println("Exception was encountered")
          println(s"Number of errors: ${numErrors.get()}")
        writeSchedule(outputFile) // Write the schedule that failed to file
        false                     // False representing that some error was encountered
  }

  private[mccct] def throwError(e: Throwable): Unit = {
    numErrors.incrementAndGet() // If an error is thrown, increment the number of errors we have encountered
    finish()                    // Then signal the scheduler that this task has finished (allowing for termination)
  }

  def readSchedule(fileName: String): List[String] = {
    var fileData       = ""                                 // The read data
    val bufferedSource = scala.io.Source.fromFile(fileName) // Get the data as a buffered source
    for (lines <- bufferedSource.getLines()) {
      fileData = fileData + lines // Append each line to the fileData
    }
    bufferedSource.close()      // Close the file
    fileData.split(", ").toList // Split the data into the correct strings
  }

  private[mccct] def writeSchedule(fileName: String): Unit = {
    if debug then println("Writing schedule to file")

    if !done then
      // If the scheduler has not finished by the time this function is called, then it means that the schedule is in the reverse order
      schedule = schedule.reverse
    var file = fileName

    // If no fileName was specified then generate a file name from the current date and time
    if file == "" then
      val currentDateTime = LocalDateTime.now()
      val formatter       = DateTimeFormatter.ofPattern("dd-MM-yy-HH-mm-ss")
      val formattedDate   = currentDateTime.format(formatter)
      file = "trace-" + formattedDate + ".txt"

    val fileWriter = new FileWriter(new File(file)) // Open and create a new file with the given name
    // An option to this is to write each task on a new line, this would make parsing the file into a oneliner, however long files can be a bit hard to work with.
    schedule.foreach { s =>
      fileWriter.write(s + ", ") // Write the task ids seperated by ", "
    }
    fileWriter.close() // Close the file writer

    // Switch back the history schedule as it was before
    if !done then schedule = schedule.reverse
  }
}
