package mccct
package test

import mccct.CoverageTracker.marker
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

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
    val (coverageReport, _) = CoverageTracker.trackCoverage(testFunction(), numIter = 10)
    assert(coverageReport("1.") == 10)
    assert(coverageReport("2.") == 10)
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
    val (coverageReport, _) = CoverageTracker.trackCoverage(testFunction(), numIter = 10)
    assert(coverageReport("1.") == 10)
    assert(coverageReport("2.") == 10)
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
    val (coverageReport, _) = CoverageTracker.trackCoverage(testFunction(), numIter = 10)
    assert(coverageReport("1.") == 10)
    assert(coverageReport("2.") == 10)
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
    val (coverageReport, _) = CoverageTracker.trackCoverage(testFunction(), numIter = 10)
    assert(coverageReport("1.") == 10)
    val numTwo = coverageReport.get("2.") match
      case Some(a) => a
      case None    => 0

    val numFour = coverageReport.get("4.") match
      case Some(a) => a
      case None    => 0

    assert(numTwo + numFour == 10)
    assert(coverageReport.get("3.") == None)
    assert(CoverageTracker.missedMarkers.size <= 2)
  }

}
