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

    // World map surface coordinates stop below this y; anything at or above it is an
    // underground / instanced region that the in-game world map cannot display.
    private static final int UNDERGROUND_Y = 6400;

    private final Plugin plugin;
    private final Client client;
    private final ClientThread clientThread;
    private final WorldMapPointManager worldMapPointManager;
    private final InfoBoxManager infoBoxManager;
    private final Gson gson;

    private Map<String, List<MapLocation>> coordsMap = new HashMap<>();
    private final List<WorldMapPoint> activeMapPoints = new ArrayList<>();
    private WorldPoint pendingTarget;
    private final BufferedImage mapIcon;

    // Active navigation target: written on the client thread, read by overlays
    // (which also render on the client thread) and the panel (EDT) - keep the
    // list reference immutable and volatile.
    private volatile String activeTrack;
    private volatile List<ActiveLocation> activeLocations = Collections.emptyList();
    private WorldPoint hintArrowPoint;

    private InfoBox openMapInfoBox;
    private Timer flashTimer;
    private int flashCount;
    private volatile boolean flashOn;

    MapNavigator(Plugin plugin, Client client, ClientThread clientThread, WorldMapPointManager worldMapPointManager, InfoBoxManager infoBoxManager, Gson gson)
    {
        this.plugin = plugin;
        this.client = client;
        this.clientThread = clientThread;
        this.worldMapPointManager = worldMapPointManager;
        this.infoBoxManager = infoBoxManager;
        this.gson = gson;
        this.mapIcon = createMapIcon();
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
    }

    List<MapLocation> getLocations(String trackName)
    {
        return coordsMap.getOrDefault(trackName, Collections.emptyList());
    }

    boolean hasLocation(String trackName)
    {
        List<MapLocation> locs = coordsMap.get(trackName);
        return locs != null && !locs.isEmpty();
    }

    String getActiveTrack()
    {
        return activeTrack;
    }

    List<ActiveLocation> getActiveLocations()
    {
        return activeLocations;
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
                parsed.add(new ActiveLocation(loc.name != null ? loc.name : trackName, wp, loc.polygon));
            }
        }

        if (parsed.isEmpty())
        {
            log.debug("navigateTo: no usable coords for '{}', falling back", trackName);
            fallback.run();
            return;
        }

        clientThread.invoke(() ->
        {
            clearActiveState();
            activeTrack = trackName;
            activeLocations = Collections.unmodifiableList(parsed);

            List<ActiveLocation> surface = new ArrayList<>();
            for (ActiveLocation a : parsed)
            {
                if (isSurface(a.point))
                {
                    surface.add(a);
                }
            }

            for (ActiveLocation a : surface)
            {
                addMapPoint(a.point, a.name);
            }

            if (surface.isEmpty())
            {
                // Every location is underground / instanced: the world map cannot render
                // those regions (WorldMapOverlay drops any point not on the surface), so
                // open the wiki to show where to travel. The hint arrow and the in-scene
                // highlight still guide the player once they are near the spot.
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "Music Cape: " + trackName + " is underground and can't be shown on the world map"
                        + " - opening the wiki. The spot will be highlighted in-game when you're nearby.", null);
                fallback.run();
                return;
            }

            WorldPoint primary = nearestTo(playerLocation(), surface).point;

            if (isWorldMapOpen())
            {
                // Map is already open: center it now. WORLDMAP_LOADMAP will not fire
                // again for a region that is already loaded, so we cannot rely on onMapLoaded().
                pendingTarget = null;
                centerMap(primary);
            }
            else
            {
                // Map is closed: remember the target and flash an indicator until the user
                // opens the map (WORLDMAP_LOADMAP -> onMapLoaded fires when it first loads).
                pendingTarget = primary;
                showOpenMapIndicator(trackName);
            }
        });
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
        if (pendingTarget == null)
        {
            return;
        }
        WorldPoint target = pendingTarget;
        pendingTarget = null;
        log.debug("onMapLoaded: centering map on {}", target);
        clientThread.invoke(() -> centerMap(target));
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

    boolean isSurface(WorldPoint wp)
    {
        WorldMap wm = client.getWorldMap();
        if (wm != null && wm.getWorldMapData() != null)
        {
            // Exact gate used by WorldMapOverlay when deciding whether a point can be drawn.
            return wm.getWorldMapData().surfaceContainsPosition(wp.getX(), wp.getY());
        }
        // World map never opened this session, so its data is not loaded yet: approximate
        // with the underground coordinate band (surface coordinates stay below y=6400).
        return wp.getY() < UNDERGROUND_Y;
    }

    private void addMapPoint(WorldPoint wp, String name)
    {
        WorldMapPoint point = WorldMapPoint.builder()
            .worldPoint(wp)
            .image(mapIcon)
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
                return "F9";
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
        openMapInfoBox.setTooltip("Open the world map (F9) to jump to " + name);
        infoBoxManager.addInfoBox(openMapInfoBox);
        startFlashing();

        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
            "Music Cape: press F9 to open the world map and jump to " + name + ".", null);
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
        pendingTarget = null;
        activeTrack = null;
        activeLocations = Collections.emptyList();
        removeMapPoints();
        clearOwnHintArrow();
    }

    void clear()
    {
        clientThread.invoke(this::clearActiveState);
    }

    private static BufferedImage createMapIcon()
    {
        BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(255, 200, 0));
        g.fillOval(1, 1, 12, 12);
        g.setColor(Color.BLACK);
        g.drawOval(1, 1, 12, 12);
        g.dispose();
        return img;
    }

    static class ActiveLocation
    {
        final String name;
        final WorldPoint point;
        final List<List<Number>> polygon;

        ActiveLocation(String name, WorldPoint point, List<List<Number>> polygon)
        {
            this.name = name;
            this.point = point;
            this.polygon = polygon;
        }
    }

    static class MapLocation
    {
        String name;
        List<Number> center;
        List<List<Number>> polygon;
    }
}
