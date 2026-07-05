package com.robisa693.musiccapehelper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import javax.swing.Timer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

class MapNavigator
{
    private static final Logger log = LoggerFactory.getLogger(MapNavigator.class);
    private static final Type MAP_TYPE = new TypeToken<Map<String, List<MapLocation>>>() {}.getType();

    // The Gielinor Surface map area covers y < 4160. The band from there up to
    // y=6400 holds self-contained zones (Mor Ul Rek, the Inferno, raid and quest
    // instances, ...), and everything at y >= 6400 is a dungeon mapped exactly
    // 6400 tiles north of the ground it sits under.
    private static final int SURFACE_MAX_Y = 4160;
    private static final int UNDERGROUND_Y = 6400;

    private final Plugin plugin;
    private final Client client;
    private final ClientThread clientThread;
    private final WorldMapPointManager worldMapPointManager;
    private final InfoBoxManager infoBoxManager;
    private final MusicCapeHelperConfig config;
    private final Gson gson;

    private static final String GIELINOR_SURFACE = "Gielinor Surface";

    private Map<String, List<MapLocation>> coordsMap = new HashMap<>();
    private Map<String, ZoneEntrance> entrances = new HashMap<>();
    private Map<String, String> wikiLocations = new HashMap<>();
    private List<MapArea> areaIndex = Collections.emptyList();
    private final List<WorldMapPoint> activeMapPoints = new ArrayList<>();
    // A centering is owed to the user but no loaded map area could display a
    // marker yet; retried on every WORLDMAP_LOADMAP until one can.
    private boolean pendingCenter;
    // The map list entry the player should select to see the marker, shown by
    // the map list highlight overlay while a centering is pending.
    private volatile String suggestedArea;
    private final BufferedImage mapIcon;

    // Active navigation target: written on the client thread, read by overlays
    // (which also render on the client thread) and the panel (EDT) - keep the
    // list references immutable and volatile.
    private volatile String activeTrack;
    private volatile List<ActiveLocation> activeLocations = Collections.emptyList();
    private volatile List<DisplayArea> mapAreas = Collections.emptyList();
    private WorldPoint hintArrowPoint;

    private InfoBox openMapInfoBox;
    private Timer flashTimer;
    private int flashCount;
    private volatile boolean flashOn;

    MapNavigator(Plugin plugin, Client client, ClientThread clientThread, WorldMapPointManager worldMapPointManager, InfoBoxManager infoBoxManager, MusicCapeHelperConfig config, Gson gson)
    {
        this.plugin = plugin;
        this.client = client;
        this.clientThread = clientThread;
        this.worldMapPointManager = worldMapPointManager;
        this.infoBoxManager = infoBoxManager;
        this.config = config;
        this.gson = gson;
        this.mapIcon = createMapIcon(new Color(255, 200, 0));
        loadCoordinates();
    }

    private void loadCoordinates()
    {
        try (InputStreamReader reader = new InputStreamReader(
            getClass().getResourceAsStream("/track_coords.json"), StandardCharsets.UTF_8))
        {
            coordsMap = gson.fromJson(reader, MAP_TYPE);
        }
        catch (Exception e)
        {
            log.warn("Failed to load track coordinates", e);
        }
        if (coordsMap == null)
        {
            coordsMap = new HashMap<>();
        }

        // Surface entrances for self-contained underground zones (Rat Pits,
        // Keldagrim, ...), keyed by the wiki mapID of the zone. Optional resource.
        try (InputStreamReader reader = new InputStreamReader(
            getClass().getResourceAsStream("/map_entrances.json"), StandardCharsets.UTF_8))
        {
            Type type = new TypeToken<Map<String, ZoneEntrance>>() {}.getType();
            entrances = gson.fromJson(reader, type);
        }
        catch (Exception e)
        {
            log.debug("No map entrance data available", e);
        }
        if (entrances == null)
        {
            entrances = new HashMap<>();
        }

        // Human-readable unlock place names from the wiki infobox, used to tell
        // the player where a track unlocks when the map cannot show it.
        try (InputStreamReader reader = new InputStreamReader(
            getClass().getResourceAsStream("/wiki_locations.json"), StandardCharsets.UTF_8))
        {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            wikiLocations = gson.fromJson(reader, type);
        }
        catch (Exception e)
        {
            log.debug("No wiki location data available", e);
        }
        if (wikiLocations == null)
        {
            wikiLocations = new HashMap<>();
        }

        // In-game map areas (the world map's map list entries) with their
        // coordinate bounds, used to tell the player which entry shows a spot.
        try (InputStreamReader reader = new InputStreamReader(
            getClass().getResourceAsStream("/map_areas.json"), StandardCharsets.UTF_8))
        {
            Type type = new TypeToken<List<MapArea>>() {}.getType();
            areaIndex = gson.fromJson(reader, type);
        }
        catch (Exception e)
        {
            log.debug("No map area data available", e);
        }
        if (areaIndex == null)
        {
            areaIndex = Collections.emptyList();
        }
    }

    /**
     * Name of the in-game map area (map list entry) that can display the point,
     * or null when no area covers it. The smallest containing area wins, so a
     * zone nested inside a larger region (Cam Torum in Varlamore Underground)
     * is preferred over its parent.
     */
    String areaNameFor(WorldPoint wp)
    {
        MapArea best = null;
        long bestSize = Long.MAX_VALUE;
        for (MapArea area : areaIndex)
        {
            if (area.bounds == null || area.bounds.size() < 2
                || area.bounds.get(0).size() < 2 || area.bounds.get(1).size() < 2)
            {
                continue;
            }
            long x1 = area.bounds.get(0).get(0).longValue();
            long y1 = area.bounds.get(0).get(1).longValue();
            long x2 = area.bounds.get(1).get(0).longValue();
            long y2 = area.bounds.get(1).get(1).longValue();
            if (wp.getX() < x1 || wp.getX() > x2 || wp.getY() < y1 || wp.getY() > y2)
            {
                continue;
            }
            long size = (x2 - x1) * (y2 - y1);
            if (size < bestSize)
            {
                bestSize = size;
                best = area;
            }
        }
        return best != null ? best.name : null;
    }

    /**
     * The map list entry the player should select to see a marker, or null when
     * no centering is pending (nothing to guide towards).
     */
    String getSuggestedAreaName()
    {
        return pendingCenter ? suggestedArea : null;
    }

    /** "It unlocks at: X." when the wiki knows the place, otherwise "". */
    private String unlockPlace(String trackName)
    {
        String loc = wikiLocations.get(trackName);
        return loc != null ? " It unlocks at: " + loc + "." : "";
    }

    List<MapLocation> getLocations(String trackName)
    {
        return coordsMap.getOrDefault(trackName, Collections.emptyList());
    }

    String getActiveTrack()
    {
        return activeTrack;
    }

    List<ActiveLocation> getActiveLocations()
    {
        return activeLocations;
    }

    List<DisplayArea> getMapAreas()
    {
        return mapAreas;
    }

    BufferedImage getMapIcon()
    {
        return mapIcon;
    }

    void navigateTo(String trackName, Runnable fallback)
    {
        List<ActiveLocation> parsed = new ArrayList<>();
        for (MapLocation loc : getLocations(trackName))
        {
            List<Number> c = loc.center;
            if (c == null || c.size() < 3)
            {
                continue;
            }
            WorldPoint wp = new WorldPoint(c.get(0).intValue(), c.get(1).intValue(), c.get(2).intValue());
            if (parsed.stream().noneMatch(a -> a.point.equals(wp)))
            {
                parsed.add(new ActiveLocation(loc.name != null ? loc.name : trackName, wp, loc.polygon, loc.mapId));
            }
        }

        if (parsed.isEmpty())
        {
            log.debug("navigateTo: no usable coords for '{}', falling back", trackName);
            clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                "Music Cape: no map data for " + trackName + " - opening the wiki."
                    + unlockPlace(trackName), null));
            fallback.run();
            return;
        }

        clientThread.invoke(() ->
        {
            clearActiveState();
            activeTrack = trackName;
            activeLocations = Collections.unmodifiableList(parsed);

            // Build the world map markers. Surface locations are marked directly.
            // Underground locations are projected to the surface spot directly above
            // them (dungeons are mapped exactly 6400 tiles north of the ground they
            // sit under), which is where the player needs to go to find the entrance.
            List<ActiveLocation> markers = new ArrayList<>();
            List<DisplayArea> areas = new ArrayList<>();
            boolean hasSurface = false;
            boolean overheadUsed = false;
            ZoneEntrance entranceZone = null;
            ZoneEntrance accessOnlyZone = null;
            BufferedImage undergroundMarker = null;

            for (ActiveLocation a : parsed)
            {
                if (isSurface(a))
                {
                    hasSurface = true;
                    markers.add(a);
                    addMapPoint(a.point, a.name, mapIcon, null);
                    addArea(areas, a.polygon, 0, false);
                    continue;
                }

                if (a.point.getY() >= UNDERGROUND_Y)
                {
                    WorldPoint overhead = new WorldPoint(a.point.getX(), a.point.getY() - UNDERGROUND_Y, 0);
                    overheadUsed = true;
                    if (undergroundMarker == null)
                    {
                        undergroundMarker = createUndergroundMarker(config.undergroundColor());
                    }
                    String label = a.name + " (underground - find the entrance near here)";
                    markers.add(new ActiveLocation(label, overhead, null, null));
                    // Anchor the badge so the dot (not the image center) sits on the spot.
                    addMapPoint(overhead, label, undergroundMarker,
                        new net.runelite.api.Point(undergroundMarker.getWidth() / 2, 7));
                    addArea(areas, a.polygon, -UNDERGROUND_Y, true);
                    // Also mark the actual spot: the client only draws a map point
                    // when the loaded map area contains it, so this appears once the
                    // user views the dungeon's own map area (e.g. by clicking its
                    // entrance icon) while the projected marker shows on the surface.
                    addMapPoint(a.point, a.name, mapIcon, null);
                    addArea(areas, a.polygon, 0, false);
                    continue;
                }

                // Self-contained zone (Rat Pits, Keldagrim, ...) with no geometric
                // surface correspondence: mark the zone's curated surface entrance.
                ZoneEntrance zone = a.mapId != null ? entrances.get(String.valueOf(a.mapId)) : null;
                if (zone == null)
                {
                    continue;
                }
                if (zone.entrance != null && zone.entrance.size() >= 2)
                {
                    WorldPoint entry = new WorldPoint(zone.entrance.get(0).intValue(), zone.entrance.get(1).intValue(), 0);
                    if (entry.getY() < SURFACE_MAX_Y)
                    {
                        entranceZone = zone;
                        if (undergroundMarker == null)
                        {
                            undergroundMarker = createUndergroundMarker(config.undergroundColor());
                        }
                        String label = "Entrance: " + zone.name + " (" + trackName + " is inside)";
                        markers.add(new ActiveLocation(label, entry, null, null));
                        addMapPoint(entry, label, undergroundMarker,
                            new net.runelite.api.Point(undergroundMarker.getWidth() / 2, 7));
                        // Mark the actual spot as well, drawn when the zone's own map
                        // area is the one loaded in the world map widget.
                        addMapPoint(a.point, a.name, mapIcon, null);
                        addArea(areas, a.polygon, 0, false);
                    }
                }
                else
                {
                    // Teleport-only / instanced zone: nothing to mark, but we can
                    // still tell the player how the zone is accessed.
                    accessOnlyZone = zone;
                }
            }

            mapAreas = Collections.unmodifiableList(areas);

            if (markers.isEmpty())
            {
                // Nothing the world map can show (instanced area with no surface
                // correspondence): open the wiki instead. The hint arrow and in-scene
                // highlight still guide the player once they are near the spot.
                String access = accessOnlyZone != null && accessOnlyZone.note != null
                    ? " Access: " + accessOnlyZone.note + "."
                    : "";
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "Music Cape: " + trackName + " is in an area the world map can't show"
                        + " - opening the wiki." + unlockPlace(trackName) + access
                        + " The spot will be highlighted in-game when you're nearby.", null);
                fallback.run();
                return;
            }

            // The in-game map area (map list entry) that can display the exact
            // unlock spot, when one exists; the entry the player should pick to
            // see the marker at the actual coordinates.
            String exactArea = areaNameFor(nearestTo(playerLocation(), parsed).point);
            suggestedArea = exactArea != null ? exactArea : GIELINOR_SURFACE;
            String mapListHint = exactArea != null && !exactArea.equals(GIELINOR_SURFACE)
                ? " You can also open the map list at the bottom of the world map and select '"
                    + exactArea + "' to see the exact spot."
                : "";

            if (!hasSurface && entranceZone != null)
            {
                String note = entranceZone.note != null ? " (" + entranceZone.note + ")" : "";
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "Music Cape: " + trackName + " unlocks inside " + entranceZone.name
                        + ", UNDERGROUND. The red marker on the world map is the entrance" + note + "."
                        + mapListHint, null);
            }
            else if (!hasSurface && overheadUsed)
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "Music Cape: " + trackName + " unlocks UNDERGROUND. The red 'UNDERGROUND' marker on"
                        + " the world map is the surface directly above it - travel there, then look for"
                        + " the dungeon or cave entrance nearby." + mapListHint, null);
            }

            if (isWorldMapOpen())
            {
                // Map is already open: center it now if the loaded map area can display
                // one of the markers. If it cannot (e.g. the player is underground so the
                // map shows a dungeon area), stay pending - WORLDMAP_LOADMAP fires when
                // the user switches map areas and onMapLoaded() retries. The map list
                // button (and the right entry, once opened) is highlighted meanwhile.
                pendingCenter = !centerOnLoadedMap();
                if (pendingCenter)
                {
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                        "Music Cape: open the map list at the bottom of the world map and select '"
                            + suggestedArea + "' to see the marker.", null);
                }
            }
            else
            {
                // Map is closed: flash an indicator until the user opens the map
                // (WORLDMAP_LOADMAP -> onMapLoaded fires when it loads).
                pendingCenter = true;
                showOpenMapIndicator(trackName);
            }
        });
    }

    /**
     * Converts a scraped wiki polygon into world coordinates for the map area
     * overlay, shifting it by {@code dy} (used to project an underground dungeon
     * outline onto the surface directly above it).
     */
    private void addArea(List<DisplayArea> areas, List<List<Number>> polygon, int dy, boolean underground)
    {
        if (polygon == null || polygon.size() < 3)
        {
            return;
        }
        int[] xs = new int[polygon.size()];
        int[] ys = new int[polygon.size()];
        for (int i = 0; i < polygon.size(); i++)
        {
            List<Number> vertex = polygon.get(i);
            if (vertex == null || vertex.size() < 2)
            {
                return;
            }
            xs[i] = vertex.get(0).intValue();
            ys[i] = vertex.get(1).intValue() + dy;
        }
        areas.add(new DisplayArea(xs, ys, underground));
    }

    /**
     * Runs every game tick (client thread). Keeps the hint arrow pointing at the
     * nearest active location that is inside the loaded scene - the same gating the
     * clue scroll plugin uses, which is what makes it work underground as well.
     */
    void onGameTick(boolean showArrow)
    {
        List<ActiveLocation> locs = activeLocations;
        if (!showArrow || locs.isEmpty())
        {
            clearOwnHintArrow();
            return;
        }

        List<ActiveLocation> inScene = new ArrayList<>();
        for (ActiveLocation a : locs)
        {
            // fromWorld returns null when the point is outside the loaded scene or on
            // a different plane - the same gate the in-scene highlight overlay uses.
            if (LocalPoint.fromWorld(client, a.point) != null)
            {
                inScene.add(a);
            }
        }

        if (inScene.isEmpty())
        {
            clearOwnHintArrow();
            return;
        }

        WorldPoint best = nearestTo(playerLocation(), inScene).point;
        if (!best.equals(hintArrowPoint))
        {
            client.setHintArrow(best);
            hintArrowPoint = best;
        }
    }

    private void clearOwnHintArrow()
    {
        if (hintArrowPoint != null)
        {
            // Only clear the arrow if it is still the one we set, so we never
            // stomp an arrow owned by the game or another plugin.
            if (hintArrowPoint.equals(client.getHintArrowPoint()))
            {
                client.clearHintArrow();
            }
            hintArrowPoint = null;
        }
    }

    private WorldPoint playerLocation()
    {
        Player local = client.getLocalPlayer();
        return local != null ? local.getWorldLocation() : null;
    }

    private static ActiveLocation nearestTo(WorldPoint from, List<ActiveLocation> locs)
    {
        if (from == null || locs.size() == 1)
        {
            return locs.get(0);
        }
        ActiveLocation best = locs.get(0);
        long bestDist = Long.MAX_VALUE;
        for (ActiveLocation a : locs)
        {
            long dx = a.point.getX() - from.getX();
            long dy = a.point.getY() - from.getY();
            long d = dx * dx + dy * dy;
            if (d < bestDist)
            {
                bestDist = d;
                best = a;
            }
        }
        return best;
    }

    void onMapLoaded()
    {
        removeOpenMapInfoBox();
        if (!pendingCenter)
        {
            return;
        }
        // A map area just loaded: the first open, or the user switched areas (surface
        // globe, dungeon entrance icon). Center on the nearest marker this area can
        // display; if it can display none, stay pending for the next area switch.
        clientThread.invoke(() ->
        {
            if (pendingCenter && centerOnLoadedMap())
            {
                pendingCenter = false;
            }
        });
    }

    /**
     * Centers the world map on the nearest active marker that the currently loaded
     * map area can display. Returns false (and does not move the map) if it can
     * display none of them.
     */
    private boolean centerOnLoadedMap()
    {
        List<ActiveLocation> displayable = new ArrayList<>();
        for (WorldMapPoint point : activeMapPoints)
        {
            if (displayableOnLoadedMap(point.getWorldPoint()))
            {
                displayable.add(new ActiveLocation(point.getName(), point.getWorldPoint(), null, null));
            }
        }
        if (displayable.isEmpty())
        {
            return false;
        }
        centerMap(nearestTo(playerLocation(), displayable).point);
        return true;
    }

    /** Whether the map area currently loaded in the world map widget can draw this point. */
    private boolean displayableOnLoadedMap(WorldPoint wp)
    {
        WorldMap wm = client.getWorldMap();
        return wm != null && wm.getWorldMapData() != null
            && wm.getWorldMapData().surfaceContainsPosition(wp.getX(), wp.getY());
    }

    private void centerMap(WorldPoint wp)
    {
        WorldMap wm = client.getWorldMap();
        if (wm != null)
        {
            wm.setWorldMapPositionTarget(wp);
            log.debug("centerMap: setWorldMapPositionTarget {}", wp);
        }
        else
        {
            log.warn("centerMap: world map is null");
        }
    }

    private boolean isWorldMapOpen()
    {
        Widget mapView = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
        return mapView != null && !mapView.isHidden();
    }

    boolean isSurface(ActiveLocation a)
    {
        // The Gielinor Surface map area ends at y=4160; everything above that is a
        // self-contained zone or dungeon regardless of mapId. Never ask the world
        // map widget: WorldMapData reflects whichever map area is currently loaded,
        // so with the player underground it rejects ordinary surface coordinates
        // and every track fell back to the wiki.
        return a.point.getY() < SURFACE_MAX_Y;
    }

    private void addMapPoint(WorldPoint wp, String name, BufferedImage image, net.runelite.api.Point imagePoint)
    {
        WorldMapPoint point = WorldMapPoint.builder()
            .worldPoint(wp)
            .image(image)
            .imagePoint(imagePoint)
            .snapToEdge(true)
            .jumpOnClick(true)
            .name(name)
            .tooltip("Music track location")
            .build();

        worldMapPointManager.add(point);
        activeMapPoints.add(point);
    }

    private void removeMapPoints()
    {
        for (WorldMapPoint point : activeMapPoints)
        {
            worldMapPointManager.remove(point);
        }
        activeMapPoints.clear();
    }

    // ------------------------------------------------------------------
    // "Open the world map" indicator: a flashing InfoBox shown while the
    // map is closed, so the user knows there is a marked location waiting.
    // ------------------------------------------------------------------

    private void showOpenMapIndicator(String name)
    {
        removeOpenMapInfoBox();

        flashOn = true;
        openMapInfoBox = new InfoBox(mapIcon, plugin)
        {
            @Override
            public String getText()
            {
                return "Map";
            }

            @Override
            public Color getTextColor()
            {
                return Color.YELLOW;
            }

            @Override
            public boolean render()
            {
                return flashOn;
            }
        };
        openMapInfoBox.setTooltip("Open the world map to jump to " + name);
        infoBoxManager.addInfoBox(openMapInfoBox);
        startFlashing();

        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
            "Music Cape: open the world map (globe icon by the minimap) to jump to " + name + ".", null);
    }

    private void removeOpenMapInfoBox()
    {
        stopFlashing();
        if (openMapInfoBox != null)
        {
            infoBoxManager.removeInfoBox(openMapInfoBox);
            openMapInfoBox = null;
        }
    }

    private void startFlashing()
    {
        stopFlashing();
        flashCount = 0;
        flashTimer = new Timer(400, (ActionEvent e) ->
        {
            flashOn = !flashOn;
            flashCount++;
            if (flashCount >= 30)
            {
                // After ~12s stop flashing but leave the box visible.
                flashOn = true;
                stopFlashing();
            }
        });
        flashTimer.setRepeats(true);
        flashTimer.start();
    }

    private void stopFlashing()
    {
        if (flashTimer != null)
        {
            flashTimer.stop();
            flashTimer = null;
        }
    }

    /** Must run on the client thread. */
    private void clearActiveState()
    {
        removeOpenMapInfoBox();
        pendingCenter = false;
        suggestedArea = null;
        activeTrack = null;
        activeLocations = Collections.emptyList();
        mapAreas = Collections.emptyList();
        removeMapPoints();
        clearOwnHintArrow();
    }

    void clear()
    {
        clientThread.invoke(this::clearActiveState);
    }

    private static BufferedImage createMapIcon(Color color)
    {
        BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.fillOval(1, 1, 12, 12);
        g.setColor(Color.BLACK);
        g.drawOval(1, 1, 12, 12);
        g.dispose();
        return img;
    }

    /**
     * A marker for the surface spot above an underground track: a colored dot
     * with an "UNDERGROUND" label baked into the image so it is unmistakable
     * on the world map.
     */
    private static BufferedImage createUndergroundMarker(Color color)
    {
        String text = "UNDERGROUND";
        java.awt.Font font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 10);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        int textWidth = pg.getFontMetrics().stringWidth(text);
        int textHeight = pg.getFontMetrics().getHeight();
        pg.dispose();

        int width = Math.max(14, textWidth + 4);
        int height = 14 + textHeight;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int dotX = width / 2 - 7;
        g.setColor(color);
        g.fillOval(dotX + 1, 1, 12, 12);
        g.setColor(Color.BLACK);
        g.drawOval(dotX + 1, 1, 12, 12);

        g.setFont(font);
        int textX = (width - textWidth) / 2;
        int textY = 14 + g.getFontMetrics().getAscent();
        // Dark outline behind the label so it stays readable on any map terrain.
        g.setColor(Color.BLACK);
        g.drawString(text, textX + 1, textY + 1);
        g.setColor(color);
        g.drawString(text, textX, textY);

        g.dispose();
        return img;
    }

    static class ActiveLocation
    {
        final String name;
        final WorldPoint point;
        final List<List<Number>> polygon;
        final Integer mapId;

        ActiveLocation(String name, WorldPoint point, List<List<Number>> polygon, Integer mapId)
        {
            this.name = name;
            this.point = point;
            this.polygon = polygon;
            this.mapId = mapId;
        }
    }

    static class MapArea
    {
        int id;
        String name;
        List<List<Number>> bounds;
    }

    static class MapLocation
    {
        String name;
        List<Number> center;
        List<List<Number>> polygon;
        Integer mapId;
    }

    static class ZoneEntrance
    {
        String name;
        List<Number> entrance;
        String note;
    }

    /**
     * A polygon in world coordinates, ready to be drawn on the world map
     * (underground outlines are already projected onto the surface).
     */
    static class DisplayArea
    {
        final int[] xs;
        final int[] ys;
        final boolean underground;

        DisplayArea(int[] xs, int[] ys, boolean underground)
        {
            this.xs = xs;
            this.ys = ys;
            this.underground = underground;
        }
    }
}
