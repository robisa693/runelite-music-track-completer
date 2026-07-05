#!/usr/bin/env python3
"""Fetch in-game world map area names and bounds -> map_areas.json.

The in-game world map has a "map list" (bottom of the map) with one entry per
map area: Gielinor Surface, Ancient Cavern, Cam Torum, Mor Ul Rek, ... Bounds
for each area come from the wiki's map engine config (basemaps.json), which is
baked from the game cache's map area definitions, so a simple rectangle test
tells which area can display a coordinate. Names come from the wiki's mapIDs
table because they match the in-game map list labels ("Gielinor Surface",
where basemaps.json says "RuneScape Surface") - the plugin string-matches
these against the map list widget entries to highlight the right row.

Only in-game areas (id < 10000) are kept; ids 10000+ are wiki-only maps.

Writes src/main/resources/map_areas.json.
"""

import json
import os
import re
import sys
import urllib.request

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
from scrape_track_coords import get_wikitext  # noqa: E402

BASEMAPS_URL = "https://maps.runescape.wiki/osrs/data/basemaps.json"
MAPIDS_PAGE = "RuneScape:Map/mapIDs"
OUT = os.path.join(SCRIPT_DIR, "..", "src", "main", "resources", "map_areas.json")

# basemaps.json predates Song of the Elves and lacks every area added since
# (and id 29). Curated approximate bounds for those, centred on the wiki
# mapIDs table centers. Precision only affects which map list entry gets
# suggested/highlighted - markers themselves are gated by the client - so a
# generous box is fine. Replace with authoritative data if a live source for
# the current basemaps config turns up. The ocean underground areas (46-51)
# are deliberately absent: no music tracks live there and their extents are
# unknown.
CURATED_BOUNDS = {
    8: [[2839, 6295], [3031, 6487]],    # Ghorrock Prison (id reused, see below)
    29: [[3136, 5952], [3392, 6208]],   # Prifddinas
    34: [[3136, 12352], [3392, 12608]], # Prifddinas Underground
    35: [[2495, 6015], [2751, 6271]],   # Prifddinas Grand Library
    36: [[3264, 5632], [3648, 6016]],   # LMS Desert Island
    37: [[1567, 5983], [1823, 6239]],   # Tutorial Island
    38: [[3392, 5952], [3712, 6288]],   # LMS Wild Varrock
    39: [[2880, 5696], [3032, 5832]],   # Ruins of Camdozaal
    40: [[2944, 4736], [3136, 4928]],   # The Abyss
    41: [[2560, 6272], [2752, 6464]],   # Lassar Undercity
    42: [[3036, 9216], [3712, 9792]],   # Kharidian Desert Underground (incl. old Kalphite Hives)
    43: [[1152, 9280], [1920, 9728]],   # Varlamore Underground (Cam Torum/Neypotzli overlap it; smaller wins)
    44: [[1344, 9472], [1536, 9664]],   # Cam Torum
    45: [[1344, 9568], [1536, 9728]],   # Neypotzli
}

# Stale basemaps entries to discard: id 8 was "Kalphite Hives" in 2019 (now
# merged into 42 Kharidian Desert Underground) and has been reused in-game
# for Ghorrock Prison, so its old bounds must not carry the new name.
SKIP_STALE_IDS = {8}

# The in-game map list was renamed for these since the 2019 snapshot; the
# current wiki table already has the new names, listed here just for
# documentation: 0 "RuneScape Surface" -> "Gielinor Surface",
# 23 "TzHaar Area" -> "Mor Ul Rek".


def main():
    req = urllib.request.Request(
        BASEMAPS_URL, headers={"User-Agent": "music-cape-helper data generator"})
    with urllib.request.urlopen(req, timeout=30) as r:
        basemaps = json.load(r)

    names = {}
    wt = get_wikitext(MAPIDS_PAGE)
    if wt:
        rows = re.findall(r"\|\s*(-?\d+)\s*\|\|\s*([^|]+?)\s*\|\|", wt)
        names = {int(i): n.strip() for i, n in rows}

    areas = []
    seen = set()
    for m in basemaps:
        map_id = m.get("mapId")
        bounds = m.get("bounds")
        if map_id is None or map_id < 0 or map_id >= 10000 or not bounds \
                or map_id in SKIP_STALE_IDS:
            continue
        seen.add(map_id)
        areas.append({
            "id": map_id,
            "name": names.get(map_id, m.get("name", f"Area {map_id}")),
            "bounds": bounds,
        })

    for map_id, bounds in CURATED_BOUNDS.items():
        if map_id in seen:
            continue
        areas.append({
            "id": map_id,
            "name": names.get(map_id, f"Area {map_id}"),
            "bounds": bounds,
        })

    areas.sort(key=lambda a: a["id"])
    with open(OUT, "w") as f:
        json.dump(areas, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {len(areas)} areas to {OUT}")


if __name__ == "__main__":
    main()
