package mccct

import gears.async

class Producer(buf: Buffer[Int], modCount: Int) {
  def run()(using async.Async, Controller): Unit =
    try {
      var tmp = 0
      var i   = 0
      while (i <= 1000) {
        println(s"Producer trying to put $tmp - $this")
        buf.put(tmp)
        tmp = (tmp + 1) % modCount
        i += 1
      }
    } catch {
      case _: InterruptedException =>
    }
}
