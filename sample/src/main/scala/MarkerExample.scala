package mccct

import mccct.CoverageTracker.marker
import gears.async.Async
import gears.async.default.given

object MarkerExample {

  def exampleProgram(): Unit = {
    println("Running example")

    Async.blocking:
      marker("1.")
      val f1 = Future {
        marker("2.")
      }
      val f2 = Future {
        if f1.isCompleted then marker("3.")
      }
  }

  /*@main*/
  def runMarkerExample(): Unit = {
    val numIterations = 1000
    println(
      s"Total hits when running ${numIterations} times is: ${CoverageTracker.trackCoverageIter(exampleProgram(), FifoAlgorithm, numIterations, true)}"
    )
    println(s"Schedule was: ${Scheduler.getSchedule()}")
  }

}
