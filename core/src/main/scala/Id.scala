package mccct

import java.util.concurrent.atomic.AtomicInteger

class Id(val parent: Controller, val isEnd: Boolean = false):

  var numChildren: AtomicInteger = AtomicInteger(0)
  private val id: String         = {
    if (parent != null && !isEnd) {
      parent.id.getId() + parent.id.getAndIncrementNumChildren() + "."
    } else if (parent != null && isEnd) {
      parent.id.getId() + "0."
    } else {
      ""
    }
  }

  private[mccct] def getAndIncrementNumChildren(): Int = numChildren.incrementAndGet()

  private[mccct] def getNumChildren(): Int = numChildren.get()

  def getId(): String = id

  private[mccct] def reset(): Unit = numChildren = AtomicInteger(0)

  def getParent(): String = parent.id.getId()
