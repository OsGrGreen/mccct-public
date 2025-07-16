package mccct

trait ExplorationAlgorithm:
  def getNext(readyTasks: List[(Task, Scheduler.Controller)]): Option[List[(Task, Scheduler.Controller)]]

  def prepareNext(taskHistory: List[String]): Unit

object FifoAlgorithm extends ExplorationAlgorithm:
  def getNext(readyTasks: List[(Task, Scheduler.Controller)]): Option[List[(Task, Scheduler.Controller)]] =
    readyTasks.headOption.map(List(_))

  def prepareNext(taskHistory: List[String]): Unit = {}

object RandomWalk extends ExplorationAlgorithm:
  def getNext(readyTasks: List[(Task, Scheduler.Controller)]): Option[List[(Task, Scheduler.Controller)]] = {
    val n = 1

    val shuffled = util.Random.shuffle(readyTasks)
    val selected = shuffled.take(n)

    Some(selected)
  }

  def prepareNext(taskHistory: List[String]): Unit = {}

class FixedSchedule(var targetSchedule: List[String]) extends ExplorationAlgorithm:
  def getNext(readyTasks: List[(Task, Scheduler.Controller)]): Option[List[(Task, Scheduler.Controller)]] = {
    targetSchedule.headOption match // Take the id of the task we want to execute.
      case Some(task) =>
        val target = readyTasks.filter((t, c) => t.id.getId() == task)
        if target.isEmpty then return None
        targetSchedule = targetSchedule.tail // Remove head from schedule
        Some(List(target.head))              // Take target task and control
      case None =>
        None
  }

  def prepareNext(taskHistory: List[String]): Unit = {}
