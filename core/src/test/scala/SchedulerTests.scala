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

  /** A test that tests if all futures are executed when using the RandomWalk algorithm In this test there are no
    * awaits, however all futures should still be executed, and completed before the main thread can continue
    */
  @Test
  def exploreAllRandomWalkTest(): Unit = {
    Scheduler.start(RandomWalk)
    val list = AtomicReference(List[Int]())

    Async.blocking:
      val f1 = Future {
        Future {
          list.updateAndGet(curr => 1 :: curr)
        }
        list.updateAndGet(curr => 2 :: curr)
      }
      val f2 = Future {
        Future {
          Future {
            list.updateAndGet(curr => 3 :: curr)
          }
          list.updateAndGet(curr => 4 :: curr)
        }
        list.updateAndGet(curr => 5 :: curr)
      }

    Scheduler.awaitTermination()
    assert(list.get().size == 5)
    assert(list.get().size * 2 == Scheduler.getSchedule().size) // 5 futures + 5 ".0." children
  }

  /** A test that makes sure that all futures are executed when using the RandomWalk algorithm This test has nested
    * futures and multiple awaits. This allows us to say some things about the execution, like if we await for the
    * top-level task, then its child task has to be completed before we can continue (if the parent task waits for the
    * child task)
    */
  @Test
  def randomWalkWithAwaitTest(): Unit = {
    Scheduler.start(RandomWalk)
    val list = AtomicReference(List[Int]())

    Async.blocking:
      val f1 = Future {
        val nestedF = Future {
          list.updateAndGet(curr => 1 :: curr)
        }
        nestedF.await
        list.updateAndGet(curr => 2 :: curr)
      }
      val f2 = Future {
        list.updateAndGet(curr => 3 :: curr)
      }
      f1.await
      val f3 = Future {
        list.updateAndGet(curr => 4 :: curr)
      }

    Scheduler.awaitTermination()
    val finalList = list.get().reverse
    // f2 could theoretically be executed at any point, which means that we can not say anything about where 3 (in list) or "2." (in schedule history) should appear
    assert(finalList.size == 4)
    assert(Scheduler.getSchedule().size == 10) // 4 Futures + 2 awaits + 4 ".0." children
    assert(appearsAfter(2, 1, finalList))
    assert(appearsAfter(4, 1, finalList))
    assert(appearsAfter(4, 2, finalList))
  }

  /** A test that makes sure that the reset function works as expected. This means that all previous information is
    * cleaned with reset
    */
  @Test
  def resetTest(): Unit = {
    Scheduler.start(FifoAlgorithm)

    val list = AtomicReference(List[Int]())

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

  /** A test that makes sure that if a top-level futures await is hit before all top-level futures have been started
    * then:
    *   1. All top-level futures are executed
    *   2. Futures are executed in the expected order
    */
  @Test
  def awaitTest(): Unit = {
    Scheduler.start(RandomWalk)
    val list = AtomicReference(List[Int]())

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
    // We do not know in which order f2 and f1 will update the list, therefore we can not say anthing about the relation between the two
    assert(
      appearsAfter("3.", "1.", schedule)
    ) // Since f3 is only executed after f1 has finished then that should also be reflected in the schedulers schedule
    assert(appearsAfter(3, 1, list.get().reverse))
    assert(list.get().size == 3)
    assert(schedule.size == 7) // 3 top-level tasks + root task + 3 ".0." child tasks
  }

  /** A test that makes sure that tasks are not executed before it is needed In this case this means that all top-level
    * tasks have to be started before execution is allowed
    */
  @Test
  def noAwaitTest(): Unit = {
    Scheduler.start(FifoAlgorithm)
    val list = AtomicReference(List[Int]())
    Async.blocking:
      val f1 = Future {
        list.updateAndGet(curr => 1 :: curr)
      }
      assert(list.get().size == 0) // Make sure that no execution has happened
      val f2 = Future {
        list.updateAndGet(curr => 2 :: curr)
      }
      assert(list.get().size == 0) // Make sure that no execution has happened
      val f3 = Future {
        list.updateAndGet(curr => 3 :: curr)
      }
      assert(list.get().size == 0) // Make sure that no execution has happened

    Scheduler.awaitTermination()
    assert(Scheduler.getDone())
    assert(Scheduler.getSchedule().size == 6) // 3 Futures, each with their own ".0." child task
    assert(list.get().size == 3)
  }

  /** A test that tests mainly two things:
    *   1. Makes sure that execution is not started until needed
    *   2. What happens if an await is the last thing to happen before awaitTermination
    */
  @Test
  def awaitNothingBeforeTest(): Unit = {
    Scheduler.start(RandomWalk)
    val list = AtomicReference(List[Int]())

    Async.blocking:
      val f1 = Future {
        list.updateAndGet(curr => 1 :: curr)
      }
      assert(list.get().size == 0)
      f1.await

    Scheduler.awaitTermination()
    assert(list.get().size == 1)
  }

  @Test
  def stressExistingTests(): Unit = {
    var counter = 1_000
    while (counter > 0) {
      awaitTest()
      noAwaitTest()
      awaitNothingBeforeTest()
      counter -= 1
    }
  }

  /** A function that determines if an element occurs before or after another element
    *
    * @param target,
    *   the target element
    * @param after,
    *   the element which target should appear after
    * @param list,
    *   the list in which the elements appears
    * @return
    *   true if target appears after after, and false otherwise
    */
  private def appearsAfter[T](target: T, after: T, list: List[T]): Boolean = {
    val afterIndex  = list.indexOf(after)
    val targetIndex = list.indexOf(target)

    afterIndex != -1 && targetIndex != -1 && targetIndex > afterIndex
  }

}
