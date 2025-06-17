package mucct

import gears.async.Async
import gears.async.default.given

object Main {
  /*@main*/ def run(): Unit =
    println("Main running...")
    Scheduler.start(3, List("3.","1.","2."))

    Async.blocking:
      Future {
        println("task 1 running...")
      }
      Future {
        println("task 2 running...")
      }
      Future {
        println("task 3 running...")
      }

    Scheduler.awaitTermination()
}
