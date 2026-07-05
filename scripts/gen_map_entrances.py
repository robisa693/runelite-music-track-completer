#!/usr/bin/env python3
"""Assign mid-band track locations to named wiki maps and emit map_entrances.json.

Mid-band locations (4160 <= y < 6400) live on self-contained named maps (Rat
Pits, Keldagrim, God Wars Dungeon, ...). The wiki's mapID table
(RuneScape:Map/mapIDs) lists each named map with its center in that same
coordinate space, so every location is assigned the nearest map center
(within 512 tiles). The curated ENTRANCES table below then gives each zone a
surface entrance point (or null + access note for teleport-only/instanced
zones).

Writes:
- "mapId" onto matching locations in track_coords.json
- src/main/resources/map_entrances.json
"""

import json
import os
import re
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
from scrape_track_coords import get_wikitext  # noqa: E402

COORDS = os.path.join(SCRIPT_DIR, "..", "src", "main", "resources", "track_coords.json")
ENTRANCES_OUT = os.path.join(SCRIPT_DIR, "..", "src", "main", "resources", "map_entrances.json")
MAPIDS_PAGE = "RuneScape:Map/mapIDs"

# Curated: mapId -> (entrance [x, y] on the surface or None, access note)
ENTRANCES = {
    1: ([2512, 3508], "Dive into the whirlpool at Otto's Grotto, Baxtorian Falls"),
    4: ([3680, 3495], "Sail with Pirate Pete, north-east of the Ectofuntus (Rum Deal)"),
    5: ([3208, 3216], "Tunnel in the Lumbridge Castle cellar (Death to the Dorgeshuun)"),
    7: ([2916, 3746], "Cave entrance north of Trollheim"),
    13: ([2996, 3384], "Mole hill in Falador Park (use a spade)"),
    16: ([2452, 3231], "Cave entrance south of West Ardougne, near the Battlefield"),
    18: ([3081, 3421], "Hole next to the well in Barbarian Village"),
    21: ([3310, 3452], "Rift east of Varrock (A Soul's Bane)"),
    23: ([2857, 3169], "Inside the Karamja volcano (TzHaar area)"),
    24: ([3440, 3232], "Via the Abandoned Mine, south-west Morytania (Haunted Mine)"),
    28: ([3201, 3169], "Shed in Lumbridge Swamp (Lost City) or fairy rings"),
    29: ([2240, 3270], "Enter Prifddinas, Tirannwn (Song of the Elves)"),
    35: ([2240, 3270], "Inside Prifddinas - the Grand Library"),
    36: ([3138, 3635], "Last Man Standing lobby, Ferox Enclave"),
    37: (None, "Tutorial Island"),
    38: ([3138, 3635], "Last Man Standing lobby, Ferox Enclave"),
    39: ([3000, 3494], "Ruins below Ice Mountain (Below Ice Mountain)"),
    40: ([3102, 3557], "Mage of Zamorak, low Wilderness north of Edgeville"),
    41: (None, "Lassar Undercity, beneath the Shattered Relics area (Desert Treasure II)"),
    10001: ([3440, 3232], "Abandoned Mine, south-west Morytania"),
    10003: ([3440, 3232], "Abandoned Mine, south-west Morytania"),
    10004: ([3440, 3232], "Abandoned Mine, south-west Morytania"),
    10005: ([3440, 3232], "Abandoned Mine, south-west Morytania"),
    10006: ([3102, 3557], "Mage of Zamorak, low Wilderness north of Edgeville"),
    10009: ([2985, 3292], "Air altar ruins south of Falador"),
    10010: (None, "Airship platform (Monkey Madness II)"),
    10012: ([2512, 3508], "Dive into the whirlpool at Otto's Grotto, Baxtorian Falls"),
    10013: (None, "Quest cutscene (Another Slice of H.A.M.)"),
    10019: ([2531, 3577], "Barbarian Outpost, north of Baxtorian Falls"),
    10020: ([2531, 3577], "Barbarian Outpost, north of Baxtorian Falls"),
    10022: ([2730, 3713], "Inside Keldagrim (tunnel east of Rellekka) - the Blast Furnace"),
    10024: (None, "Quest area"),
    10028: ([2900, 3565], "Games room in Burthorpe castle"),
    10029: (None, "Quest cutscene (Cabin Fever)"),
    10031: (None, "Cave in the Wilderness or games necklace teleport (Corporeal Beast)"),
    10032: ([3201, 3169], "In Zanaris (via the Lumbridge Swamp shed)"),
    10033: (None, "Fairytale II dream"),
    10034: (None, "Quest cutscene (King's Ransom)"),
    10036: ([2433, 3513], "Crash Site Cavern, north-west of the Gnome Stronghold (Monkey Madness II)"),
    10037: ([2648, 3212], "Basement of the Tower of Life, south of East Ardougne"),
    10039: (None, "Death altar, Temple of Light (Mourning's End Part II)"),
    10044: (None, "Quest instance (Between a Rock...)"),
    10045: ([3208, 3216], "Via the Lumbridge Castle cellar tunnel"),
    10046: (None, "Quest cutscene (Dragon Slayer)"),
    10047: (None, "Dream World (Lunar Diplomacy)"),
    10048: (None, "Dream World (Dream Mentor)"),
    10049: (None, "Dream World (Lunar Diplomacy)"),
    10050: (None, "Random event"),
    10052: ([2328, 3496], "Eagles' Peak, west of the Piscatoris Fishing Colony"),
    10055: (None, "Fairy ring code B-K-Q (Enchanted Valley)"),
    10056: (None, "Balloon transport cutscene (Enlightened Journey)"),
    10057: (None, "Random event"),
    10058: (None, "In Zanaris - use a raw chicken at the Chicken Shrine"),
    10059: (None, "Random event"),
    10060: (None, "Quest cutscene (The Eyes of Glouphrie)"),
    10061: (None, "Fairytale II - fairy ring travel"),
    10062: (None, "Fisher Realm - magic whistle (Holy Grail)"),
    10063: (None, "Fisher Realm - magic whistle (Holy Grail)"),
    10064: ([2675, 3163], "Board the Fishing Trawler at Port Khazard"),
    10065: ([2675, 3163], "Board the Fishing Trawler at Port Khazard"),
    10068: (None, "Quest finale (Song of the Elves)"),
    10069: (None, "Random event"),
    10073: (None, "Fairy ring code D-I-R (Gorak Plane)"),
    10074: ([3166, 3252], "H.A.M. hideout trapdoor, west of Lumbridge"),
    10075: ([3612, 3362], "Hallowed Sepulchre, entered in Darkmeyer"),
    10076: ([3612, 3362], "Hallowed Sepulchre, entered in Darkmeyer"),
    10078: (None, "Quest instance (Song of the Elves)"),
    10080: ([3233, 2897], "Jaldraocht Pyramid, western desert (Desert Treasure)"),
    10087: (None, "Quest instance (King's Ransom)"),
    10088: ([2730, 3713], "Rat pit inside Keldagrim (Ratcatchers)"),
    10089: ([3108, 3353], "Portal on the top floor of Draynor Manor (Ernest the Chicken area)"),
    10090: (None, "Quest instance (King's Ransom)"),
    10091: (None, "Random event"),
    10098: ([2509, 3636], "The Lighthouse, north of Barbarian Outpost (Horror from the Deep)"),
    10099: ([3540, 3986], "Lithkren, island north-west of Morytania (Dragon Slayer II)"),
    10103: ([3222, 3218], "Lumbridge Castle (Recipe for Disaster)"),
    10107: ([3619, 3313], "Meiyerditch, Morytania"),
    10108: (None, "Random event"),
    10111: (None, "Underground hangar, Gnome Stronghold (Monkey Madness)"),
    10113: ([2551, 3316], "Mourner HQ basement, West Ardougne"),
    10114: (None, "Shrunken mouse hole (Grim Tales)"),
    10115: (None, "Quest cutscene (My Arm's Big Adventure)"),
    10119: ([2869, 3019], "Nature altar ruins, north-east Karamja"),
    10121: (None, "Quest cutscene"),
    10127: (None, "Random event"),
    10128: (None, "Crop circle in a wheat field, or the centre of Zanaris (Puro-Puro)"),
    10129: ([3288, 2787], "Jalsavrah pyramid in Sophanem (Pyramid Plunder)"),
    10131: (None, "Random event"),
    10134: (None, "Via Jimmy Dazzler, East Ardougne (Ratcatchers)"),
    10135: (None, "Quest instance (Recipe for Disaster: Awowogei)"),
    10136: (None, "Quest instance (Recruitment Drive)"),
    10137: ([2905, 3537], "Under The Toad and Chicken inn, Burthorpe"),
    10138: ([2858, 3577], "Saba's cave near Death Plateau, Troll Country"),
    10139: ([2547, 3421], "Ladder west of the Fishing Guild - needs the ring of visibility (Desert Treasure)"),
    10142: ([3304, 2788], "Sophanem bank (Contact!)"),
    10144: ([3321, 3140], "Talk to the Apprentice in Al Kharid (Sorceress's Garden)"),
    10145: (None, "Random event"),
    10149: ([3403, 3485], "Start Temple Trekking at Paterdomus or Burgh de Rott"),
    10150: (None, "Quest area"),
    10151: (None, "Quest instance (Monkey Madness II)"),
    10153: ([2730, 3713], "Keldagrim train station (Another Slice of H.A.M.)"),
    10158: (None, "Tunnel of Chaos, east of Varrock (What Lies Below)"),
    10162: ([2434, 3315], "Underground Pass entrance, west of West Ardougne"),
    10164: ([2434, 3315], "Underground Pass entrance, west of West Ardougne"),
    10165: ([2434, 3315], "Underground Pass entrance, west of West Ardougne"),
    10170: ([3254, 3452], "Varrock Museum basement"),
    10172: ([3264, 3390], "Rat pit trapdoor in south-east Varrock (Ratcatchers)"),
    10173: ([2772, 2943], "Viyeldi caves, Kharazi Jungle (Legends' Quest)"),
    10178: (None, "Wilderness minigame area"),
    10179: ([2696, 3283], "Dungeon entrance in Witchaven, east of East Ardougne"),
    10180: ([2458, 2847], "Wrath altar, basement of the Myths' Guild"),
}


# Curated: tracks whose pocket has no map of its own on the wiki, so the
# nearest-center assignment picks a wrong neighbour (all three would get
# 10114 "Mouse hole"). Maps track name -> mapId, or None for instanced
# areas with no map at all (the plugin falls back to the wiki for those).
MAPID_OVERRIDES = {
    "Inferno": 23,           # unlocks entering Mor Ul Rek (wiki: Mor Ul Rek)
    "Darkly Altared": None,  # Skotizo's chamber
    "Monkey Sadness": None,  # Glough's laboratory (MM2 instance)
}


def main():
    wt = get_wikitext(MAPIDS_PAGE)
    if not wt:
        raise SystemExit(f"could not fetch {MAPIDS_PAGE} from the wiki")
    rows = re.findall(r'\|\s*(-?\d+)\s*\|\|\s*([^|]+?)\s*\|\|\s*\((\d+),\s*(\d+)\)', wt)
    maps = [(int(i), n.strip(), int(x), int(y)) for i, n, x, y in rows]
    mid = [m for m in maps if 4160 <= m[3] < 6400 and m[0] >= 1]
    names = {m[0]: m[1] for m in maps}

    with open(COORDS) as f:
        data = json.load(f)

    used = {}
    assigned = 0
    unassigned = []
    for track, locs in data.items():
        for l in locs:
            c = l.get("center")
            if not c or len(c) < 3 or not (4160 <= c[1] < 6400):
                continue
            if track in MAPID_OVERRIDES:
                override = MAPID_OVERRIDES[track]
                if override is None:
                    l.pop("mapId", None)
                else:
                    l["mapId"] = override
            elif "mapId" not in l:
                best, bd = None, float("inf")
                for mi, mn, mx, my in mid:
                    dist = (c[0] - mx) ** 2 + (c[1] - my) ** 2
                    if dist < bd:
                        bd, best = dist, mi
                if bd ** 0.5 <= 512:
                    l["mapId"] = best
            if "mapId" in l:
                used[l["mapId"]] = names.get(l["mapId"], "Unknown")
                assigned += 1
                if track == "Rat a Tat Tat" or track == "Rat Hunt":
                    print(f"  {track} -> mapId {l['mapId']} ({names.get(l['mapId'])})")
            else:
                unassigned.append((track, int(c[0]), int(c[1])))

    entrances_out = {}
    missing_curation = []
    for mi, name in sorted(used.items()):
        cur = ENTRANCES.get(mi)
        if cur is None:
            missing_curation.append((mi, name))
            entrances_out[str(mi)] = {"name": name, "entrance": None, "note": "See the wiki for access"}
        else:
            entrance, note = cur
            entrances_out[str(mi)] = {"name": name, "entrance": entrance, "note": note}

    # validate entrances
    for mi, e in entrances_out.items():
        ent = e["entrance"]
        if ent is not None:
            assert 1000 <= ent[0] <= 4300 and 2400 <= ent[1] < 4160, f"entrance out of surface range: {mi} {e}"

    with open(COORDS, "w") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    with open(ENTRANCES_OUT, "w") as f:
        json.dump(entrances_out, f, indent=2, ensure_ascii=False)

    with_e = sum(1 for e in entrances_out.values() if e["entrance"])
    print(f"\nassigned {assigned} locations across {len(used)} zones")
    print(f"entrances: {with_e} with surface coords, {len(entrances_out) - with_e} access-note only")
    print(f"unassigned (wiki fallback): {len(unassigned)}")
    if missing_curation:
        print("zones lacking curation (auto note):")
        for mi, n in missing_curation:
            print(f"  {mi} {n}")


if __name__ == "__main__":
    main()
