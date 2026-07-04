package com.robisa693.musiccapehelper;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.DBTableID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(
    name = "Music Cape Helper",
    description = "Helps you complete the music log and unlock the music cape. Shows all music tracks, their areas and unlock hints, and guides you to each track's unlock spot.",
    tags = {"music", "cape", "track", "tracker", "completionist", "log", "unlock", "hint", "area", "collection", "progress", "wiki"}
)
public class MusicCapeHelperPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(MusicCapeHelperPlugin.class);
    private static final String CONFIG_GROUP = "musiccapehelper";
    private static final String KEY_UNLOCKED_MAP = "unlockedMap";
    private static final Type MAP_TYPE = new TypeToken<Map<Integer, Boolean>>() {}.getType();

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private Gson gson;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private WorldMapPointManager worldMapPointManager;

    @Inject
    private InfoBoxManager infoBoxManager;

    @Inject
    private OverlayManager overlayManager;

    private MusicCapeHelperConfig config;
    private MusicCapeHelperPanel panel;
    private MapNavigator mapNavigator;
    private MusicSceneOverlay sceneOverlay;
    private MusicMapAreaOverlay mapAreaOverlay;
    private NavigationButton navButton;
    private Map<Integer, Boolean> unlockedState = new HashMap<>();

    private List<TrackData> allTracks = Collections.emptyList();

    @Override
    protected void startUp()
    {
        config = getConfig(configManager);
        loadUnlockedState();
        mapNavigator = new MapNavigator(this, client, clientThread, worldMapPointManager, infoBoxManager, config, gson);
        sceneOverlay = new MusicSceneOverlay(client, config, mapNavigator);
        mapAreaOverlay = new MusicMapAreaOverlay(client, config, mapNavigator);
        overlayManager.add(sceneOverlay);
        overlayManager.add(mapAreaOverlay);
        panel = new MusicCapeHelperPanel(this, config, configManager, okHttpClient, client, clientThread, mapNavigator);
        navButton = NavigationButton.builder()
            .tooltip("Music Cape Helper")
            .icon(ImageUtil.loadImageResource(getClass(), "icon.png"))
            .priority(5)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        clientThread.invoke(this::loadTrackData);
    }

    @Override
    protected void shutDown()
    {
        if (sceneOverlay != null)
        {
            overlayManager.remove(sceneOverlay);
        }
        if (mapAreaOverlay != null)
        {
            overlayManager.remove(mapAreaOverlay);
        }
        if (mapNavigator != null)
        {
            mapNavigator.clear();
        }
        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (mapNavigator != null)
        {
            mapNavigator.onGameTick(config.showHintArrow());
        }
    }

    @Provides
    MusicCapeHelperConfig getConfig(ConfigManager configManager)
    {
        return configManager.getConfig(MusicCapeHelperConfig.class);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            clientThread.invoke(this::loadTrackData);
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (event.getGroup().equals(CONFIG_GROUP))
        {
            panel.rebuild();
        }
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent ev)
    {
        if (!"musicTrackFilter".equals(ev.getEventName()))
        {
            return;
        }

        int[] stack = client.getIntStack();
        int stackSize = client.getIntStackSize();

        int unlocked = stack[stackSize - 1];
        int dbrow = stack[stackSize - 2];

        boolean isUnlocked = unlocked != 0;
        Boolean prev = unlockedState.put(dbrow, isUnlocked);
        if (prev == null || prev != isUnlocked)
        {
            saveUnlockedState();
            panel.rebuild();
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        if (event.getScriptId() == ScriptID.WORLDMAP_LOADMAP)
        {
            mapNavigator.onMapLoaded();
        }
    }

    private void loadTrackData()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        List<Integer> rows = client.getDBTableRows(DBTableID.Music.ID);
        if (rows == null || rows.isEmpty())
        {
            return;
        }

        List<TrackData> tracks = new ArrayList<>();
        for (int dbrow : rows)
        {
            String displayName = readDBString(dbrow, DBTableID.Music.COL_DISPLAYNAME);
            if (displayName == null)
            {
                continue;
            }

            String unlockHint = readDBString(dbrow, DBTableID.Music.COL_UNLOCKHINT);
            int areaId = readDBInt(dbrow, DBTableID.Music.COL_AREA);
            boolean hidden = readDBBoolean(dbrow, DBTableID.Music.COL_HIDDEN);

            tracks.add(new TrackData(dbrow, displayName, unlockHint != null ? unlockHint : "", areaId, hidden));
        }

        tracks.sort(Comparator.comparing(t -> t.displayName));

        allTracks = tracks;
        panel.rebuild();
    }

    private String readDBString(int dbrow, int col)
    {
        Object[] val = client.getDBTableField(dbrow, col, 0);
        if (val != null && val.length > 0 && val[0] instanceof String)
        {
            return (String) val[0];
        }
        return null;
    }

    private int readDBInt(int dbrow, int col)
    {
        Object[] val = client.getDBTableField(dbrow, col, 0);
        if (val != null && val.length > 0 && val[0] instanceof Integer)
        {
            return (Integer) val[0];
        }
        return 0;
    }

    private boolean readDBBoolean(int dbrow, int col)
    {
        Object[] val = client.getDBTableField(dbrow, col, 0);
        if (val != null && val.length > 0 && val[0] instanceof Integer)
        {
            return (Integer) val[0] != 0;
        }
        return false;
    }

    public List<TrackData> getVisibleTracks()
    {
        return allTracks.stream()
            .filter(t -> !t.hidden)
            .filter(t -> {
                if (config.showMissingOnly())
                {
                    Boolean unlocked = unlockedState.get(t.dbRow);
                    return unlocked == null || !unlocked;
                }
                return true;
            })
            .collect(Collectors.toList());
    }

    public Map<Integer, Boolean> getUnlockedState()
    {
        return unlockedState;
    }

    public long getTotalTracks()
    {
        return allTracks.stream().filter(t -> !t.hidden).count();
    }

    public long getUnlockedCount()
    {
        return allTracks.stream().filter(t -> !t.hidden)
            .filter(t -> {
                Boolean u = unlockedState.get(t.dbRow);
                return u != null && u;
            })
            .count();
    }

    private void loadUnlockedState()
    {
        String json = configManager.getConfiguration(CONFIG_GROUP, KEY_UNLOCKED_MAP);
        if (!Strings.isNullOrEmpty(json))
        {
            try
            {
                Map<Integer, Boolean> loaded = gson.fromJson(json, MAP_TYPE);
                if (loaded != null)
                {
                    unlockedState = loaded;
                }
            }
            catch (Exception e)
            {
                log.warn("Failed to load unlocked state", e);
            }
        }
    }

    private void saveUnlockedState()
    {
        String json = gson.toJson(unlockedState);
        configManager.setConfiguration(CONFIG_GROUP, KEY_UNLOCKED_MAP, json);
    }
}
