package mccct

import scala.util.control.NonFatal
import scala.util.{Try, Success, Failure}
import gears.async
import gears.async.{Cancellable, Scheduler}
import gears.async.default.given

object Finish {
  def apply[T](body: Task ?=> T)(using a: async.Async, parent: Task): Unit =
    val p          = async.Future.Promise[Integer]()
    val task: Task = new Task(Controller(parent.controller, controllerType = ControllerType.Finish)) {
      def run() =
        try
          // We must make a choice here if root
          if (parent.isRoot) {
            Scheduler.stuckSignal(parent.controller)
          }
          val result = body(using this)
          if (this.controller.totalChildren.get() != 0) {
            this.controller.await() // Wait for child to signal this
          }
          // Signal the scheduler that this function has finished. This will decrement the cnt by one and possibly terminate the scheduler
          Scheduler.noLongerStuck(parent.controller)
          p.complete(Success(1))
        catch // If an error is encountered then notify the scheduler of this
          case NonFatal(e) =>
            // Call the throwError method, which increments the number of exceptions and finishes this task
            Scheduler.throwError(e, this.controller)
            p.complete(Failure(e))
          case e =>
            Scheduler.throwError(e, this.controller)
            p.complete(Failure(e))
    }
    // Start task on virtual thread
    Scheduler.startThread(task)
    val result = p.asFuture.await

}

object Async {

  private def submitChild(parent: Controller)(using a: async.Async): Unit =
    val endChild = new Task(Controller(parent, isEnd = true)) { // The task that is executed on a new thread
      def run() = {
        try
          this.controller.await()           // Wait for scheduler to signal the controller to execute
          Scheduler.finish(this.controller) // Do nothing and finish()
        catch
          case e =>
            Scheduler.throwError(e, this.controller)
      }
    }
    Scheduler.startThread(endChild)       // Start this .0. child task on a new virtual thread
    Scheduler.submit(endChild.controller) // Submit the child task to the scheduler

  def apply[T](body: Task ?=> T)(using a: async.Async, parent: Task): Unit =
    val task: Task = new Task(Controller(parent.controller)) {
      def run() =
        try
          this.controller.await() // Wait for scheduler to let the task start
          // Using a promise is enough, since a task is started only once
          // Try to execute the body
          val result        = body(using this)
          val closest_finsh = this.controller.closestType(ControllerType.Finish)
          // Schedule the end child before completing this future. It seems to behave more consistently
          submitChild(this.controller)(using a)
          if (!closest_finsh.isRoot) then
            val finishVal = closest_finsh.totalChildren.decrementAndGet()
            if (finishVal == 0) {
              closest_finsh.await()
            }
          // Signal the scheduler that this function has finished. This will decrement the cnt by one and possibly terminate the scheduler
          Scheduler.finish(this.controller)
          // Have child resubmit finish when done

        catch // If an error is encountered then notify the scheduler of this
          case NonFatal(e) =>
            // Call the throwError method, which increments the number of exceptions and finishes this task
            Scheduler.throwError(e, this.controller)
          case e =>
            Scheduler.throwError(e, this.controller)
    }
    val closest_finsh = parent.controller.closestType(ControllerType.Finish)
    if (!closest_finsh.isRoot) then closest_finsh.totalChildren.incrementAndGet()
    Scheduler.startThread(task)
    Scheduler.submit(task.controller) // <- try variants below
}
