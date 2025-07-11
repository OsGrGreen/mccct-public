package mccct
package test

import mccct.CoverageTracker.marker
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

import scala.concurrent.duration.{FiniteDuration, SECONDS}
import gears.async.Async
import gears.async.default.given

import scala.util.{Try, Success, Failure}

@RunWith(classOf[JUnit4])
class CoverageTests {

  @Test
  def nestedFutureCoverage(): Unit = {
    def testFunction(): Unit = {
      Async.blocking:
        Future {
          marker("1.")
          Future {
            marker("2.")
          }
        }
    }
    CoverageTracker.reset()
    CoverageTracker.updateExpectedMarkers(List("1.", "2."))
    val (coverageReport, failedSchedules) = CoverageTracker.trackCoverageIter(testFunction(), RandomWalk, 10, false)
    assert(coverageReport("1.") == 10)
    assert(coverageReport("2.") == 10)
    assert(failedSchedules.isEmpty)
    assert(CoverageTracker.missedMarkers.size == 0)
  }

  @Test
  def sequentialMarkerTest(): Unit = {
    def testFunction(): Unit = {
      Async.blocking:
        Future {
          marker("1.")
        }
        Future {
          marker("2.")
        }
    }
    CoverageTracker.reset()
    CoverageTracker.updateExpectedMarkers(List("1.", "2."))
    val (coverageReport, failedSchedules) = CoverageTracker.trackCoverageIter(testFunction(), RandomWalk, 10, false)
    assert(coverageReport("1.") == 10)
    assert(coverageReport("2.") == 10)
    assert(failedSchedules.isEmpty)
    assert(CoverageTracker.missedMarkers.size == 0)
  }

  @Test
  def awaitCompletedTest(): Unit = {
    def testFunction(): Unit = {
      Async.blocking:
        val f1 = Future {
          marker("1.")
        }
        f1.await
        Future {
          if f1.isCompleted then marker("2.")
        }
    }
    CoverageTracker.reset()
    CoverageTracker.updateExpectedMarkers(List("1.", "2."))
    val (coverageReport, failedSchedules) = CoverageTracker.trackCoverageIter(testFunction(), RandomWalk, 10, false)
    assert(coverageReport("1.") == 10)
    assert(coverageReport("2.") == 10)
    assert(failedSchedules.isEmpty)
    assert(CoverageTracker.missedMarkers.size == 0)
  }

  @Test
  def getValueMarkerTest(): Unit = {
    def testFunction(): Unit = {
      Async.blocking:
        val f1 = Future {
          marker("1.")
        }
        Future {
          f1.value match
            case Some(Success(v)) => marker("2.")
            case Some(Failure(e)) => marker("3.")
            case None             => marker("4.")
        }
    }
    CoverageTracker.reset()
    CoverageTracker.updateExpectedMarkers(List("1.", "2.", "3.", "4."))
    val (coverageReport, failedSchedules) = CoverageTracker.trackCoverageIter(testFunction(), RandomWalk, 10, false)
    assert(coverageReport("1.") == 10)
    val numTwo = coverageReport.get("2.") match
      case Some(a) => a
      case None    => 0

    val numFour = coverageReport.get("4.") match
      case Some(a) => a
      case None    => 0

    assert(numTwo + numFour == 10)
    assert(coverageReport.get("3.") == None)
    assert(failedSchedules.isEmpty)
    assert(CoverageTracker.missedMarkers.size <= 2)
  }

  @Test
  def coverageTrackerExceptionTest(): Unit = {
    def testFunction(): Unit = {
      Async.blocking:
        val f1 = Future {
          marker("1.")
          throw new RuntimeException("Test runtime exception")
          marker("2.")
        }
        val f2 = Future {
          marker("3.")
        }
    }

    CoverageTracker.reset()
    // Run the CoverageTracker until marker "1." and "3." has been hit
    // Since "2." will never be reached, since the error is thrown before the marker is hit
    val (coverageReport, failedRuns) =
      CoverageTracker.trackCoverage(testFunction(), RandomWalk, List("1.", "3."), FiniteDuration(120, SECONDS), false)
    assert(coverageReport.get("2.") == None)
    assert(coverageReport("1.") >= 1)
    assert(coverageReport("3.") >= 1)
    assert(failedRuns.keySet.size == 1)
  }

  @Test
  def conditionalExceptionCoverageTest(): Unit = {
    def testFunction(): Unit = {
      Async.blocking:
        val f1 = Future {
          marker("1.")
        }
        val f2 = Future {
          if f1.isCompleted then
            marker("2.")
            throw new RuntimeException("Test runtime exception")
          marker("3.")
        }
    }

    CoverageTracker.reset()
    // Run the CoverageTracker until all markers have been hit
    val (coverageReport, failedRuns) =
      CoverageTracker.trackCoverage(
        testFunction(),
        RandomWalk,
        List("1.", "2.", "3."),
        FiniteDuration(120, SECONDS),
        false
      )
    // All markers are in the report
    assert(coverageReport.keySet.size == 3)
    // At least one schedule is in the failedRuns
    assert(failedRuns.keySet.size >= 1)
    // The scheduler should not hit marker 3 in a schedule that got an exception
    // Since the error is thrown before marker 3 is hit, thereby suspending the task before marker 3 can execute
    assert(failedRuns.values.toList.head == List("1.", "2."))
  }

  @Test
  def coverageTimeoutTest(): Unit = {
    def testFunction(): Unit = {
      Async.blocking:
        Future {
          marker("1.")
        }
    }
    CoverageTracker.reset()
    // Marker "2." does not exist and will never be hit, triggering an infinite loop
    val (coverageReport, failedSchedules) =
      CoverageTracker.trackCoverage(testFunction(), RandomWalk, List("1.", "2."), FiniteDuration(5, SECONDS), false)
    assert(!coverageReport.isEmpty)
    assert(CoverageTracker.missedMarkers.size == 1)
  }
}
