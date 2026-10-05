package ua.pryimak.drnav.graph

/** Road class from the OSM highway=* tag. `_link` (ramps) map to their own class. */
enum class RoadClass {
    MOTORWAY, TRUNK, PRIMARY, SECONDARY, TERTIARY, UNCLASSIFIED, RESIDENTIAL, LIVING_STREET, SERVICE, TRACK;

    companion object {
        fun fromHighway(tag: String): RoadClass? = when (tag) {
            "motorway", "motorway_link" -> MOTORWAY
            "trunk", "trunk_link" -> TRUNK
            "primary", "primary_link" -> PRIMARY
            "secondary", "secondary_link" -> SECONDARY
            "tertiary", "tertiary_link" -> TERTIARY
            "unclassified", "road" -> UNCLASSIFIED
            "residential" -> RESIDENTIAL
            "living_street" -> LIVING_STREET
            "service" -> SERVICE
            "track" -> TRACK
            else -> null
        }
    }
}

/** Edge bit flags: [0..3] class, 4 - oneway (only along the geometry), 5 - bridge, 6 - tunnel. */
object EdgeFlags {
    const val CLASS_MASK = 0x0F
    const val ONEWAY = 0x10
    const val BRIDGE = 0x20
    const val TUNNEL = 0x40

    fun pack(roadClass: RoadClass, oneway: Boolean, bridge: Boolean, tunnel: Boolean): Int =
        roadClass.ordinal or
            (if (oneway) ONEWAY else 0) or
            (if (bridge) BRIDGE else 0) or
            (if (tunnel) TUNNEL else 0)

    fun roadClass(flags: Int): RoadClass = RoadClass.entries[flags and CLASS_MASK]
}
