import java.util.concurrent.ConcurrentHashMap

data class ActiveStation(val id: String, var dist: Double?)

fun main() {
    val set = ConcurrentHashMap.newKeySet<ActiveStation>()
    val s = ActiveStation("A", null)
    set.add(s)
    s.dist = 1.0
    val toKeep = setOf("B")
    
    set.removeAll { it.id !in toKeep }
    println("Remaining: " + set.size)
    if(set.isNotEmpty()) {
        println("It's still there! " + set.iterator().next().id)
    }
}
