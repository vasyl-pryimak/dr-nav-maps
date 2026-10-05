package ua.pryimak.drnav.graph

/**
 * Binary road graph format (.drg), little-endian, all sections 4-byte aligned for reading via mmap.
 *
 * ```
 * header (HEADER_INTS × int):
 *   magic, version, nodeCount, edgeCount, pointCount, cellCount, cellSegmentCount, adjacencyCount,
 *   gridMinLatE7, gridMinLonE7, cellLatE7, cellLonE7, gridCols, nameCount, nameByteCount, placeCount,
 *   houseCount, houseCellCount, houseTextBytes, oldNameCount
 * points:          pointCount × (latE7 int, lonE7 int)
 * edgePointStart:  (edgeCount + 1) × int   - geometry of edge e = points [start[e], start[e+1])
 * edgeFrom:        edgeCount × int         - node at the first point
 * edgeTo:          edgeCount × int         - node at the last point
 * edgeFlags:       edgeCount × byte, padded to 4
 * nodeEdgeStart:   (nodeCount + 1) × int   - edges of node n = adjacency[start[n], start[n+1])
 * adjacency:       adjacencyCount × int
 * cellKeys:        cellCount × int (ascending; key = row * gridCols + col)
 * cellStart:       (cellCount + 1) × int
 * cellSegments:    cellSegmentCount × int  - index of the segment's first point (segment p → p+1 of one edge)
 * edgeName:        edgeCount × int         - name index, -1 if unnamed
 * nameStart:       (nameCount + 1) × int   - offsets into nameBytes
 * nameBytes:       nameByteCount × byte (UTF-8), padded to 4
 * places:          placeCount × (latE7, lonE7, rank, nameIdx) - places, most important first (rank 0 - city)
 * houses:          houseCount × (latE7, lonE7) - house numbers, sorted by cell of the same grid
 * houseTextStart:  (houseCount + 1) × int - offsets into houseText
 * houseText:       houseTextBytes × byte (UTF-8), padded to 4
 * houseCellKeys:   houseCellCount × int (ascending)
 * houseCellStart:  (houseCellCount + 1) × int
 * houseStreet:     houseCount × int (same order as houses) - name index of addr:street, else addr:place; -1 if none
 * oldNames:        oldNameCount × (edge, nameIdx), sorted by edge - former names (old_name) for address search
 * ```
 *
 * Versions: 1 roads and grid, 2 street names, 3 places, 4 house numbers, 5 house streets and old names.
 */
object GraphFormat {
    const val MAGIC = 0x31475244 // "DRG1"
    const val VERSION = 5
    const val HEADER_INTS = 20
    const val E7 = 1e7

    fun toE7(deg: Double): Int = Math.round(deg * E7).toInt()
    fun fromE7(v: Int): Double = v / E7
}
