package ua.pryimak.drnav.maps

import crosby.binary.BinaryParser
import crosby.binary.Osmformat
import crosby.binary.file.BlockInputStream
import ua.pryimak.drnav.graph.EdgeFlags
import ua.pryimak.drnav.graph.GraphData
import ua.pryimak.drnav.graph.GraphFormat
import ua.pryimak.drnav.graph.GraphWriter
import ua.pryimak.drnav.graph.RoadClass
import ua.pryimak.drnav.graph.RoadGraph
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Builds a car road graph from an OSM .pbf in the Doctor Navigation format (.drg).
 *
 *   map-builder <input.osm.pbf> <output.drg> [--manifest manifest.json --osm-date YYYY-MM-DD]
 *               [--bbox minLat,minLon,maxLat,maxLon] [--no-tracks]
 *
 * --manifest: JSON for the app — format version, data date, size, SHA-256
 * --no-tracks: skip highway=track (field and forest tracks — almost half the total road length in Ukraine)
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: map-builder <input.osm.pbf> <output.drg> [--manifest m.json --osm-date YYYY-MM-DD] [--bbox ...] [--no-tracks]")
        kotlin.system.exitProcess(2)
    }
    val input = File(args[0])
    val output = File(args[1])
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args[it + 1] }
    val manifest = opt("--manifest")?.let(::File)
    val osmDate = opt("--osm-date")
    val noTracks = "--no-tracks" in args
    val bbox = args.indexOf("--bbox").takeIf { it >= 0 }?.let { args[it + 1].split(",").map(String::toDouble) }

    val t0 = System.currentTimeMillis()
    fun log(msg: String) = println("[%5.1f s] %s".format((System.currentTimeMillis() - t0) / 1000.0, msg))

    // --- pass 1: roads ---
    val ways = WayCollector(noTracks)
    BlockInputStream(input.inputStream().buffered(1 shl 20), ways).process()
    log("roads: ${ways.flags.size}, node refs: ${ways.refs.size}")

    // --- pass 2: coordinates of the needed nodes ---
    val needed = LongArray(ways.refs.size + ways.houseRefs.size).also { all ->
        ways.refs.toArray().copyInto(all)
        ways.houseRefs.toArray().copyInto(all, ways.refs.size)
        all.sort()
    }.distinctSorted()
    log("buildings with a number: ${ways.houseNumber.size}")
    log("unique nodes: ${needed.size}")
    val nodes = NodeCollector(needed)
    BlockInputStream(input.inputStream().buffered(1 shl 20), nodes).process()
    log("coordinates found: ${nodes.found} of ${needed.size}")

    val roads = buildGraph(ways, needed, nodes, bbox)
    val houses = (houseCentroids(ways, needed, nodes) + nodes.houses).filter { h -> bbox == null || inBbox(h.latE7, h.lonE7, bbox) }
    log("house numbers: ${houses.size}")
    val graph = withPlaces(roads, nodes.places.filter { p -> bbox == null || inBbox(p.latE7, p.lonE7, bbox) }, houses)
    log("places: ${graph.placeLatE7.size}")
    log("graph nodes: ${graph.nodeCount}, edges: ${graph.edgeCount}, geometry points: ${graph.pointCount}")

    GraphWriter.write(graph, output)
    log("written ${output.name}: ${output.length() / 1024 / 1024} MB")

    val written = RoadGraph.open(output)
    printStats(written)
    manifest?.let {
        writeManifest(it, output, written, osmDate)
        log("manifest: ${it.name}")
    }
}

private class WayCollector(private val noTracks: Boolean) : BinaryParser() {
    val refs = LongList(1 shl 20)
    val wayStart = IntList(1 shl 16).apply { add(0) }
    val flags = IntList(1 shl 16)
    val reverse = ByteList(1 shl 16)
    val name = IntList(1 shl 16)
    val names = ArrayList<String>()
    private val nameIndex = HashMap<String, Int>()

    private fun intern(n: String?): Int = if (n == null) -1 else nameIndex.getOrPut(n) { names.add(n); names.size - 1 }

    /** Buildings with a number: outline (nodes) and the number itself. The coordinate is computed later as the outline centroid. */
    val houseRefs = LongList(1 shl 20)
    val houseStart = IntList(1 shl 16).apply { add(0) }
    val houseNumber = ArrayList<String>()

    override fun parseWays(list: List<Osmformat.Way>) {
        for (w in list) {
            collectHouse(w)
            if (w.refsCount < 2 || (0 until w.keysCount).none { getStringById(w.getKeys(it)) == "highway" }) continue
            val tags = HashMap<String, String>(8)
            for (i in 0 until w.keysCount) tags[getStringById(w.getKeys(i))] = getStringById(w.getVals(i))
            val road = RoadFilter.classify(tags) ?: continue
            if (noTracks && EdgeFlags.roadClass(road.flags) == RoadClass.TRACK) continue
            var ref = 0L
            for (i in 0 until w.refsCount) {
                ref += w.getRefs(i)
                refs.add(ref)
            }
            wayStart.add(refs.size)
            flags.add(road.flags)
            reverse.add(if (road.reverse) 1 else 0)
            name.add(intern(roadName(tags)))
        }
    }

    private fun collectHouse(w: Osmformat.Way) {
        if (w.refsCount < 3) return
        var number: String? = null
        for (i in 0 until w.keysCount) {
            if (getStringById(w.getKeys(i)) == "addr:housenumber") { number = getStringById(w.getVals(i)); break }
        }
        val n = number?.let(::cleanHouseNumber) ?: return
        var ref = 0L
        for (i in 0 until w.refsCount) {
            ref += w.getRefs(i)
            houseRefs.add(ref)
        }
        houseStart.add(houseRefs.size)
        houseNumber.add(n)
    }

    override fun parseDense(nodes: Osmformat.DenseNodes) = Unit
    override fun parseNodes(nodes: List<Osmformat.Node>) = Unit
    override fun parseRelations(rels: List<Osmformat.Relation>) = Unit
    override fun parse(header: Osmformat.HeaderBlock) = Unit
    override fun complete() = Unit
}

internal class House(val latE7: Int, val lonE7: Int, val number: String)

/** "12", "12A", "3/5"; long descriptions instead of a number are dropped — they would only clutter the map. */
internal fun cleanHouseNumber(raw: String): String? {
    val t = raw.trim()
    return if (t.isEmpty() || t.length > MAX_HOUSE_NUMBER_LEN) null else t
}

private const val MAX_HOUSE_NUMBER_LEN = 8

private class NodeCollector(private val ids: LongArray) : BinaryParser() {
    val lat = IntArray(ids.size) { MISSING }
    val lon = IntArray(ids.size)
    var found = 0

    /** Settlements (place=* nodes) for map labels. */
    val places = ArrayList<Place>()
    /** Addresses given as standalone points (not on a building outline). */
    val houses = ArrayList<House>()

    private fun maybePlace(tags: () -> Map<String, String>, latRaw: Long, lonRaw: Long) {
        val t = tags()
        val rank = PlaceRank.of(t["place"] ?: return) ?: return
        val name = t["name:uk"] ?: t["name"] ?: return
        places.add(Place(GraphFormat.toE7(parseLat(latRaw)), GraphFormat.toE7(parseLon(lonRaw)), rank, name))
    }

    private fun put(id: Long, latRaw: Long, lonRaw: Long) {
        val idx = ids.binarySearch(id)
        if (idx < 0 || lat[idx] != MISSING) return
        lat[idx] = GraphFormat.toE7(parseLat(latRaw))
        lon[idx] = GraphFormat.toE7(parseLon(lonRaw))
        found++
    }

    override fun parseDense(nodes: Osmformat.DenseNodes) {
        var id = 0L; var la = 0L; var lo = 0L
        var kv = 0
        val kvCount = nodes.keysValsCount
        for (i in 0 until nodes.idCount) {
            id += nodes.getId(i); la += nodes.getLat(i); lo += nodes.getLon(i)
            put(id, la, lo)
            if (kv >= kvCount) continue
            // dense node tags: key,val pairs (string indices), 0 ends a node
            val start = kv
            var hasPlace = false
            while (kv < kvCount) {
                val k = nodes.getKeysVals(kv++)
                if (k == 0) break
                when (getStringById(k)) {
                    "place" -> hasPlace = true
                    "addr:housenumber" -> cleanHouseNumber(getStringById(nodes.getKeysVals(kv)))?.let {
                        houses.add(House(GraphFormat.toE7(parseLat(la)), GraphFormat.toE7(parseLon(lo)), it))
                    }
                }
                kv++
            }
            if (hasPlace) {
                val end = kv
                maybePlace({
                    val m = HashMap<String, String>()
                    var j = start
                    while (j < end - 1) { m[getStringById(nodes.getKeysVals(j))] = getStringById(nodes.getKeysVals(j + 1)); j += 2 }
                    m
                }, la, lo)
            }
        }
    }

    override fun parseNodes(nodes: List<Osmformat.Node>) {
        for (n in nodes) {
            put(n.id, n.lat, n.lon)
            val tags = (0 until n.keysCount).associate { getStringById(n.getKeys(it)) to getStringById(n.getVals(it)) }
            maybePlace({ tags }, n.lat, n.lon)
            tags["addr:housenumber"]?.let(::cleanHouseNumber)?.let {
                houses.add(House(GraphFormat.toE7(parseLat(n.lat)), GraphFormat.toE7(parseLon(n.lon)), it))
            }
        }
    }

    override fun parseWays(ways: List<Osmformat.Way>) = Unit
    override fun parseRelations(rels: List<Osmformat.Relation>) = Unit
    override fun parse(header: Osmformat.HeaderBlock) = Unit
    override fun complete() = Unit

    companion object {
        const val MISSING = Int.MIN_VALUE
    }
}

private fun buildGraph(ways: WayCollector, ids: LongArray, nodes: NodeCollector, bbox: List<Double>?): GraphData {
    val wayCount = ways.flags.size
    val refIdx = IntArray(ways.refs.size) { ids.binarySearch(ways.refs[it]) }
    fun valid(r: Int) = nodes.lat[refIdx[r]] != NodeCollector.MISSING

    // A node is a junction if it is referenced ≥ 2 times, or is a road end / edge of missing data.
    val refCount = IntArray(ids.size)
    val endpoint = BooleanArray(ids.size)
    for (w in 0 until wayCount) {
        val s = ways.wayStart[w]; val t = ways.wayStart[w + 1]
        for (r in s until t) {
            if (!valid(r)) continue
            refCount[refIdx[r]]++
            if (r == s || r == t - 1 || !valid(r - 1) || !valid(r + 1)) endpoint[refIdx[r]] = true
        }
    }
    fun isJunction(i: Int) = refCount[i] >= 2 || endpoint[i]

    val junctionId = IntArray(ids.size) { -1 }
    var nodeCount = 0
    fun nodeOf(i: Int): Int {
        if (junctionId[i] < 0) junctionId[i] = nodeCount++
        return junctionId[i]
    }

    val pLat = IntList(1 shl 20); val pLon = IntList(1 shl 20)
    val edgeStart = IntList(1 shl 18).apply { add(0) }
    val edgeFrom = IntList(1 shl 18); val edgeTo = IntList(1 shl 18)
    val edgeFlags = ByteList(1 shl 18)
    val edgeName = IntList(1 shl 18)

    val piece = IntList(256)
    fun emit(flags: Int, reverse: Boolean, nameIdx: Int) {
        if (piece.size < 2) return
        val order = IntArray(piece.size) { piece[it] }.also { if (reverse) it.reverse() }
        val lat = IntArray(order.size) { nodes.lat[order[it]] }
        val lon = IntArray(order.size) { nodes.lon[order[it]] }
        if (bbox != null && lat.indices.none { inBbox(lat[it], lon[it], bbox) }) return
        val keep = simplify(lat, lon, SIMPLIFY_TOLERANCE_M)
        if (keep.size < 2) return
        for (k in keep) { pLat.add(lat[k]); pLon.add(lon[k]) }
        edgeStart.add(pLat.size)
        edgeFrom.add(nodeOf(order.first()))
        edgeTo.add(nodeOf(order.last()))
        edgeFlags.add(flags.toByte())
        edgeName.add(nameIdx)
    }

    for (w in 0 until wayCount) {
        val flags = ways.flags[w]
        val reverse = ways.reverse[w].toInt() == 1
        val nameIdx = ways.name[w]
        piece.clear()
        for (r in ways.wayStart[w] until ways.wayStart[w + 1]) {
            if (!valid(r)) {
                emit(flags, reverse, nameIdx); piece.clear()
                continue
            }
            val i = refIdx[r]
            piece.add(i)
            if (piece.size > 1 && isJunction(i)) {
                emit(flags, reverse, nameIdx); piece.clear(); piece.add(i)
            }
        }
        emit(flags, reverse, nameIdx)
    }

    return GraphData(
        nodeCount = nodeCount,
        pointLatE7 = pLat.toArray(),
        pointLonE7 = pLon.toArray(),
        edgePointStart = edgeStart.toArray(),
        edgeFrom = edgeFrom.toArray(),
        edgeTo = edgeTo.toArray(),
        edgeFlags = edgeFlags.toArray(),
        edgeName = edgeName.toArray(),
        names = ways.names,
    )
}

internal class Place(val latE7: Int, val lonE7: Int, val rank: Int, val name: String)

/** Lower is more important: shown at a wider zoom. */
internal object PlaceRank {
    fun of(place: String): Int? = when (place) {
        "city" -> 0
        "town" -> 1
        "village" -> 2
        "suburb", "quarter" -> 3
        else -> null
    }
}

/** A house number is placed at the centroid of the building outline. */
private fun houseCentroids(ways: WayCollector, ids: LongArray, nodes: NodeCollector): List<House> {
    val out = ArrayList<House>(ways.houseNumber.size)
    for (h in ways.houseNumber.indices) {
        var sumLat = 0L; var sumLon = 0L; var n = 0
        val s = ways.houseStart[h]; val t = ways.houseStart[h + 1]
        // the outline is closed: last node == first, don't count it twice
        val end = if (t - s > 1 && ways.houseRefs[t - 1] == ways.houseRefs[s]) t - 1 else t
        for (r in s until end) {
            val idx = ids.binarySearch(ways.houseRefs[r])
            if (idx < 0 || nodes.lat[idx] == NodeCollector.MISSING) continue
            sumLat += nodes.lat[idx]; sumLon += nodes.lon[idx]; n++
        }
        if (n > 0) out.add(House((sumLat / n).toInt(), (sumLon / n).toInt(), ways.houseNumber[h]))
    }
    return out
}

/** Add places and house numbers to the graph; place names go into the same table as street names. */
private fun withPlaces(g: GraphData, places: List<Place>, houses: List<House>): GraphData {
    val names = ArrayList(g.names)
    val index = HashMap<String, Int>().apply { names.forEachIndexed { i, n -> put(n, i) } }
    val sorted = places.sortedBy { it.rank }
    return GraphData(
        nodeCount = g.nodeCount,
        pointLatE7 = g.pointLatE7, pointLonE7 = g.pointLonE7,
        edgePointStart = g.edgePointStart, edgeFrom = g.edgeFrom, edgeTo = g.edgeTo, edgeFlags = g.edgeFlags,
        edgeName = g.edgeName,
        names = names,
        placeLatE7 = IntArray(sorted.size) { sorted[it].latE7 },
        placeLonE7 = IntArray(sorted.size) { sorted[it].lonE7 },
        placeRank = IntArray(sorted.size) { sorted[it].rank },
        placeName = IntArray(sorted.size) { i -> index.getOrPut(sorted[i].name) { names.add(sorted[i].name); names.size - 1 } },
        houseLatE7 = IntArray(houses.size) { houses[it].latE7 },
        houseLonE7 = IntArray(houses.size) { houses[it].lonE7 },
        houseNumber = houses.map { it.number },
    )
}

private const val SIMPLIFY_TOLERANCE_M = 1.5

/** e.g. "Khreshchatyk Street", "Kyiv – Chop (M-06)", "T-10-01". */
internal fun roadName(tags: Map<String, String>): String? {
    val name = tags["name:uk"] ?: tags["name"]
    val ref = tags["ref"]
    return when {
        name != null && ref != null -> "$name ($ref)"
        name != null -> name
        else -> ref
    }
}

/** Douglas–Peucker in local metres. Returns the indices of the points kept (endpoints always kept). */
internal fun simplify(latE7: IntArray, lonE7: IntArray, toleranceM: Double): IntArray {
    val n = latE7.size
    if (n <= 2) return IntArray(n) { it }
    val cosLat = cos(Math.toRadians(GraphFormat.fromE7(latE7[0])))
    val x = DoubleArray(n) { GraphFormat.fromE7(lonE7[it]) * cosLat * 111_320.0 }
    val y = DoubleArray(n) { GraphFormat.fromE7(latE7[it]) * 111_320.0 }
    val keep = BooleanArray(n).also { it[0] = true; it[n - 1] = true }
    val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to n - 1) }
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        var maxD = -1.0; var maxI = -1
        for (i in a + 1 until b) {
            val d = pointSegmentDistance(x[i], y[i], x[a], y[a], x[b], y[b])
            if (d > maxD) { maxD = d; maxI = i }
        }
        if (maxI >= 0 && maxD > toleranceM) {
            keep[maxI] = true
            stack.add(a to maxI); stack.add(maxI to b)
        }
    }
    return keep.indices.filter { keep[it] }.toIntArray()
}

private fun pointSegmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
    return hypot(px - ax - t * dx, py - ay - t * dy)
}

private fun inBbox(latE7: Int, lonE7: Int, b: List<Double>): Boolean {
    val lat = GraphFormat.fromE7(latE7); val lon = GraphFormat.fromE7(lonE7)
    return lat in b[0]..b[2] && lon in b[1]..b[3]
}

private fun LongArray.distinctSorted(): LongArray {
    if (isEmpty()) return this
    var n = 1
    for (i in 1 until size) if (this[i] != this[n - 1]) this[n++] = this[i]
    return copyOf(n)
}

/**
 * Manifest for the app: it reads this small file first and decides whether to download the map.
 * formatVersion keeps an old app from trying to read a newer format.
 */
private fun writeManifest(file: File, graphFile: File, g: RoadGraph, osmDate: String?) {
    val sha = java.security.MessageDigest.getInstance("SHA-256").let { md ->
        graphFile.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    file.writeText(
        """
        {
          "formatVersion": ${GraphFormat.VERSION},
          "region": "ukraine",
          "osmDate": ${osmDate?.let(::q) ?: "null"},
          "file": ${q(graphFile.name)},
          "size": ${graphFile.length()},
          "sha256": ${q(sha)},
          "edges": ${g.edgeCount},
          "places": ${g.placeCount},
          "houses": ${g.houseCount},
          "attribution": "© OpenStreetMap contributors, ODbL"
        }
        """.trimIndent() + "\n",
    )
}

private fun printStats(g: RoadGraph) {
    val km = DoubleArray(RoadClass.entries.size)
    for (e in 0 until g.edgeCount) km[g.roadClass(e).ordinal] += g.edgeLengthM(e) / 1000
    println("Road length by class:")
    RoadClass.entries.forEach { println("  %-14s %9.0f km".format(it.name, km[it.ordinal])) }
    println("  %-14s %9.0f km".format("TOTAL", km.sum()))

    val ranks = IntArray(4)
    for (i in 0 until g.placeCount) ranks[g.placeRank(i)]++
    println("Places: cities ${ranks[0]}, towns ${ranks[1]}, villages ${ranks[2]}, districts ${ranks[3]}")
    println("Cities near Kyiv: " + g.placesInBox(50.2, 30.2, 50.7, 30.9, 1).take(8).joinToString { g.placeName(it) })

    val (lat, lon) = 50.4501 to 30.5234 // Maidan Nezalezhnosti (Independence Square)
    val hits = g.nearby(lat, lon, 60.0)
    println("Near Maidan (60 m): ${hits.size} edges")
    hits.take(5).forEach {
        println("  edge ${it.edge} ${g.roadClass(it.edge)} \"${g.edgeName(it.edge)}\" ${"%.1f".format(it.distanceM)} m, bearing ${it.bearingDeg.toInt()}°" +
            if (g.edgeFlags(it.edge) and EdgeFlags.ONEWAY != 0) " one-way" else "")
    }
}
