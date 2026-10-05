# dr-nav-maps

Maps for the **Doctor Navigation** app: a road graph of Ukraine in `.drg` format, built from OpenStreetMap data.

## Download
The latest map is always available at permanent links:
- manifest: https://github.com/vasyl-pryimak/dr-nav-maps/releases/latest/download/manifest.json
- map: https://github.com/vasyl-pryimak/dr-nav-maps/releases/latest/download/ukraine.drg

The app first reads the small `manifest.json` and downloads the map only if it is newer and has a compatible format version.

```json
{
  "formatVersion": 5,
  "region": "ukraine",
  "osmDate": "2026-09-29",
  "file": "ukraine.drg",
  "size": 415550520,
  "sha256": "0def8bb5…",
  "edges": 4336446,
  "places": 28937,
  "houses": 1694818,
  "oldNames": 269988,
  "attribution": "© OpenStreetMap contributors, ODbL"
}
```

## What's in the map
- **Roads for cars**: from motorways to residential streets, service roads and dirt tracks. One-way traffic, roundabouts, bridges and tunnels are taken into account.
- **Street and road names**: `name:uk`, otherwise `name`, plus the route number (`ref`).
- **Settlements**: cities, towns, villages, city districts.
- **House numbers**: from building outlines and standalone address points, each with its street (`addr:street`, or `addr:place` for villages without street names) — ~74 % of them have one.
- **Former road names** (`old_name`) for address search: after the renamings many people still search by the old name.
- **Spatial grid** of ~250 m for fast "what's nearby" lookups. The file is read via mmap.

The format is described in [`GraphFormat.kt`](src/main/kotlin/ua/pryimak/drnav/graph/GraphFormat.kt).

## Automatic updates
[GitHub Actions](.github/workflows/build-map.yml) runs every Monday at 03:00 UTC and:
1. looks up the date of the latest data on Geofabrik and skips the run if a release for that date already exists;
2. downloads `ukraine-latest.osm.pbf` and verifies its MD5;
3. builds the map and the manifest;
4. publishes a `map-YYYY-MM-DD` release;
5. keeps the 4 most recent maps and deletes older ones.

You can run it manually from the Actions tab → Build map → Run workflow. The `force` option rebuilds the map for the same date.

## Local build
```bash
./gradlew test installDist
curl -L -o data/ukraine.osm.pbf https://download.geofabrik.de/europe/ukraine-latest.osm.pbf
build/install/map-builder/bin/map-builder data/ukraine.osm.pbf out/ukraine.drg \
    --manifest out/manifest.json --osm-date 2026-09-29 [--no-tracks] [--bbox minLat,minLon,maxLat,maxLon]
```
All of Ukraine builds in about 40 s and needs up to 3 GB of RAM.

## Format and app compatibility
The format files in `src/main/kotlin/ua/pryimak/drnav/graph/` are a copy of the `graph` module from the app repository. **When changing the format, update both places and bump `GraphFormat.VERSION`.** The app compares `formatVersion` in the manifest with its own version and won't download a map it can't read.

## Data license
Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), licensed under the [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/). The built maps are a derivative database and are distributed under the same terms.
