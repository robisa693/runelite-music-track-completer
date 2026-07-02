# Music Track Completer

A RuneLite plugin that helps you complete the music log and unlock the **music cape**. Shows all music tracks organized by area, with unlock hints for tracks you haven't found yet.

## Features

- Side panel listing all music tracks grouped by area
- Track name, unlock status (green = unlocked, red = locked), and unlock hint
- "Missing only" toggle to show just what you need
- Search/filter by track name
- Summary counts (total unlocked / total tracks)
- Click any track to open its **OSRS Wiki page** for full details (unlock conditions, length, members/f2p, release date)
- Unlock state persisted between sessions

## Why?

The in-game music player only lists tracks you've already unlocked. This plugin shows you **everything** — including tracks you're missing and where to find them — so you can systematically complete the music log and get the music cape.

## Usage

1. Install and enable the plugin
2. Open the **Music tab** (keyboard shortcut or click the music icon)
3. The **Music Track Completer** panel populates from the Music tab data
4. Click the music note icon in the RuneLite sidebar to open the panel
5. Toggle "Missing only" to filter to locked tracks
6. Use the search box to find specific tracks

> **Note:** The plugin observes the vanilla Music tab to determine which tracks are unlocked. Open the Music tab with the "All" filter selected at least once so every track gets evaluated. The unlocked state is cached between sessions.

## Configuration

- **Show missing only** – Only display locked tracks
- **Group by area** – Organize tracks by area
