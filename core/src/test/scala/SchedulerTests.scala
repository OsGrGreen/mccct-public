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

    val list:AtomicReference[List[Int]] = AtomicReference(List()) 

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

  @Test
  def awaitTest(): Unit = {
    Scheduler.start()
    val list:AtomicReference[List[Int]] = AtomicReference(List()) 

    Async.blocking:
      val f1 = Future {
        list.updateAndGet(curr => 1 :: curr)
      }
      val f2 = Future {
        list.updateAndGet(curr => 2 :: curr)
      }
      f1.await
      val f3 = Future {
        list.updateAndGet(curr => 3 :: curr)
      }
    
    Scheduler.awaitTermination()
    val schedule = Scheduler.getSchedule()
    assert(appearsAfter("3.", "1.", schedule))
    assert(list.get().size == 3)
  }
  
  @Test
  def noAwaitTest(): Unit = {
    Scheduler.start()
    val list:AtomicReference[List[Int]] = AtomicReference(List()) 

    Async.blocking:
      val f1 = Future {
        list.updateAndGet(curr => 1 :: curr)
      }
      assert(list.get().size == 0)
      val f2 = Future {
        list.updateAndGet(curr => 2 :: curr)
      }
      assert(list.get().size == 0)
      val f3 = Future {
        list.updateAndGet(curr => 3 :: curr)
      }
      assert(list.get().size == 0)
    
    Scheduler.awaitTermination()
    assert(Scheduler.getDone())
    assert(Scheduler.getSchedule().size == 6)
    assert(list.get().size == 3)
  }

  @Test
  def awaitNothingBeforeTest(): Unit = {
    Scheduler.start()
    val list:AtomicReference[List[Int]] = AtomicReference(List()) 

    Async.blocking:
      val f1 = Future {
        list.updateAndGet(curr => 1 :: curr)
      }
      assert(list.get().size == 0)
      f1.await
    
    Scheduler.awaitTermination()
    assert(list.get().size == 1)
  }

  private def appearsAfter(target: String, after: String, list: List[String]): Boolean = {
    val afterIndex = list.indexOf(after)
    val targetIndex = list.indexOf(target)

    afterIndex != -1 && targetIndex != -1 && targetIndex > afterIndex
  }

}

