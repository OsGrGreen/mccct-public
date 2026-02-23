package mccct

import gears.async.Async
import gears.async.default.given

object Deadlock {

  // Create locks
  val L1 = new SchedulerLock()
  val L2 = new SchedulerLock()
  val L3 = new SchedulerLock()

  /*@main*/
  def run(): Unit =
    println("Main running...")
    Scheduler.start(FifoAlgorithm)

    Async.blocking:
      Future {
        L1.lock()
        try
          Thread.sleep(350)
          L2.lock()
          try
            println("Got both locks 1")
          finally
            L2.unlock()
        finally L1.unlock()
      }
      Future {
        L2.lock()
        try
          Thread.sleep(100)
          L3.lock()
          try
            println("Got both locks 2")
          finally
            L3.unlock()
        finally L2.unlock()
      }
      Future {
        L3.lock()
        try
          Thread.sleep(800)
          L1.lock()
          try
            println("Got both locks 3")
          finally
            L1.unlock()
        finally L3.unlock()
      }

    Scheduler.awaitTermination()
}
