package ua.pryimak.drnav.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RoadGraphTest {

    /**
     * Cross: node 0 at the centre (50.0, 30.0), four ~500 m roads to N, E, S, W.
     * The east one is one-way away from the centre, with an intermediate point.
     */
    private fun cross(): RoadGraph {
        val c = GraphFormat.toE7(50.0) to GraphFormat.toE7(30.0)
        val dLat = GraphFormat.toE7(500.0 / 111_320.0)
        val dLon = GraphFormat.toE7(500.0 / (111_320.0 * Math.cos(Math.toRadians(50.0))))
        val lat = intArrayOf(c.first, c.first + dLat, c.first, c.first, c.first + 0, c.first, c.first - dLat, c.first, c.first)
        val lon = intArrayOf(c.second, c.second, c.second, c.second + dLon / 2, c.second + dLon, c.second, c.second, c.second, c.second - dLon)
        val data = GraphData(
            nodeCount = 5,
            pointLatE7 = lat,
            pointLonE7 = lon,
            edgePointStart = intArrayOf(0, 2, 5, 7, 9),
            edgeFrom = intArrayOf(0, 0, 0, 0),
            edgeTo = intArrayOf(1, 2, 3, 4),
            edgeFlags = byteArrayOf(
                EdgeFlags.pack(RoadClass.PRIMARY, false, false, false).toByte(),
                EdgeFlags.pack(RoadClass.RESIDENTIAL, true, false, false).toByte(),
                EdgeFlags.pack(RoadClass.PRIMARY, false, true, false).toByte(),
                EdgeFlags.pack(RoadClass.TRACK, false, false, true).toByte(),
            ),
        )
        val f = File.createTempFile("cross", ".drg").apply { deleteOnExit() }
        GraphWriter.write(data, f, cellM = 100.0)
        return RoadGraph.open(f)
    }

    @Test
    fun `reads header and adjacency`() {
        val g = cross()
        assertEquals(5, g.nodeCount)
        assertEquals(4, g.edgeCount)
        assertEquals(setOf(0, 1, 2, 3), g.nodeEdges(0).toSet())
        assertEquals(listOf(1), g.nodeEdges(2).toList())
        assertTrue(g.isOneway(1))
        assertEquals(RoadClass.TRACK, g.roadClass(3))
        assertEquals(500.0, g.edgeLengthM(1), 1.0)
    }

    @Test
    fun `finds nearest road`() {
        val g = cross()
        // 20 m east of the north road, 300 m up
        val lat = 50.0 + 300.0 / 111_320.0
        val lon = 30.0 + 20.0 / (111_320.0 * Math.cos(Math.toRadians(50.0)))
        val hits = g.nearby(lat, lon, 50.0)
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].edge)
        assertEquals(20.0, hits[0].distanceM, 0.5)
        assertEquals(0.0, hits[0].bearingDeg, 0.5)
        assertEquals(0.6, hits[0].fraction, 0.01)
    }

    @Test
    fun `near the centre all four roads are found, one segment each`() {
        val g = cross()
        val hits = g.nearby(50.0, 30.0, 30.0)
        assertEquals(setOf(0, 1, 2, 3), hits.map { it.edge }.toSet())
        assertEquals(1, g.edgeOfPoint(3))
        // the east road heads east
        assertEquals(90.0, hits.first { it.edge == 1 }.bearingDeg, 0.5)
    }

    @Test
    fun `places and names by index`() {
        val f = File.createTempFile("places", ".drg").apply { deleteOnExit() }
        val data = GraphData(
            nodeCount = 2,
            pointLatE7 = intArrayOf(GraphFormat.toE7(50.0), GraphFormat.toE7(50.001)),
            pointLonE7 = intArrayOf(GraphFormat.toE7(30.0), GraphFormat.toE7(30.0)),
            edgePointStart = intArrayOf(0, 2), edgeFrom = intArrayOf(0), edgeTo = intArrayOf(1),
            edgeFlags = byteArrayOf(EdgeFlags.pack(RoadClass.RESIDENTIAL, false, false, false).toByte()),
            edgeName = intArrayOf(0),
            names = listOf("вулиця Тестова", "Київ", "Бровари"),
            placeLatE7 = intArrayOf(GraphFormat.toE7(50.45), GraphFormat.toE7(50.51)),
            placeLonE7 = intArrayOf(GraphFormat.toE7(30.52), GraphFormat.toE7(30.79)),
            placeRank = intArrayOf(0, 1),
            placeName = intArrayOf(1, 2),
            houseLatE7 = intArrayOf(GraphFormat.toE7(50.0005), GraphFormat.toE7(50.0008)),
            houseLonE7 = intArrayOf(GraphFormat.toE7(30.0001), GraphFormat.toE7(30.0001)),
            houseNumber = listOf("12А", "14"),
        )
        GraphWriter.write(data, f)
        val g = RoadGraph.open(f)
        assertEquals("вулиця Тестова", g.edgeName(0))
        assertEquals("вулиця Тестова", g.name(g.edgeNameIndex(0)))
        assertEquals(2, g.placeCount)
        assertEquals(listOf("Київ"), g.placesInBox(50.0, 30.0, 51.0, 31.0, maxRank = 0).map { g.placeName(it) })
        assertEquals(listOf("Київ", "Бровари"), g.placesInBox(50.0, 30.0, 51.0, 31.0, maxRank = 3).map { g.placeName(it) })
        assertEquals(setOf("12А", "14"), g.housesInBox(50.0, 29.99, 50.001, 30.01).map { g.houseNumber(it) }.toSet())
        assertEquals(listOf("12А"), g.housesInBox(50.0, 29.99, 50.0006, 30.01).map { g.houseNumber(it) })
    }

    @Test
    fun `house streets and old names`() {
        val f = File.createTempFile("addr", ".drg").apply { deleteOnExit() }
        GraphWriter.write(
            GraphData(
                nodeCount = 3,
                pointLatE7 = intArrayOf(GraphFormat.toE7(50.0), GraphFormat.toE7(50.001), GraphFormat.toE7(50.002)),
                pointLonE7 = intArrayOf(GraphFormat.toE7(30.0), GraphFormat.toE7(30.0), GraphFormat.toE7(30.0)),
                edgePointStart = intArrayOf(0, 2, 3), edgeFrom = intArrayOf(0, 1), edgeTo = intArrayOf(1, 2),
                edgeFlags = ByteArray(2) { EdgeFlags.pack(RoadClass.RESIDENTIAL, false, false, false).toByte() },
                edgeName = intArrayOf(0, 0),
                names = listOf("вулиця Героїв", "вулиця Леніна", "Гора", "вулиця Стара"),
                houseLatE7 = intArrayOf(GraphFormat.toE7(50.0005), GraphFormat.toE7(50.0015), GraphFormat.toE7(50.0018)),
                houseLonE7 = intArrayOf(GraphFormat.toE7(30.0001), GraphFormat.toE7(30.0001), GraphFormat.toE7(30.0001)),
                houseNumber = listOf("1", "2", "3"),
                houseStreet = intArrayOf(0, 2, -1),
                // written unsorted on purpose: the writer sorts by edge
                oldNameEdge = intArrayOf(1, 0, 1),
                oldName = intArrayOf(1, 1, 3),
            ),
            f,
        )
        val g = RoadGraph.open(f)
        val byNumber = (0 until g.houseCount).associateBy { g.houseNumber(it) }
        assertEquals("вулиця Героїв", g.houseStreet(byNumber.getValue("1")))
        assertEquals(g.edgeNameIndex(0), g.houseStreetIndex(byNumber.getValue("1")))
        assertEquals("Гора", g.houseStreet(byNumber.getValue("2")))
        assertEquals(null, g.houseStreet(byNumber.getValue("3")))
        assertEquals(3, g.oldNameCount)
        assertEquals(listOf("вулиця Леніна"), g.edgeOldNames(0))
        assertEquals(setOf("вулиця Леніна", "вулиця Стара"), g.edgeOldNames(1).toSet())
    }

    @Test
    fun `nothing far from roads`() {
        assertTrue(cross().nearby(50.1, 30.1, 100.0).isEmpty())
    }
}
