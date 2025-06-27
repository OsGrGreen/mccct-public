package mccct
package test

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicReference

import gears.async.Async
import gears.async.default.given

@RunWith(classOf[JUnit4])
class SchedulerTests {

  @Test
  def resetTest(): Unit = {
    Scheduler.start()

    var list:AtomicReference[List[Int]] = AtomicReference(List()) 

    Async.blocking:
      val f = Future {
        val fut = Future {
          list.updateAndGet(curr => 1 :: curr)
        }
        val res = fut.await
        list.updateAndGet(curr => 2 :: curr)
      }
    
    Scheduler.awaitTermination()
    assert(!list.get().isEmpty)
    Scheduler.reset()
    assert(Scheduler.getSchedule().isEmpty)
    assert(!Scheduler.getDone())
  }

}

