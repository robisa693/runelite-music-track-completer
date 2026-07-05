#!/usr/bin/env python3
"""Scrape music track coordinates from OSRS Wiki.

Extracts polygon/area coordinates from each track page and Map: subpages,
computes centroids, and outputs track_coords.json.
"""

import json
import os
import re
import sys
import time
from urllib.parse import quote

import requests

API = "https://oldschool.runescape.wiki/api.php"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUT_DIR = os.path.join(SCRIPT_DIR, "..", "src", "main", "resources")
OUTPUT_FILE = os.path.join(OUTPUT_DIR, "track_coords.json")
MISSING_CATEGORY = "Category:Missing_track_location"

SESSION = requests.Session()
SESSION.headers.update({"User-Agent": "MusicCapeHelperCoordScraper/1.0"})

def api_call(params):
    params["format"] = "json"
    time.sleep(0.35)
    resp = SESSION.get(API, params=params)
    resp.raise_for_status()
    return resp.json()

def get_category_members(category, limit=500):
    pages = []
    cmcontinue = None
    while True:
        params = {
            "action": "query",
            "list": "categorymembers",
            "cmtitle": category,
            "cmlimit": min(limit, 500),
            "cmtype": "page",  # exclude subcategories/files that also live under this category
        }
        if cmcontinue:
            params["cmcontinue"] = cmcontinue

        data = api_call(params)
        for m in data.get("query", {}).get("categorymembers", []):
            pages.append(m["title"])

        cont = data.get("continue", {})
        cmcontinue = cont.get("cmcontinue")
        if not cmcontinue:
            break
    return pages

def get_wikitext(title):
    data = api_call({
        "action": "parse",
        "page": title,
        "prop": "wikitext",
        "redirects": 1,
    })
    parsed = data.get("parse")
    if not parsed:
        return None
    wt = parsed.get("wikitext", {}).get("*")
    return wt

def parse_coord_pair(text):
    text = text.strip()
    m = re.match(r'(\d+\.?\d*)\s*,\s*(\d+\.?\d*)', text)
    if m:
        return [float(m.group(1)), float(m.group(2))]
    return None

def find_balanced_template_blocks(wikitext, name):
    """Find the content of all {{name|...}} invocations (case-insensitive
    template name), correctly handling nested braces such as inner templates
    ({{mainonly|yes}}) or parameter placeholders ({{{height|300}}}) that a
    naive "stop at the first }" regex would truncate on.
    """
    blocks = []
    pattern = re.compile(r'\{\{\s*' + re.escape(name) + r'\s*\|', re.IGNORECASE)
    n = len(wikitext)
    for m in pattern.finditer(wikitext):
        start = m.end()
        depth = 2  # already consumed the opening "{{"
        i = start
        while i < n and depth > 0:
            c = wikitext[i]
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
            i += 1
        end = i - 2 if depth == 0 else i
        if end < start:
            end = start
        blocks.append(wikitext[start:end])
    return blocks

def split_top_level(content, sep="|"):
    """Split on sep, but only at brace-depth 0, so nested templates like
    {{mainonly|yes}} inside a parameter value aren't torn apart.
    """
    parts = []
    depth = 0
    current = []
    for ch in content:
        if ch == "{":
            depth += 1
            current.append(ch)
        elif ch == "}":
            depth -= 1
            current.append(ch)
        elif ch == sep and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    parts.append("".join(current))
    return parts

def clean_param_value(v):
    """Unwrap simple single-argument templates like {{mainonly|yes}} -> "yes"
    so downstream naming/display logic doesn't leak raw wikitext.
    """
    v = v.strip()
    m = re.match(r'^\{\{[^|{}]+\|([^{}]+)\}\}$', v)
    if m:
        return m.group(1).strip()
    return v

def parse_inline_map_template(content):
    content = content.strip()
    parts = [p.strip() for p in split_top_level(content)]
    coords = []
    params = {}
    for part in parts:
        if "=" in part:
            k, v = part.split("=", 1)
            params[k.strip()] = clean_param_value(v)
        else:
            pair = parse_coord_pair(part)
            if pair:
                coords.append(pair)
    plane = int(params.get("plane", "0"))
    map_id = params.get("mapID", "-1")
    mtype = params.get("mtype", "")
    group = params.get("group", "")
    bucket = params.get("bucket", "")
    rect_x = params.get("rectX")
    rect_y = params.get("rectY")
    return {
        "coords": coords,
        "plane": plane,
        "mapID": map_id,
        "type": mtype,
        "group": group,
        "bucket": bucket,
        "rectX": rect_x,
        "rectY": rect_y,
    }

def expand_shape_coords(im):
    """Some map features (e.g. mtype=rectangle) are defined by a single
    centre point plus separate side-length parameters rather than an
    explicit polygon outline. Expand those into a 4-corner box so callers
    always get a usable coordinate list. Other single/multi-point feature
    types (pin, dot, text, media, circle, square, point, etc.) are passed
    through unchanged.
    """
    coords = im["coords"]
    if im.get("type", "").lower() == "rectangle" and len(coords) == 1 and im.get("rectX") and im.get("rectY"):
        try:
            x, y = coords[0]
            half_x = float(im["rectX"]) / 2
            half_y = float(im["rectY"]) / 2
            return [
                [x - half_x, y - half_y],
                [x - half_x, y + half_y],
                [x + half_x, y + half_y],
                [x + half_x, y - half_y],
            ]
        except (ValueError, TypeError):
            return coords
    return coords

def extract_inline_maps(wikitext):
    maps = []
    for content in find_balanced_template_blocks(wikitext, "Map"):
        parsed = parse_inline_map_template(content)
        # Single-point features (pins, dots, rectangles, circles, etc.) are
        # valid map data too -- not just multi-point polygons/lines -- so we
        # only require at least one coordinate.
        if len(parsed["coords"]) >= 1:
            maps.append(parsed)
    return maps

def extract_infobox_map_ref(wikitext):
    # Template names are case-insensitive on the wiki ({{Map:X}} vs {{map:X}}).
    m = re.search(r'\|\s*map\s*=\s*\{\{[Mm]ap:([^}]+)\}\}', wikitext)
    if m:
        subpage = "Map:" + m.group(1).strip()
        return subpage
    return None

def parse_subpage_data(wikitext):
    x_m = re.search(r'\|\s*x\s*=\s*([\d.]+)', wikitext)
    y_m = re.search(r'\|\s*y\s*=\s*([\d.]+)', wikitext)
    if not x_m or not y_m:
        return None
    center = [float(x_m.group(1)), float(y_m.group(1))]

    plane = 0
    plane_m = re.search(r'\|\s*plane\s*=\s*(\d+)', wikitext)
    if plane_m:
        plane = int(plane_m.group(1))

    map_id = None
    map_id_m = re.search(r'\|\s*mapID\s*=\s*(\d+)', wikitext)
    if map_id_m:
        map_id = int(map_id_m.group(1))

    polygon = []
    pt_pattern = re.compile(r'\[(\d+\.?\d*)\s*,\s*(\d+\.?\d*)\]')
    for match in pt_pattern.finditer(wikitext):
        polygon.append([float(match.group(1)), float(match.group(2))])

    return {
        "center": center,
        "plane": plane,
        "polygon": polygon,
        "mapId": map_id,
    }

def centroid(coords):
    if not coords:
        return None
    xs = [c[0] for c in coords]
    ys = [c[1] for c in coords]
    return [round(sum(xs) / len(xs), 1), round(sum(ys) / len(ys), 1)]

def resolve_inline_map_name(wikitext):
    m = re.search(r'\|\s*link\s*=\s*([^|}]+)', wikitext)
    if m:
        return m.group(1).strip()
    m = re.search(r'\|\s*mapname\s*=\s*([^|}]+)', wikitext)
    if m:
        return m.group(1).strip()
    return None

def build_locations_from_inline_maps(inline_maps, existing_locations):
    """Turn a list of parsed {{Map|...}} feature dicts into location entries,
    appending them to (and de-duplicating against) existing_locations.
    """
    new_locations = []
    multi_group_seen = set()
    for im in inline_maps:
        # Historical features mark removed content - guiding players to them
        # marks empty space. bucket=hist is always historical; bucket=no also
        # appears on perfectly current features (A Festive Party's Party Room),
        # so it only counts as historical when the feature's group says so.
        group = (im.get("group") or "").lower()
        if im["bucket"] == "hist" or "hist" in group or "old" in group:
            continue
        coords = expand_shape_coords(im)
        c = centroid(coords)
        if not c:
            continue
        full_center = c + [im["plane"]]

        if im["group"] and im["group"] not in multi_group_seen:
            multi_group_seen.add(im["group"])
            loc_name = f"Region {im['group']}" if im["group"] else "Location"
        else:
            loc_name = "Location"

        if im["bucket"]:
            loc_name = f"{loc_name} ({im['bucket']})"

        all_locations = existing_locations + new_locations
        if not any(loc["center"] == full_center for loc in all_locations):
            entry = {
                "name": loc_name,
                "center": full_center,
                "polygon": coords,
            }
            # Record which named wiki map the feature belongs to (mapID); the
            # main surface map is -1/absent and is left out.
            try:
                map_id = int(im.get("mapID", "-1"))
                if map_id >= 0:
                    entry["mapId"] = map_id
            except (ValueError, TypeError):
                pass
            new_locations.append(entry)
    return new_locations

def main():
    print("Fetching music track pages...")
    all_pages = get_category_members("Category:Music_tracks")
    print(f"  Found {len(all_pages)} pages total")

    print("Fetching missing-track-location pages...")
    missing_pages = set(get_category_members(MISSING_CATEGORY))
    print(f"  {len(missing_pages)} pages have no location data")

    output_data = {}

    skipped_pages = {"7th Realm"}

    for i, page in enumerate(all_pages):
        if page in skipped_pages:
            continue

        if (i + 1) % 50 == 0:
            print(f"  Processing page {i + 1}/{len(all_pages)}...")

        wt = get_wikitext(page)
        if wt is None:
            print(f"  WARNING: Could not fetch wikitext for {page}")
            continue

        track_name = page.replace(" (music track)", "").strip()
        if track_name == page:
            track_name = page

        locations = []

        in_missing = page in missing_pages

        if not in_missing:
            subpage = extract_infobox_map_ref(wt)
            if subpage:
                sub_wt = get_wikitext(subpage)
                if sub_wt:
                    sub_data = parse_subpage_data(sub_wt)
                    if sub_data:
                        loc_name = resolve_inline_map_name(sub_wt) or subpage.replace("Map:", "").replace(" music", "").strip()
                        full_center = sub_data["center"] + [sub_data["plane"]]
                        entry = {
                            "name": loc_name,
                            "center": full_center,
                            "polygon": sub_data["polygon"],
                        }
                        if sub_data.get("mapId") is not None:
                            entry["mapId"] = sub_data["mapId"]
                        locations.append(entry)
                    else:
                        # Some Map: subpages don't use the explicit x=/y=
                        # "Music track map" format; instead they embed one or
                        # more raw {{Map|...}} feature templates directly
                        # (e.g. Map:Rugged Terrain). Fall back to extracting
                        # those the same way we do for the main page.
                        sub_inline_maps = extract_inline_maps(sub_wt)
                        locations.extend(build_locations_from_inline_maps(sub_inline_maps, locations))

            inline_maps = extract_inline_maps(wt)
            locations.extend(build_locations_from_inline_maps(inline_maps, locations))

        # Always record an entry for every page we processed -- even when no
        # locations were found -- so pages are never silently dropped from
        # the output (this previously happened e.g. for "Rugged Terrain",
        # which has a map= subpage reference but that subpage's data didn't
        # match the expected format, so it fell through every branch below).
        output_data[track_name] = locations

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    with open(OUTPUT_FILE, "w") as f:
        json.dump(output_data, f, indent=2, ensure_ascii=False)
    print(f"\nDone! Wrote {OUTPUT_FILE}")
    print(f"  Tracks with coords: {sum(1 for v in output_data.values() if v)}")
    print(f"  Tracks without coords: {sum(1 for v in output_data.values() if not v)}")
    print(f"  Total entries: {len(output_data)}")

if __name__ == "__main__":
    main()
