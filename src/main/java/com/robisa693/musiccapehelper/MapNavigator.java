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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
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
    private WorldMapPoint activeMapPoint;
    private WorldPoint pendingTarget;
    private BufferedImage mapIcon;

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

    void navigateTo(String trackName, Runnable fallback)
    {
        List<MapLocation> locs = getLocations(trackName);
        if (locs.isEmpty())
        {
            log.debug("navigateTo: no coords for '{}', falling back", trackName);
            fallback.run();
            return;
        }

        MapLocation first = locs.get(0);
        List<Number> c = first.center;
        if (c == null || c.size() < 3)
        {
            log.debug("navigateTo: no center for '{}', falling back", trackName);
            fallback.run();
            return;
        }

        WorldPoint wp = new WorldPoint(c.get(0).intValue(), c.get(1).intValue(), c.get(2).intValue());
        log.debug("navigateTo: {} -> {}", trackName, wp);

        clientThread.invoke(() ->
        {
            if (!isSurface(wp))
            {
                // The RuneLite world map only renders the surface: WorldMapOverlay drops any
                // point where WorldMapData.surfaceContainsPosition() is false (underground /
                // instanced regions live on separate map areas). No plugin - clue helper
                // included - can mark those, so send the user to the wiki instead.
                log.debug("navigateTo: {} is not on the surface map ({}), falling back to wiki", trackName, wp);
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "Music Cape: " + trackName + " is underground and can't be shown on the world map - opening the wiki.", null);
                fallback.run();
                return;
            }

            try
            {
                client.setHintArrow(wp);
            }
            catch (Exception e)
            {
                log.warn("navigateTo: setHintArrow failed", e);
            }

            addMapPoint(wp, first.name);

            if (isWorldMapOpen())
            {
                // Map is already open: center it now. WORLDMAP_LOADMAP will not fire
                // again for a region that is already loaded, so we cannot rely on onMapLoaded().
                pendingTarget = null;
                removeOpenMapInfoBox();
                centerMap(wp);
            }
            else
            {
                // Map is closed: remember the target and flash an indicator until the user
                // opens the map (WORLDMAP_LOADMAP -> onMapLoaded fires when it first loads).
                pendingTarget = wp;
                showOpenMapIndicator(first.name != null ? first.name : trackName);
            }
        });
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

    private boolean isSurface(WorldPoint wp)
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
        removeMapPoint();

        WorldMapPoint point = WorldMapPoint.builder()
            .worldPoint(wp)
            .image(mapIcon)
            .snapToEdge(true)
            .jumpOnClick(true)
            .name(name)
            .tooltip("Music track location")
            .build();

        worldMapPointManager.add(point);
        activeMapPoint = point;
    }

    void removeMapPoint()
    {
        if (activeMapPoint != null)
        {
            worldMapPointManager.remove(activeMapPoint);
            activeMapPoint = null;
        }
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

    void clear()
    {
        removeOpenMapInfoBox();
        pendingTarget = null;
        clientThread.invoke(() ->
        {
            removeMapPoint();
            client.clearHintArrow();
        });
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

    static class MapLocation
    {
        String name;
        List<Number> center;
        List<List<Number>> polygon;
    }
}
