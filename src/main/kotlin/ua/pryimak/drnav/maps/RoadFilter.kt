package ua.pryimak.drnav.maps

import ua.pryimak.drnav.graph.EdgeFlags
import ua.pryimak.drnav.graph.RoadClass

/** Which OSM ways count as roads for cars, and with which flags. */
object RoadFilter {
    private val NO_ACCESS = setOf("no", "agricultural", "forestry")
    private val ACCESS_KEYS = listOf("motor_vehicle", "motorcar", "vehicle", "access")

    /** @property reverse oneway=-1: traffic runs against the geometry direction, so it must be reversed */
    data class Road(val flags: Int, val reverse: Boolean)

    /** @return null if the way is not for cars */
    fun classify(tags: Map<String, String>): Road? {
        val highway = tags["highway"] ?: return null
        val roadClass = RoadClass.fromHighway(highway) ?: return null
        if (tags["area"] == "yes") return null
        // the most specific access tag present wins
        val access = ACCESS_KEYS.firstNotNullOfOrNull { tags[it] }
        if (access in NO_ACCESS) return null

        val onewayTag = tags["oneway"]
        val roundabout = tags["junction"] == "roundabout" || tags["junction"] == "circular"
        val reverse = onewayTag == "-1"
        val oneway = when (onewayTag) {
            "yes", "1", "true", "-1" -> true
            null -> roundabout || highway == "motorway"
            else -> false
        }
        val bridge = tags["bridge"].let { it != null && it != "no" }
        val tunnel = tags["tunnel"].let { it != null && it != "no" }
        return Road(EdgeFlags.pack(roadClass, oneway, bridge, tunnel), reverse)
    }
}
