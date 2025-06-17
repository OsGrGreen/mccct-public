package mucct

class Id(val parent: Task, val isEnd: Boolean = false):

    private var numChildren = 0
    private val id: String = {
        if (parent != null && !isEnd) {
            parent.id.incrementChildren()
            parent.id.getId() + parent.id.getNumChildren() + "."
        } else if (parent != null && isEnd){
            parent.id.getId() + "0."
        } else{
            ""
        }
    }

    def incrementChildren(): Unit = numChildren += 1

    def getNumChildren(): Int = numChildren

    def getId(): String = id

