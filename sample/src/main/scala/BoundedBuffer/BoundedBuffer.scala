package mccct

import gears.async.Async
import gears.async.default.given
import scala.compiletime.uninitialized
import scala.concurrent.duration.{FiniteDuration, SECONDS}

object BoundedBuffer {

  val SIZE     = 1;
  val PRODS    = 4;
  val CONS     = 4;
  val MODCOUNT = 2;

  var buf: Buffer[Int] = uninitialized

  def runBuffer(): Unit = {
    buf = new BufferImpl[Int](SIZE)
    Async.blocking:
      for (_ <- 0 until PRODS) {
        Future {
          val p = new Producer(buf, MODCOUNT)
          p.run()
        }
      }
      for (_ <- 0 until PRODS) {
        Future {
          val c = new Consumer(buf)
          c.run()
        }
      }
  }

  @main
  def runTest(): Unit =
    CoverageTracker.reset()
    // Marker "2." does not exist and will never be hit, triggering an infinite loop
    val (coverageReport, failedSchedules) =
      CoverageTracker.trackCoverageIter(
        runBuffer(),
        RandomWalk,
        1,
        true,
        false
      )
    println(coverageReport);
    println(failedSchedules);

  @main
  def replayTest(): Unit =
    println("Starting..")
    Scheduler.start(FixedSchedule(Scheduler.readSchedule("trace_-13-10-25-14-46-47.txt")), sequential = true)
    runBuffer()
    Scheduler.awaitTermination()
    println(Scheduler.checkErrors(true))
}
