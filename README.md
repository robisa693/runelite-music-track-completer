# Music Cape Helper

A RuneLite plugin that helps you complete the music log and unlock the **music cape**. Shows all music tracks organized by area, with unlock hints for tracks you haven't found yet — and guides you to each track's unlock spot on the world map and in the game world.

## Features

- Side panel listing all music tracks with their area
- Track name, unlock status (green = unlocked, red = locked), and unlock hint
- "Missing only" toggle to show just what you need
- Search/filter by track name
- Summary counts (total unlocked / total tracks)
- Unlock state persisted between sessions
- **Wiki mode** – click any track to open its OSRS Wiki page for full details
- **Map mode** – click any track to mark its unlock location:
  - The **world map** jumps to the spot and shows the unlock **area highlighted** (not just a dot), for every known location of the track
  - If the map is closed, a flashing **infobox** reminds you to open it
  - When you get near the spot, the tile is **highlighted on the ground** with the track name, and a **hint arrow** points to it — this works in **dungeons and caves** too, where the world map can't reach
  - **Underground tracks** (e.g. Sarachnis in Forthos Dungeon) mark the **surface area directly above the dungeon** on the world map, with its outline projected onto the ground above — head there, find the entrance, and the in-game highlight takes over once you're inside
  - Instanced areas the map can't represent at all open the wiki instead
- **✕ button** to clear the marker, highlights and hint arrow

## Why?

The in-game music player already shows locked tracks, but it's tedious to scroll through every area and read each tooltip one by one. This plugin lists all unlock hints at once, grouped by area, and then actually takes you there: pick a missing track, jump the world map to its unlock area, and follow the on-the-ground highlight for the final steps — like the clue scroll helper, but for music tracks.

## Usage

1. Install and enable the plugin
2. Open the **Music tab** (keyboard shortcut or click the music icon)
3. The **Music Cape Helper** panel populates from the Music tab data
4. Click the music note icon in the RuneLite sidebar to open the panel
5. Toggle "Missing only" to filter to locked tracks
6. Use the search box to find specific tracks
7. Switch between **Wiki** and **Map** at the top of the panel, then click a track

> **Note:** The plugin observes the vanilla Music tab to determine which tracks are unlocked. Open the Music tab with the "All" filter selected at least once so every track gets evaluated. The unlocked state is cached between sessions.

## Configuration

Day-to-day controls (missing-only filter, Wiki/Map click mode, search, clear marker) live directly in the side panel. The settings page only holds the in-game guidance options:

- **Highlight location in scene** – Ground highlight when near the unlock spot
- **Show hint arrow** – Minimap arrow when near the unlock spot
- **Highlight area on world map** – Draw the unlock area as a region on the world map
- **Highlight color** – Color used for surface highlights and markers
- **Underground color** – Color used for the surface-above-a-dungeon markers
