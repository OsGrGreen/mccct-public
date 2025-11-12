package mccct

import gears.async

class Consumer(buf: Buffer[Int]) {
  def run()(using async.Async, Controller): Unit =
    try {
      var i = 0
      while (i <= 1000) {
        val tmp = buf.get()
        println(s"Consumer trying to get $tmp - $this")
        i += 1
      }
    } catch {
      case _: InterruptedException =>
    }
}
