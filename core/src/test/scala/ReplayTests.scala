package mucct

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicReference

import gears.async.Async
import gears.async.default.given


@RunWith(classOf[JUnit4])
class ReplayTests {


  @Test
  def replayOrderTest(): Unit = {
    val list:AtomicReference[List[Int]] = AtomicReference(List()) 
    val schedule: List[String] = List("1.","1.2.","1.2.0.","1.1.","1.1.0.","1.","1.","1.0.")
    
    Scheduler.start(schedule)

    Async.blocking:
      val f = Future {
        val fut1 = Future {
          list.updateAndGet(curr => 1 :: curr)
        }
        val fut2 = Future {
          list.updateAndGet(curr => 0 :: curr)
        }
        fut1.await
        fut2.await
        list.updateAndGet(curr => 2 :: curr)
      }

    Scheduler.awaitTermination()
    assert(list.get() == List(2, 1, 0))
  }
  
}

