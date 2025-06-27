package mucct

import java.util.concurrent.atomic.AtomicInteger

class Id(val parent: Task, val isEnd: Boolean = false):

    private var numChildren: AtomicInteger = AtomicInteger(0)
    private val id: String = {
        if (parent != null && !isEnd) {
            parent.id.getId() + parent.id.getNumChildren() + "."
        } else if (parent != null && isEnd){
            parent.id.getId() + "0."
        } else{
            ""
        }
    }

    def getNumChildren(): Int = numChildren.incrementAndGet()

    def getId(): String = id

    def reset(): Unit = numChildren = AtomicInteger(0)