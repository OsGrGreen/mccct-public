package mccct

import gears.async.Async
import gears.async.default.given

object NestedFuture {
  @main def run(): Unit =
    println("NestedFuture running...")
    Scheduler.start(List("1.", "1.1.", "1.1.1.", "1.1.1.0.", "1.1."))

    Async.blocking:
      Future {
        println("task 1 running...")
        val fut = Future {
          println("nested task 2 running...")
          val nestedFut = Future {
            println("nested task 3 running...")
            5
          }
          nestedFut.await
        }
        val res = fut.await
        println(s"task 1 continuing (res=$res)...")
      }

    Scheduler.awaitTermination()
    println(s"The recorded schedule was: ${Scheduler.getSchedule()}")
}
