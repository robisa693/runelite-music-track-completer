#!/usr/bin/env python3
"""Fetch in-game world map area names, bounds and occupied squares -> map_areas.json.

The in-game world map has a "map list" (bottom of the map) with one entry per
map area: Gielinor Surface, Ancient Cavern, Cam Torum, Mor Ul Rek, ... Bounds
for each area come from the wiki's map engine config (basemaps.json), which is
baked from the game cache's map area definitions. Names come from the wiki's
mapIDs table because they match the in-game map list labels ("Gielinor
Surface", where basemaps.json says "RuneScape Surface") - the plugin
string-matches these against the map list widget entries to highlight the
right row.

The engine bounds are viewport boxes, padded past the area's real extent, so
adjacent dungeon areas overlap (Dwarven Mines' box covers the Edgeville
Dungeon, which belongs to Misthalin Underground). To resolve containment
exactly, each area's occupied 64x64 map squares are probed from the wiki tile
server: a zoom-2 tile named {plane}_{x//64}_{y//64}.png exists only where the
area has rendered content, so a missing tile means an empty square. The
squares list is what the plugin tests against; bounds remain as a fallback
for curated areas that postdate the engine snapshot and have no tiles.

Only in-game areas (id < 10000) are kept; ids 10000+ are wiki-only maps.

Writes src/main/resources/map_areas.json.
"""

import json
import os
import re
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
from scrape_track_coords import get_wikitext  # noqa: E402

BASEMAPS_URL = "https://maps.runescape.wiki/osrs/data/basemaps.json"
TILE_URL = "https://maps.runescape.wiki/osrs/tiles/{map_id}_{version}/2/{plane}_{cx}_{cy}.png"
MAPIDS_PAGE = "RuneScape:Map/mapIDs"
OUT = os.path.join(SCRIPT_DIR, "..", "src", "main", "resources", "map_areas.json")
USER_AGENT = "music-cape-helper data generator"

# basemaps.json predates Song of the Elves and lacks every area added since
# (and id 29). Curated approximate bounds for those, centred on the wiki
# mapIDs table centers; no tiles exist for them, so they stay bounds-only.
# Precision only affects which map list entry gets suggested/highlighted -
# markers themselves are gated by the client - so a generous box is fine.
# Ocean underground areas are added only as verified in-game (Unquiet Ocean
# covers Sophanem Dungeon); the others (46, 48-51) are absent because their
# extents are unknown.
CURATED_BOUNDS = {
    8: [[2839, 6295], [3031, 6487]],    # Ghorrock Prison (id reused, see below)
    29: [[3136, 5952], [3392, 6208]],   # Prifddinas (does NOT render the Zalcano pocket at x~3040, verified in-game)
    34: [[3136, 12352], [3392, 12608]], # Prifddinas Underground
    35: [[2495, 6015], [2751, 6271]],   # Prifddinas Grand Library
    36: [[3264, 5632], [3648, 6016]],   # LMS Desert Island
    37: [[1567, 5983], [1823, 6239]],   # Tutorial Island
    38: [[3392, 5952], [3712, 6288]],   # LMS Wild Varrock
    39: [[2880, 5696], [3032, 5832]],   # Ruins of Camdozaal
    40: [[2944, 4736], [3136, 4928]],   # The Abyss
    41: [[2560, 6272], [2752, 6464]],   # Lassar Undercity
    42: [[3036, 9296], [3556, 9640]],   # Kharidian Desert Underground (stops before the Temple of the Eye instance and Morytania's Barrows strip)
    43: [[1152, 9280], [1920, 9728]],   # Varlamore Underground (Cam Torum/Neypotzli overlap it; smaller wins)
    44: [[1344, 9472], [1536, 9664]],   # Cam Torum
    45: [[1344, 9600], [1536, 9728]],   # Neypotzli (below Cam Torum; keep the city center out of its box)
    47: [[3072, 8832], [3712, 9280]],   # Unquiet Ocean Underground (desert coast; covers Sophanem Dungeon)
}

# Stale basemaps entries to discard: id 8 was "Kalphite Hives" in 2019 (now
# merged into 42 Kharidian Desert Underground) and has been reused in-game
# for Ghorrock Prison, so neither its old bounds nor its old tiles may carry
# the new name.
SKIP_STALE_IDS = {8}

# Areas whose current wiki-table center is the wiki map pocket rather than
# the in-game position; the snapshot bounds are still correct in-game
# (verified: Tolna's Rift appears in the map list at its old dungeon-band
# spot), so the center-inside-bounds staleness check must not reject them.
VALIDATION_EXEMPT = {21}

# The in-game map list was renamed for these since the 2019 snapshot; the
# current wiki table already has the new names, listed here just for
# documentation: 0 "RuneScape Surface" -> "Gielinor Surface",
# 23 "TzHaar Area" -> "Mor Ul Rek".


def tile_exists(map_id, version, plane, cx, cy):
    url = TILE_URL.format(map_id=map_id, version=version, plane=plane, cx=cx, cy=cy)
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=30):
            return True
    except urllib.error.HTTPError:
        return False


def probe_squares(map_id, version, bounds):
    """Occupied 64x64 map squares within the bounds, from the tile server."""
    chunks = [
        (cx, cy)
        for cx in range(bounds[0][0] // 64, bounds[1][0] // 64 + 1)
        for cy in range(bounds[0][1] // 64, bounds[1][1] // 64 + 1)
    ]

    def check(chunk):
        cx, cy = chunk
        # Content is almost always rendered on plane 0; a few upper-level-only
        # squares (e.g. Troll Stronghold ledges) exist on plane 1 alone.
        if tile_exists(map_id, version, 0, cx, cy) or tile_exists(map_id, version, 1, cx, cy):
            return chunk
        return None

    with ThreadPoolExecutor(max_workers=16) as pool:
        return sorted(c for c in pool.map(check, chunks) if c)


def main():
    req = urllib.request.Request(
        BASEMAPS_URL, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as r:
        basemaps = json.load(r)

    names, centers = {}, {}
    wt = get_wikitext(MAPIDS_PAGE)
    if wt:
        rows = re.findall(r"\|\s*(-?\d+)\s*\|\|\s*([^|]+?)\s*\|\|\s*\((\d+),\s*(\d+)\)", wt)
        names = {int(i): n.strip() for i, n, _, _ in rows}
        centers = {int(i): (int(x), int(y)) for i, _, x, y in rows}

    areas = []
    seen = set()
    # Ids with their own snapshot entry can't be another area's new identity.
    snapshot_ids = {
        m.get("mapId") for m in basemaps
        if m.get("mapId") is not None and 0 <= m.get("mapId") < 10000 and m.get("bounds")
    }
    for m in basemaps:
        map_id = m.get("mapId")
        bounds = m.get("bounds")
        version = m.get("cacheVersion")
        if map_id is None or map_id < 0 or map_id >= 10000 or not bounds \
                or map_id in SKIP_STALE_IDS:
            continue
        tile_id = map_id  # tiles are stored under the snapshot's id
        name = names.get(map_id)
        if name is None:
            # The area fell out of the current in-game map list since the
            # snapshot. If exactly one current area's center sits inside its
            # bounds, the area was renamed/regrouped (Karamja Underground ->
            # Ardent Ocean Underground in the ocean reorg): adopt the current
            # id and name, keeping the probed squares. Otherwise drop it -
            # suggesting a map list entry that no longer exists guides nowhere.
            adopted = [
                i for i, (cx, cy) in centers.items()
                if 1 <= i < 10000 and i not in snapshot_ids
                and bounds[0][0] <= cx <= bounds[1][0] and bounds[0][1] <= cy <= bounds[1][1]
            ]
            if len(adopted) == 1:
                map_id = adopted[0]
                name = names[map_id]
                print(f"  renamed stale area {m.get('mapId')} ({m.get('name')}) -> {map_id} ({name})")
            else:
                print(f"  dropped stale area {m.get('mapId')} ({m.get('name')}): "
                      f"{len(adopted)} current centers inside")
                continue
        seen.add(map_id)
        area = {
            "id": map_id,
            "name": name,
            "bounds": bounds,
        }
        # The surface needs no square list: the plugin classifies y < 4160 as
        # surface directly, and probing its ~2700 squares would be wasteful.
        if map_id != 0 and version:
            area["squares"] = probe_squares(tile_id, version, bounds)
            print(f"{map_id:3d} {area['name']}: {len(area['squares'])} squares")
        areas.append(area)

    for map_id, bounds in CURATED_BOUNDS.items():
        if map_id in seen:
            continue
        areas.append({
            "id": map_id,
            "name": names.get(map_id, f"Area {map_id}"),
            "bounds": bounds,
        })

    # An area whose current in-game center falls outside its bounds has been
    # moved since the bounds were sourced (how Tolna's Rift went stale) - its
    # box would sit somewhere wrong and steal containment from the area that
    # really covers those coordinates. Fail loudly so it gets curated.
    stale = []
    for a in areas:
        c = centers.get(a["id"])
        if not c or a["id"] in VALIDATION_EXEMPT:
            continue
        b = a["bounds"]
        if not (b[0][0] <= c[0] <= b[1][0] and b[0][1] <= c[1] <= b[1][1]):
            stale.append(f"{a['id']} {a['name']}: center {c} outside bounds {b}")
    if stale:
        raise SystemExit("moved map areas need curated bounds:\n  " + "\n  ".join(stale))

    areas.sort(key=lambda a: a["id"])
    with open(OUT, "w") as f:
        json.dump(areas, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {len(areas)} areas to {OUT}")


if __name__ == "__main__":
    main()
