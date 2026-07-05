package com.robisa693.musiccapehelper;

import com.robisa693.musiccapehelper.MusicCapeHelperConfig.ClickMode;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class MusicCapeHelperPanel extends PluginPanel
{
    private static final Color COLOR_UNLOCKED = new Color(90, 220, 90);
    private static final Color COLOR_LOCKED = new Color(220, 90, 90);
    private static final Color COLOR_HINT = new Color(180, 180, 180);
    private static final Color COLOR_HEADER = Color.WHITE;
    private static final Color COLOR_HOVER = new Color(50, 50, 60);
    private static final Color COLOR_TOGGLE_ACTIVE = new Color(70, 70, 90);
    private static final Color COLOR_TOGGLE_INACTIVE = new Color(40, 40, 50);
    private static final Color COLOR_COORDS = new Color(100, 160, 200);

    private final MusicCapeHelperPlugin plugin;
    private final MusicCapeHelperConfig config;
    private final ConfigManager configManager;
    private final OkHttpClient okHttpClient;
    private final Client client;
    private final ClientThread clientThread;
    private final MapNavigator mapNavigator;

    private ClickMode clickMode;

    private JLabel summaryLabel;
    private JTextField searchField;
    private JCheckBox missingOnlyCheck;
    private JLabel wikiToggle;
    private JLabel mapToggle;
    private JPanel trackListPanel;
    private String filterText = "";

    MusicCapeHelperPanel(MusicCapeHelperPlugin plugin, MusicCapeHelperConfig config, ConfigManager configManager, OkHttpClient okHttpClient, Client client, ClientThread clientThread, MapNavigator mapNavigator)
    {
        this.plugin = plugin;
        this.config = config;
        this.configManager = configManager;
        this.okHttpClient = okHttpClient;
        this.client = client;
        this.clientThread = clientThread;
        this.mapNavigator = mapNavigator;
        this.clickMode = config.clickMode();

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        buildUI();
    }

    private void buildUI()
    {
        removeAll();

        summaryLabel = new JLabel("Loading...");
        summaryLabel.setForeground(COLOR_HEADER);
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 14f));
        summaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(summaryLabel);
        add(Box.createRigidArea(new Dimension(0, 8)));

        searchField = new JTextField();
        searchField.setToolTipText("Search tracks by name");
        searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        searchField.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchField.getDocument().addDocumentListener(new DocumentListener()
        {
            public void changedUpdate(DocumentEvent e) { onFilterChanged(); }
            public void insertUpdate(DocumentEvent e) { onFilterChanged(); }
            public void removeUpdate(DocumentEvent e) { onFilterChanged(); }
        });
        add(searchField);
        add(Box.createRigidArea(new Dimension(0, 4)));

        missingOnlyCheck = new JCheckBox("Missing only");
        missingOnlyCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        missingOnlyCheck.addActionListener(e ->
        {
            boolean selected = missingOnlyCheck.isSelected();
            configManager.setConfiguration("musiccapehelper", "showMissingOnly", selected);
            rebuild();
        });
        missingOnlyCheck.setSelected(config.showMissingOnly());
        add(missingOnlyCheck);
        add(Box.createRigidArea(new Dimension(0, 8)));

        add(createToggleBar());
        add(Box.createRigidArea(new Dimension(0, 4)));

        trackListPanel = new JPanel()
        {
            @Override
            public Dimension getMaximumSize()
            {
                Dimension max = super.getMaximumSize();
                max.width = Short.MAX_VALUE;
                return max;
            }
        };
        trackListPanel.setLayout(new BoxLayout(trackListPanel, BoxLayout.Y_AXIS));
        trackListPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        add(trackListPanel);

        revalidate();
        rebuild();
    }

    private JPanel createToggleBar()
    {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        wikiToggle = createToggleLabel(" Wiki ");
        mapToggle = createToggleLabel(" Map ");

        updateToggleColors();

        wikiToggle.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                setClickMode(ClickMode.WIKI);
            }
        });

        mapToggle.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                setClickMode(ClickMode.MAP);
            }
        });

        JLabel clearLabel = createToggleLabel(" ✕ ");
        clearLabel.setBackground(COLOR_TOGGLE_INACTIVE);
        clearLabel.setForeground(Color.GRAY);
        clearLabel.setToolTipText("Clear the marked track (map marker, highlights and hint arrow)");
        clearLabel.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                mapNavigator.clear();
            }
        });

        bar.add(wikiToggle);
        bar.add(Box.createRigidArea(new Dimension(4, 0)));
        bar.add(mapToggle);
        bar.add(Box.createHorizontalGlue());
        bar.add(clearLabel);

        return bar;
    }

    private static JLabel createToggleLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setOpaque(true);
        label.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return label;
    }

    private void updateToggleColors()
    {
        wikiToggle.setBackground(clickMode == ClickMode.WIKI ? COLOR_TOGGLE_ACTIVE : COLOR_TOGGLE_INACTIVE);
        wikiToggle.setForeground(clickMode == ClickMode.WIKI ? Color.WHITE : Color.GRAY);
        mapToggle.setBackground(clickMode == ClickMode.MAP ? COLOR_TOGGLE_ACTIVE : COLOR_TOGGLE_INACTIVE);
        mapToggle.setForeground(clickMode == ClickMode.MAP ? Color.WHITE : Color.GRAY);
    }

    private void setClickMode(ClickMode mode)
    {
        if (clickMode == mode)
        {
            return;
        }
        clickMode = mode;
        configManager.setConfiguration("musiccapehelper", "clickMode", mode);
        updateToggleColors();
        mapNavigator.clear();
        rebuild();
    }

    private void onFilterChanged()
    {
        filterText = searchField.getText().trim().toLowerCase();
        rebuild();
    }

    void rebuild()
    {
        // Gather game state on the client thread, then apply all Swing
        // mutations on the EDT - mixing the two freezes the client.
        clientThread.invokeLater(() ->
        {
            List<TrackData> tracks = plugin.getVisibleTracks();
            Map<Integer, Boolean> unlockedState = new java.util.HashMap<>(plugin.getUnlockedState());
            long total = plugin.getTotalTracks();
            long unlocked = plugin.getUnlockedCount();
            SwingUtilities.invokeLater(() -> rebuildUI(tracks, unlockedState, total, unlocked));
        });
    }

    private void rebuildUI(List<TrackData> tracks, Map<Integer, Boolean> unlockedState, long total, long unlocked)
    {
        clickMode = config.clickMode();
        updateToggleColors();

        summaryLabel.setText("Tracks: " + unlocked + " / " + total + " unlocked");

        boolean hasData = !unlockedState.isEmpty();

        List<TrackData> filtered = tracks.stream()
            .filter(t -> filterText.isEmpty() || t.displayName.toLowerCase().contains(filterText))
            .collect(Collectors.toList());

        trackListPanel.removeAll();

        if (!hasData)
        {
            JLabel empty = new JLabel("Open the Music tab\nto populate this list.");
            empty.setForeground(COLOR_HINT);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            trackListPanel.add(empty);
        }
        else if (filtered.isEmpty())
        {
            JLabel empty = new JLabel("No tracks match\nyour filter.");
            empty.setForeground(COLOR_HINT);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            trackListPanel.add(empty);
        }
        else
        {
            for (TrackData track : filtered)
            {
                trackListPanel.add(createTrackRow(track, unlockedState));
                trackListPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            }
        }

        trackListPanel.revalidate();
        trackListPanel.repaint();
        revalidate();
    }

    private JPanel createTrackRow(TrackData track, Map<Integer, Boolean> unlockedState)
    {
        JPanel row = new JPanel()
        {
            @Override
            public Dimension getMaximumSize()
            {
                Dimension max = super.getMaximumSize();
                max.width = Short.MAX_VALUE;
                return max;
            }
        };
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        row.setOpaque(true);
        row.setBackground(null);

        Boolean unlocked = unlockedState.get(track.dbRow);
        boolean isUnlocked = unlocked != null && unlocked;

        JLabel nameLabel = new JLabel(track.displayName);
        nameLabel.setForeground(isUnlocked ? COLOR_UNLOCKED : COLOR_LOCKED);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(nameLabel);

        if (!isUnlocked && !track.unlockHint.isEmpty())
        {
            JLabel hintLabel = new JLabel(track.unlockHint);
            hintLabel.setForeground(COLOR_HINT);
            hintLabel.setFont(hintLabel.getFont().deriveFont(11f));
            hintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(hintLabel);
        }

        JLabel areaLabel = new JLabel(AreaResolver.getAreaName(track.areaId));
        areaLabel.setForeground(new Color(120, 120, 120));
        areaLabel.setFont(areaLabel.getFont().deriveFont(10f));
        areaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(areaLabel);

        if (clickMode == ClickMode.MAP)
        {
            String coordText = coordString(track.displayName);
            JLabel coordLabel = new JLabel(coordText);
            coordLabel.setForeground(COLOR_COORDS);
            coordLabel.setFont(coordLabel.getFont().deriveFont(10f));
            coordLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(coordLabel);
        }

        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        row.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                if (clickMode == ClickMode.MAP)
                {
                    mapNavigator.navigateTo(track.displayName, track.unlockHint, () -> resolveAndBrowse(track.displayName));
                }
                else
                {
                    resolveAndBrowse(track.displayName);
                }
            }

            @Override
            public void mouseEntered(MouseEvent e)
            {
                row.setBackground(COLOR_HOVER);
                row.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                row.setBackground(null);
                row.repaint();
            }
        });

        return row;
    }

    private String coordString(String trackName)
    {
        List<MapNavigator.MapLocation> locs = mapNavigator.getLocations(trackName);
        List<Number> c = locs.isEmpty() ? null : locs.get(0).center;
        if (c == null || c.size() < 3)
        {
            return "[?]";
        }
        String coords = "[" + c.get(0).intValue() + ", " + c.get(1).intValue() + ", " + c.get(2).intValue() + "]";
        return locs.size() > 1 ? coords + " +" + (locs.size() - 1) + " more" : coords;
    }

    private void resolveAndBrowse(String trackName)
    {
        String plainUrl = wikiUrlForTrack(trackName);
        String apiEncoded = URLEncoder.encode(trackName + " (music track)", StandardCharsets.UTF_8);
        String apiUrl = "https://oldschool.runescape.wiki/api.php?action=query&titles="
            + apiEncoded + "&format=json&formatversion=2&redirects=1";

        Request request = new Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "MusicCapeHelper/1.0")
            .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                browseUrl(plainUrl);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                // Only use the "<name> (music track)" page when the API
                // positively confirms it exists; on any error, rate-limit
                // page or unexpected payload, the plain title is the safe
                // choice (most track pages live there anyway).
                boolean suffixedExists = false;
                try (ResponseBody body = response.body())
                {
                    if (response.isSuccessful())
                    {
                        com.google.gson.JsonObject root = new com.google.gson.JsonParser()
                            .parse(body.string()).getAsJsonObject();
                        com.google.gson.JsonArray pages = root
                            .getAsJsonObject("query").getAsJsonArray("pages");
                        if (pages != null && pages.size() > 0)
                        {
                            com.google.gson.JsonObject pageObj = pages.get(0).getAsJsonObject();
                            suffixedExists = !pageObj.has("missing")
                                && !pageObj.has("invalid");
                        }
                    }
                }
                catch (Exception ex)
                {
                    suffixedExists = false;
                }
                browseUrl(suffixedExists ? plainUrl + "_(music_track)" : plainUrl);
            }
        });
    }

    private static void browseUrl(String url)
    {
        SwingUtilities.invokeLater(() -> LinkBrowser.browse(url));
    }

    private static String wikiUrlForTrack(String name)
    {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8)
            .replace("+", "_");
        return "https://oldschool.runescape.wiki/w/" + encoded;
    }
}
