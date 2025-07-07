package mccct
package test

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

import gears.async.Async
import gears.async.default.given


@RunWith(classOf[JUnit4])
class RecordTests {

  /**
    * A test that makes sure that if the scheduler is given a fixed schedule, then the recorded schedule is the same schedule
    * 
    * A caveat of this test is that the FixedSchedule needs to be complete. I.E. lead to termination of the code
    */
  @Test
  def replayAndRecordTest(): Unit = {
    val schedule: List[String] = List("1.","2.","1.2.","1.2.0.","2.1.", "1.1." ,"2.1.0.", "1.1.0.","1.","2.2.","2.2.0.","1.","2.", "1.0.","2.","2.0.")

    Scheduler.start(FixedSchedule(schedule))

    Async.blocking:
      val f1 = Future {
        val fut11 = Future {
        }
        val fut12 = Future {
        }
        fut11.await
        fut12.await
      }
      val f2 = Future {
        val fut21 = Future {
        }
        val fut22 = Future {
        }
        fut21.await
        fut22.await
      }

    Scheduler.awaitTermination()
    assert(schedule == Scheduler.getSchedule())
  }

  @Test
  def stressExistingRecordTests(): Unit = {
    var counter = 1_000
    while (counter > 0) {
      replayAndRecordTest()
      counter -= 1
    }
  }

}

