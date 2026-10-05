package ua.pryimak.drnav.graph

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** In-memory graph before writing. Nodes are intersections and road ends; an edge is the stretch between them with geometry. */
class GraphData(
    val nodeCount: Int,
    val pointLatE7: IntArray,
    val pointLonE7: IntArray,
    val edgePointStart: IntArray,
    val edgeFrom: IntArray,
    val edgeTo: IntArray,
    val edgeFlags: ByteArray,
    /** Index into [names] for each edge, -1 - unnamed. */
    val edgeName: IntArray = IntArray(edgeFrom.size) { -1 },
    val names: List<String> = emptyList(),
    val placeLatE7: IntArray = IntArray(0),
    val placeLonE7: IntArray = IntArray(0),
    val placeRank: IntArray = IntArray(0),
    val placeName: IntArray = IntArray(0),
    val houseLatE7: IntArray = IntArray(0),
    val houseLonE7: IntArray = IntArray(0),
    val houseNumber: List<String> = emptyList(),
    /** Index into [names] of each house's street (addr:street, else addr:place), -1 - unknown. */
    val houseStreet: IntArray = IntArray(houseLatE7.size) { -1 },
    /** Former names: pairs (edge, index into [names]), any order. */
    val oldNameEdge: IntArray = IntArray(0),
    val oldName: IntArray = IntArray(0),
) {
    val edgeCount get() = edgeFrom.size
    val pointCount get() = pointLatE7.size
}

object GraphWriter {
    /** ~250 m in latitude; longitude is adjusted for the mean latitude so cells are nearly square. */
    const val DEFAULT_CELL_M = 250.0

    fun write(g: GraphData, file: File, cellM: Double = DEFAULT_CELL_M) {
        require(g.edgePointStart.size == g.edgeCount + 1)
        require(g.houseStreet.size == g.houseLatE7.size && g.oldName.size == g.oldNameEdge.size)

        // --- node adjacency ---
        val degree = IntArray(g.nodeCount)
        for (e in 0 until g.edgeCount) {
            degree[g.edgeFrom[e]]++
            if (g.edgeTo[e] != g.edgeFrom[e]) degree[g.edgeTo[e]]++
        }
        val nodeEdgeStart = IntArray(g.nodeCount + 1)
        for (n in 0 until g.nodeCount) nodeEdgeStart[n + 1] = nodeEdgeStart[n] + degree[n]
        val adjacency = IntArray(nodeEdgeStart[g.nodeCount])
        val fill = nodeEdgeStart.copyOf(g.nodeCount)
        for (e in 0 until g.edgeCount) {
            adjacency[fill[g.edgeFrom[e]]++] = e
            if (g.edgeTo[e] != g.edgeFrom[e]) adjacency[fill[g.edgeTo[e]]++] = e
        }

        // --- grid ---
        var minLat = Int.MAX_VALUE; var maxLat = Int.MIN_VALUE
        var minLon = Int.MAX_VALUE; var maxLon = Int.MIN_VALUE
        for (i in 0 until g.pointCount) {
            minLat = min(minLat, g.pointLatE7[i]); maxLat = max(maxLat, g.pointLatE7[i])
            minLon = min(minLon, g.pointLonE7[i]); maxLon = max(maxLon, g.pointLonE7[i])
        }
        if (g.pointCount == 0) { minLat = 0; maxLat = 0; minLon = 0; maxLon = 0 }
        val midLat = GraphFormat.fromE7((minLat + maxLat) / 2)
        val cellLatE7 = max(1, (cellM / 111_320.0 * GraphFormat.E7).toInt())
        val cellLonE7 = max(1, (cellM / (111_320.0 * cos(Math.toRadians(midLat))) * GraphFormat.E7).toInt())
        val cols = (maxLon - minLon) / cellLonE7 + 1

        var pairs = LongArray(max(16, g.pointCount))
        var pairCount = 0
        for (e in 0 until g.edgeCount) {
            for (p in g.edgePointStart[e] until g.edgePointStart[e + 1] - 1) {
                val r0 = (min(g.pointLatE7[p], g.pointLatE7[p + 1]) - minLat) / cellLatE7
                val r1 = (max(g.pointLatE7[p], g.pointLatE7[p + 1]) - minLat) / cellLatE7
                val c0 = (min(g.pointLonE7[p], g.pointLonE7[p + 1]) - minLon) / cellLonE7
                val c1 = (max(g.pointLonE7[p], g.pointLonE7[p + 1]) - minLon) / cellLonE7
                for (r in r0..r1) for (c in c0..c1) {
                    if (pairCount == pairs.size) pairs = pairs.copyOf(pairs.size * 2)
                    val key = r.toLong() * cols + c
                    pairs[pairCount++] = (key shl 32) or p.toLong()
                }
            }
        }
        pairs = pairs.copyOf(pairCount)
        pairs.sort()

        var cellCount = 0
        for (i in 0 until pairCount) if (i == 0 || (pairs[i] ushr 32) != (pairs[i - 1] ushr 32)) cellCount++
        val cellKeys = IntArray(cellCount)
        val cellStart = IntArray(cellCount + 1)
        val cellSegments = IntArray(pairCount)
        var ci = -1
        for (i in 0 until pairCount) {
            val key = (pairs[i] ushr 32).toInt()
            if (ci < 0 || cellKeys[ci] != key) {
                ci++
                cellKeys[ci] = key
                cellStart[ci] = i
            }
            cellSegments[i] = (pairs[i] and 0xFFFFFFFFL).toInt()
        }
        cellStart[cellCount] = pairCount

        // --- names ---
        val encoded = g.names.map { it.toByteArray(Charsets.UTF_8) }
        val nameStart = IntArray(encoded.size + 1)
        for (i in encoded.indices) nameStart[i + 1] = nameStart[i] + encoded[i].size
        val nameBytes = ByteArray(nameStart.last())
        for (i in encoded.indices) encoded[i].copyInto(nameBytes, nameStart[i])

        // --- house numbers: sorted by cell of the same grid as the roads ---
        val houseKeys = LongArray(g.houseLatE7.size) { i ->
            val r = (g.houseLatE7[i] - minLat) / cellLatE7
            val c = (g.houseLonE7[i] - minLon) / cellLonE7
            val inGrid = g.houseLatE7[i] >= minLat && g.houseLonE7[i] >= minLon && c < cols
            if (inGrid) (r.toLong() * cols + c shl 32) or i.toLong() else -1L
        }.filter { it >= 0 }.sorted()
        val houseOrder = IntArray(houseKeys.size) { (houseKeys[it] and 0xFFFFFFFFL).toInt() }
        val houseText = houseOrder.map { g.houseNumber[it].toByteArray(Charsets.UTF_8) }
        val houseTextStart = IntArray(houseOrder.size + 1)
        for (i in houseOrder.indices) houseTextStart[i + 1] = houseTextStart[i] + houseText[i].size
        val houseTextBytes = ByteArray(houseTextStart.last())
        for (i in houseOrder.indices) houseText[i].copyInto(houseTextBytes, houseTextStart[i])
        val hCellKeys = IntList()
        val hCellStart = IntList()
        for (i in houseKeys.indices) {
            val key = (houseKeys[i] ushr 32).toInt()
            if (hCellKeys.size == 0 || hCellKeys.last() != key) { hCellKeys.add(key); hCellStart.add(i) }
        }
        hCellStart.add(houseKeys.size)

        // --- write ---
        BufferedOutputStream(file.outputStream(), 1 shl 20).use { out ->
            val w = LeWriter(out)
            w.ints(
                intArrayOf(
                    GraphFormat.MAGIC, GraphFormat.VERSION, g.nodeCount, g.edgeCount, g.pointCount,
                    cellCount, pairCount, adjacency.size, minLat, minLon, cellLatE7, cellLonE7, cols,
                    g.names.size, nameBytes.size, g.placeLatE7.size,
                    houseOrder.size, hCellKeys.size, houseTextBytes.size, g.oldNameEdge.size,
                ),
            )
            for (i in 0 until g.pointCount) { w.int(g.pointLatE7[i]); w.int(g.pointLonE7[i]) }
            w.ints(g.edgePointStart)
            w.ints(g.edgeFrom)
            w.ints(g.edgeTo)
            w.bytes(g.edgeFlags)
            repeat((4 - g.edgeCount % 4) % 4) { w.bytes(byteArrayOf(0)) }
            w.ints(nodeEdgeStart)
            w.ints(adjacency)
            w.ints(cellKeys)
            w.ints(cellStart)
            w.ints(cellSegments)
            w.ints(g.edgeName)
            w.ints(nameStart)
            w.bytes(nameBytes)
            repeat((4 - nameBytes.size % 4) % 4) { w.bytes(byteArrayOf(0)) }
            for (i in g.placeLatE7.indices) {
                w.int(g.placeLatE7[i]); w.int(g.placeLonE7[i]); w.int(g.placeRank[i]); w.int(g.placeName[i])
            }
            for (i in houseOrder) { w.int(g.houseLatE7[i]); w.int(g.houseLonE7[i]) }
            w.ints(houseTextStart)
            w.bytes(houseTextBytes)
            repeat((4 - houseTextBytes.size % 4) % 4) { w.bytes(byteArrayOf(0)) }
            w.ints(hCellKeys.toArray())
            w.ints(hCellStart.toArray())
            for (i in houseOrder) w.int(g.houseStreet[i])
            for (i in g.oldNameEdge.indices.sortedBy { g.oldNameEdge[it] }) { w.int(g.oldNameEdge[i]); w.int(g.oldName[i]) }
            w.flush()
        }
    }

    private class IntList {
        private var data = IntArray(1024)
        var size = 0; private set
        fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun last() = data[size - 1]
        fun toArray() = data.copyOf(size)
    }

    private class LeWriter(private val out: OutputStream) {
        private val buf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)

        fun int(v: Int) {
            if (buf.remaining() < 4) flush()
            buf.putInt(v)
        }

        fun ints(a: IntArray) = a.forEach(::int)

        fun bytes(a: ByteArray) {
            for (b in a) {
                if (!buf.hasRemaining()) flush()
                buf.put(b)
            }
        }

        fun flush() {
            out.write(buf.array(), 0, buf.position())
            buf.clear()
        }
    }
}
