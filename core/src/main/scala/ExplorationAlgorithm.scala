package mccct

import gears.async


trait ExplorationAlgorithm:
    def getNext(readyTasks: List[(Task,Scheduler.Controller)]): Option[(Task,Scheduler.Controller)]

    def prepareNext(taskHistory: List[String]): Unit

object FifoAlgorithm extends ExplorationAlgorithm:
    def getNext(readyTasks: List[(Task, Scheduler.Controller)]): Option[(Task,Scheduler.Controller)] =
        Some(readyTasks.head)
    
    def prepareNext(taskHistory: List[String]): Unit = {}

object RandomWalk extends ExplorationAlgorithm:
    def getNext(readyTasks: List[(Task, Scheduler.Controller)]): Option[(Task,Scheduler.Controller)] = {
        var chosen = getRandElem(readyTasks).get
        Some(chosen)
    }

    def prepareNext(taskHistory: List[String]): Unit = {}

    def getRandElem[T](l: List[T]): Option[T] =
        if (l.isEmpty) then None
        else l.lift(util.Random.nextInt(l.length))


class FixedSchedule(var targetSchedule: List[String])  extends ExplorationAlgorithm:
    def getNext(readyTasks: List[(Task,Scheduler.Controller)]): Option[(Task,Scheduler.Controller)] = {
        val targetTask = targetSchedule.head //Take the id of the task we want to execute.
        var target:List[(Task, Scheduler.Controller)] = readyTasks.filter((t,c) => t.id.getId() == targetTask)
        if target.isEmpty then
            return None
        targetSchedule = targetSchedule.tail //Remove head from schedule
        Some(target.head) //Take target task and control
    }

    def prepareNext(taskHistory: List[String]): Unit = {}



