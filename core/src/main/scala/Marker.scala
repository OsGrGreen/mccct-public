package mccct

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap

object CoverageTracker {
  // Map that saves how many times each marker has been hit
  private val markerMap = TrieMap[String, Int]()
  // Map that saves what schedule led to a specific markerMap
  private val scheduleMarkerMap = TrieMap[String, TrieMap[String, Int]]()
  // List that stores which markers the user expects the tracker to hit
  private var expectedMarkers = List[String]()

  def marker(id: String): Unit = markerMap.updateWith(id) {
    // If there was a previous value then add 1 to it
    case Some(value) => Some(value + 1)
    // If there was no previous value set it to 1, since it has now been hit once
    case None => Some(1)
  }

  def updateExpectedMarkers(markers: List[String]): Unit =
    this.synchronized {
      expectedMarkers = markers
    }

  def missedMarkers: List[String] =
    this.synchronized {
      expectedMarkers diff markerMap.keySet.toList
    }

  def numMarkers: Int = markerMap.values.sum

  def reset(): Unit =
    this.synchronized {
      markerMap.clear()
      scheduleMarkerMap.clear()
      expectedMarkers = List[String]()
    }

  private def addSchedule(map: TrieMap[String, Int]): Unit =
    val schedule  = Scheduler.getSchedule()
    val stringRep = schedule.mkString(", ")
    scheduleMarkerMap.addOne(stringRep, map)

  private def diff(map1: TrieMap[String, Int], map2: TrieMap[String, Int]): TrieMap[String, Int] =
    val keys   = map1.keySet ++ map2.keySet
    val result = TrieMap[String, Int]()

    for (key <- keys) {
      val v1 = map1.getOrElse(key, 0)
      val v2 = map2.getOrElse(key, 0)
      result(key) = v1 - v2
    }
    result

  /** Runs a function a number of times and returns how many times each marker was hit
    *
    * @param testFunction
    *   the function to test
    * @param alg
    *   the exploration algorithm to use
    * @param numIter
    *   the numer of iterations to run the tracker tool for
    * @return
    */
  def trackCoverage(
      testFunction: => Unit,
      alg: ExplorationAlgorithm = FifoAlgorithm,
      numIter: Int = 10
  ): (TrieMap[String, Int], TrieMap[String, TrieMap[String, Int]]) = {
    var counter = 0
    var prevMap = TrieMap[String, Int]()
    while (counter < numIter) {
      Scheduler.start(alg)
      testFunction
      Scheduler.awaitTermination()
      if Scheduler.getNumErrors() != 0 then addSchedule(diff(markerMap, prevMap))
      prevMap = TrieMap.empty[String, Int] ++ markerMap
      counter += 1
    }
    (markerMap, scheduleMarkerMap)
  }
}
