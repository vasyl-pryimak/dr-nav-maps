package ua.pryimak.drnav.graph

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Road graph on top of an mmap'ed .drg file: only pages near the current position get loaded into memory.
 * The format is described in [GraphFormat].
 */
class RoadGraph private constructor(private val buf: ByteBuffer) {

    val nodeCount: Int
    val edgeCount: Int
    val pointCount: Int
    private val cellCount: Int
    private val gridMinLat: Int
    private val gridMinLon: Int
    private val cellLatE7: Int
    private val cellLonE7: Int
    private val gridCols: Int
    private val nameCount: Int
    val placeCount: Int
    val houseCount: Int
    private val houseCellCount: Int
    val oldNameCount: Int

    private val offPoints: Int
    private val offEdgeStart: Int
    private val offEdgeFrom: Int
    private val offEdgeTo: Int
    private val offEdgeFlags: Int
    private val offNodeStart: Int
    private val offAdjacency: Int
    private val offCellKeys: Int
    private val offCellStart: Int
    private val offCellSegments: Int
    private val offEdgeName: Int
    private val offNameStart: Int
    private val offNameBytes: Int
    private val offPlaces: Int
    private val offHouses: Int
    private val offHouseTextStart: Int
    private val offHouseText: Int
    private val offHouseCellKeys: Int
    private val offHouseCellStart: Int
    private val offHouseStreet: Int
    private val offOldNames: Int

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        require(buf.getInt(0) == GraphFormat.MAGIC) { "не .drg файл" }
        require(buf.getInt(4) == GraphFormat.VERSION) { "версія ${buf.getInt(4)}, очікувалась ${GraphFormat.VERSION}" }
        nodeCount = buf.getInt(8)
        edgeCount = buf.getInt(12)
        pointCount = buf.getInt(16)
        cellCount = buf.getInt(20)
        val cellSegmentCount = buf.getInt(24)
        val adjacencyCount = buf.getInt(28)
        gridMinLat = buf.getInt(32)
        gridMinLon = buf.getInt(36)
        cellLatE7 = buf.getInt(40)
        cellLonE7 = buf.getInt(44)
        gridCols = buf.getInt(48)
        nameCount = buf.getInt(52)
        val nameByteCount = buf.getInt(56)
        placeCount = buf.getInt(60)
        houseCount = buf.getInt(64)
        houseCellCount = buf.getInt(68)
        val houseTextBytes = buf.getInt(72)
        oldNameCount = buf.getInt(76)

        var off = GraphFormat.HEADER_INTS * 4
        offPoints = off; off += pointCount * 8
        offEdgeStart = off; off += (edgeCount + 1) * 4
        offEdgeFrom = off; off += edgeCount * 4
        offEdgeTo = off; off += edgeCount * 4
        offEdgeFlags = off; off += (edgeCount + 3) / 4 * 4
        offNodeStart = off; off += (nodeCount + 1) * 4
        offAdjacency = off; off += adjacencyCount * 4
        offCellKeys = off; off += cellCount * 4
        offCellStart = off; off += (cellCount + 1) * 4
        offCellSegments = off; off += cellSegmentCount * 4
        offEdgeName = off; off += edgeCount * 4
        offNameStart = off; off += (nameCount + 1) * 4
        offNameBytes = off; off += (nameByteCount + 3) / 4 * 4
        offPlaces = off; off += placeCount * 16
        offHouses = off; off += houseCount * 8
        offHouseTextStart = off; off += (houseCount + 1) * 4
        offHouseText = off; off += (houseTextBytes + 3) / 4 * 4
        offHouseCellKeys = off; off += houseCellCount * 4
        offHouseCellStart = off; off += (houseCellCount + 1) * 4
        offHouseStreet = off; off += houseCount * 4
        offOldNames = off; off += oldNameCount * 8
        require(off == buf.capacity()) { "пошкоджений файл: очікувалось $off байт, є ${buf.capacity()}" }
    }

    fun pointLat(p: Int): Double = GraphFormat.fromE7(buf.getInt(offPoints + p * 8))
    fun pointLon(p: Int): Double = GraphFormat.fromE7(buf.getInt(offPoints + p * 8 + 4))

    fun edgeFirstPoint(e: Int): Int = buf.getInt(offEdgeStart + e * 4)
    fun edgeLastPoint(e: Int): Int = buf.getInt(offEdgeStart + (e + 1) * 4) - 1
    fun edgeFrom(e: Int): Int = buf.getInt(offEdgeFrom + e * 4)
    fun edgeTo(e: Int): Int = buf.getInt(offEdgeTo + e * 4)
    fun edgeFlags(e: Int): Int = buf.get(offEdgeFlags + e).toInt() and 0xFF
    fun roadClass(e: Int): RoadClass = EdgeFlags.roadClass(edgeFlags(e))
    fun isOneway(e: Int): Boolean = edgeFlags(e) and EdgeFlags.ONEWAY != 0

    /** Street/road name (name:uk, name, ref) or null. */
    fun edgeName(e: Int): String? = nameAt(buf.getInt(offEdgeName + e * 4))

    /** Edge name index (-1 - unnamed): to avoid labeling the same street twice without reading the string. */
    fun edgeNameIndex(e: Int): Int = buf.getInt(offEdgeName + e * 4)

    fun placeLat(i: Int): Double = GraphFormat.fromE7(buf.getInt(offPlaces + i * 16))
    fun placeLon(i: Int): Double = GraphFormat.fromE7(buf.getInt(offPlaces + i * 16 + 4))
    /** 0 - city, 1 - town, 2 - village, 3 - city district. */
    fun placeRank(i: Int): Int = buf.getInt(offPlaces + i * 16 + 8)
    fun placeName(i: Int): String = nameAt(buf.getInt(offPlaces + i * 16 + 12)) ?: ""

    /** Places in the box with rank at most [maxRank]. There are tens of thousands of places - plain linear scan. */
    fun placesInBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, maxRank: Int): List<Int> {
        val out = ArrayList<Int>()
        for (i in 0 until placeCount) {
            if (placeRank(i) > maxRank) break // sorted by rank
            val la = placeLat(i); val lo = placeLon(i)
            if (la in minLat..maxLat && lo in minLon..maxLon) out.add(i)
        }
        return out
    }

    /** String from the name table by index ([edgeNameIndex]). */
    fun name(idx: Int): String? = nameAt(idx)

    fun houseLat(i: Int): Double = GraphFormat.fromE7(buf.getInt(offHouses + i * 8))
    fun houseLon(i: Int): Double = GraphFormat.fromE7(buf.getInt(offHouses + i * 8 + 4))
    fun houseNumber(i: Int): String {
        val s = buf.getInt(offHouseTextStart + i * 4)
        val t = buf.getInt(offHouseTextStart + (i + 1) * 4)
        val bytes = ByteArray(t - s)
        for (k in bytes.indices) bytes[k] = buf.get(offHouseText + s + k)
        return String(bytes, Charsets.UTF_8)
    }

    /** Street of the house (addr:street, else the village from addr:place) or null. */
    fun houseStreet(i: Int): String? = nameAt(houseStreetIndex(i))

    /** Name index of the house's street, comparable with [edgeNameIndex]; -1 - unknown. */
    fun houseStreetIndex(i: Int): Int = buf.getInt(offHouseStreet + i * 4)

    fun oldNameEdge(i: Int): Int = buf.getInt(offOldNames + i * 8)
    fun oldNameIndex(i: Int): Int = buf.getInt(offOldNames + i * 8 + 4)

    /** Former names of the edge (old_name), usually none. */
    fun edgeOldNames(e: Int): List<String> {
        var lo = 0; var hi = oldNameCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (oldNameEdge(mid) < e) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<String>(1)
        while (lo < oldNameCount && oldNameEdge(lo) == e) nameAt(oldNameIndex(lo++))?.let(out::add)
        return out
    }

    /** House numbers in the box - via the same grid as the roads. */
    fun housesInBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<Int> {
        val r0 = maxOf(0, floorDiv(GraphFormat.toE7(minLat) - gridMinLat, cellLatE7))
        val r1 = floorDiv(GraphFormat.toE7(maxLat) - gridMinLat, cellLatE7)
        val c0 = maxOf(0, floorDiv(GraphFormat.toE7(minLon) - gridMinLon, cellLonE7))
        val c1 = minOf(gridCols - 1, floorDiv(GraphFormat.toE7(maxLon) - gridMinLon, cellLonE7))
        val out = ArrayList<Int>()
        for (r in r0..r1) for (c in c0..c1) {
            val key = r * gridCols + c
            var lo = 0; var hi = houseCellCount - 1; var ci = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val k = buf.getInt(offHouseCellKeys + mid * 4)
                when { k < key -> lo = mid + 1; k > key -> hi = mid - 1; else -> { ci = mid; break } }
            }
            if (ci < 0) continue
            val s = buf.getInt(offHouseCellStart + ci * 4)
            val t = buf.getInt(offHouseCellStart + (ci + 1) * 4)
            for (i in s until t) {
                val la = houseLat(i); val lo2 = houseLon(i)
                if (la in minLat..maxLat && lo2 in minLon..maxLon) out.add(i)
            }
        }
        return out
    }

    private fun nameAt(idx: Int): String? {
        if (idx < 0) return null
        val s = buf.getInt(offNameStart + idx * 4)
        val t = buf.getInt(offNameStart + (idx + 1) * 4)
        val bytes = ByteArray(t - s)
        for (i in bytes.indices) bytes[i] = buf.get(offNameBytes + s + i)
        return String(bytes, Charsets.UTF_8)
    }

    /** Edges with at least one segment inside the box (for map rendering). */
    fun edgesInBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, maxClass: RoadClass = RoadClass.TRACK): Set<Int> {
        val r0 = maxOf(0, floorDiv(GraphFormat.toE7(minLat) - gridMinLat, cellLatE7))
        val r1 = floorDiv(GraphFormat.toE7(maxLat) - gridMinLat, cellLatE7)
        val c0 = maxOf(0, floorDiv(GraphFormat.toE7(minLon) - gridMinLon, cellLonE7))
        val c1 = minOf(gridCols - 1, floorDiv(GraphFormat.toE7(maxLon) - gridMinLon, cellLonE7))
        val out = HashSet<Int>()
        for (r in r0..r1) for (c in c0..c1) {
            val ci = findCell(r * gridCols + c)
            if (ci < 0) continue
            val s = buf.getInt(offCellStart + ci * 4)
            val t = buf.getInt(offCellStart + (ci + 1) * 4)
            for (i in s until t) {
                val e = edgeOfPoint(buf.getInt(offCellSegments + i * 4))
                if (roadClass(e) <= maxClass) out.add(e)
            }
        }
        return out
    }

    fun nodeEdges(n: Int): IntArray {
        val s = buf.getInt(offNodeStart + n * 4)
        val t = buf.getInt(offNodeStart + (n + 1) * 4)
        return IntArray(t - s) { buf.getInt(offAdjacency + (s + it) * 4) }
    }

    /** Edge that point p belongs to (first or intermediate point of a segment). */
    fun edgeOfPoint(p: Int): Int {
        var lo = 0
        var hi = edgeCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (edgeFirstPoint(mid) <= p) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun edgeLengthM(e: Int): Double {
        var len = 0.0
        for (p in edgeFirstPoint(e) until edgeLastPoint(e)) {
            len += distanceM(pointLat(p), pointLon(p), pointLat(p + 1), pointLon(p + 1))
        }
        return len
    }

    /**
     * Road segment near a point.
     * @property pointIndex first point of the segment (segment pointIndex → pointIndex + 1)
     * @property fraction where the projection lies on the segment, 0..1
     * @property bearingDeg segment bearing along the edge geometry
     */
    data class SegmentHit(
        val edge: Int,
        val pointIndex: Int,
        val distanceM: Double,
        val fraction: Double,
        val lat: Double,
        val lon: Double,
        val bearingDeg: Double,
    )

    /** All segments within the radius, nearest first. Only the nearest segment of each edge. */
    fun nearby(lat: Double, lon: Double, radiusM: Double): List<SegmentHit> {
        val latE7 = GraphFormat.toE7(lat)
        val lonE7 = GraphFormat.toE7(lon)
        val dLatE7 = (radiusM / 111_320.0 * GraphFormat.E7).toInt()
        val dLonE7 = (radiusM / (111_320.0 * cos(Math.toRadians(lat))) * GraphFormat.E7).toInt()
        val r0 = floorDiv(latE7 - dLatE7 - gridMinLat, cellLatE7)
        val r1 = floorDiv(latE7 + dLatE7 - gridMinLat, cellLatE7)
        val c0 = maxOf(0, floorDiv(lonE7 - dLonE7 - gridMinLon, cellLonE7))
        val c1 = minOf(gridCols - 1, floorDiv(lonE7 + dLonE7 - gridMinLon, cellLonE7))

        val best = HashMap<Int, SegmentHit>()
        val cosLat = cos(Math.toRadians(lat))
        for (r in maxOf(0, r0)..r1) for (c in c0..c1) {
            val ci = findCell(r * gridCols + c)
            if (ci < 0) continue
            val s = buf.getInt(offCellStart + ci * 4)
            val t = buf.getInt(offCellStart + (ci + 1) * 4)
            for (i in s until t) {
                val p = buf.getInt(offCellSegments + i * 4)
                val hit = project(p, lat, lon, cosLat)
                if (hit.distanceM > radiusM) continue
                val e = edgeOfPoint(p)
                val prev = best[e]
                if (prev == null || hit.distanceM < prev.distanceM) best[e] = hit.copy(edge = e)
            }
        }
        return best.values.sortedBy { it.distanceM }
    }

    private fun project(p: Int, lat: Double, lon: Double, cosLat: Double): SegmentHit {
        val aLat = pointLat(p); val aLon = pointLon(p)
        val bLat = pointLat(p + 1); val bLon = pointLon(p + 1)
        // local plane in meters centered on the query point
        val ax = (aLon - lon) * cosLat * M_PER_DEG; val ay = (aLat - lat) * M_PER_DEG
        val bx = (bLon - lon) * cosLat * M_PER_DEG; val by = (bLat - lat) * M_PER_DEG
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx; val py = ay + t * dy
        val bearing = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
        return SegmentHit(
            edge = -1,
            pointIndex = p,
            distanceM = sqrt(px * px + py * py),
            fraction = t,
            lat = aLat + t * (bLat - aLat),
            lon = aLon + t * (bLon - aLon),
            bearingDeg = bearing,
        )
    }

    private fun findCell(key: Int): Int {
        var lo = 0
        var hi = cellCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = buf.getInt(offCellKeys + mid * 4)
            when {
                k < key -> lo = mid + 1
                k > key -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    companion object {
        private const val M_PER_DEG = 111_320.0

        fun open(file: File): RoadGraph {
            RandomAccessFile(file, "r").use { raf ->
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                return RoadGraph(map)
            }
        }

        fun fromBuffer(buf: ByteBuffer): RoadGraph = RoadGraph(buf)

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val x = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2)) * M_PER_DEG
            val y = (lat2 - lat1) * M_PER_DEG
            return sqrt(x * x + y * y)
        }

        private fun floorDiv(a: Int, b: Int): Int = floor(a.toDouble() / b).toInt()
    }
}
