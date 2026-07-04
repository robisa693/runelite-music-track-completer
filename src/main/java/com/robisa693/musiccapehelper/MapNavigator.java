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
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

class MapNavigator
{
    private static final Logger log = LoggerFactory.getLogger(MapNavigator.class);
    private static final Type MAP_TYPE = new TypeToken<Map<String, List<MapLocation>>>() {}.getType();

    private final Client client;
    private final ClientThread clientThread;
    private final WorldMapPointManager worldMapPointManager;
    private final Gson gson;

    private Map<String, List<MapLocation>> coordsMap = new HashMap<>();
    private WorldMapPoint activeMapPoint;
    private WorldPoint pendingTarget;
    private BufferedImage mapIcon;
    private Timer blinkTimer;

    MapNavigator(Client client, ClientThread clientThread, WorldMapPointManager worldMapPointManager, Gson gson)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.worldMapPointManager = worldMapPointManager;
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
            log.info("navigateTo: no coords for '{}', falling back", trackName);
            fallback.run();
            return;
        }

        MapLocation first = locs.get(0);
        List<Number> c = first.center;
        if (c == null || c.size() < 3)
        {
            log.info("navigateTo: no center for '{}', falling back", trackName);
            fallback.run();
            return;
        }

        WorldPoint wp = new WorldPoint(c.get(0).intValue(), c.get(1).intValue(), c.get(2).intValue());
        log.debug("navigateTo: {} -> {}", trackName, wp);

        clientThread.invoke(() ->
        {
            try
            {
                client.setHintArrow(wp);
            }
            catch (Exception e)
            {
                log.warn("navigateTo: setHintArrow failed", e);
            }

            addMapPoint(wp, first.name);
            startBlinking();

            if (isWorldMapOpen())
            {
                // Map is already open: center it now. WORLDMAP_LOADMAP will not fire
                // again for a region that is already loaded, so we cannot rely on onMapLoaded().
                pendingTarget = null;
                centerMap(wp);
            }
            else
            {
                // Map is closed: remember the target and center it once the user opens the
                // map (WORLDMAP_LOADMAP -> onMapLoaded fires when the map first loads).
                pendingTarget = wp;
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "Music Cape: track marked. Open the world map (F9) to jump to it.", null);
            }
        });
    }

    void onMapLoaded()
    {
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

    private int blinkCount;
    private boolean blinkVisible;

    private void startBlinking()
    {
        stopBlinking();
        blinkCount = 0;
        blinkVisible = true;
        blinkTimer = new Timer(500, (ActionEvent e) ->
        {
            blinkCount++;
            if (blinkCount >= 12 || activeMapPoint == null)
            {
                stopBlinking();
                return;
            }

            blinkVisible = !blinkVisible;
            clientThread.invoke(() ->
            {
                if (blinkVisible)
                {
                    worldMapPointManager.add(activeMapPoint);
                }
                else
                {
                    worldMapPointManager.remove(activeMapPoint);
                }
            });
        });
        blinkTimer.setRepeats(true);
        blinkTimer.setInitialDelay(500);
        blinkTimer.start();
    }

    private void stopBlinking()
    {
        if (blinkTimer != null)
        {
            blinkTimer.stop();
            blinkTimer = null;
        }
        blinkVisible = true;
        if (activeMapPoint != null)
        {
            clientThread.invoke(() -> worldMapPointManager.add(activeMapPoint));
        }
    }

    void clear()
    {
        stopBlinking();
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
