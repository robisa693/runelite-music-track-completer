package com.robisa693.musiccapehelper;

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

    private final MusicCapeHelperPlugin plugin;
    private final MusicCapeHelperConfig config;
    private final ConfigManager configManager;
    private final OkHttpClient okHttpClient;
    private final Client client;
    private final ClientThread clientThread;

    private JLabel summaryLabel;
    private JTextField searchField;
    private JCheckBox missingOnlyCheck;
    private JPanel trackListPanel;
    private String filterText = "";

    MusicCapeHelperPanel(MusicCapeHelperPlugin plugin, MusicCapeHelperConfig config, ConfigManager configManager, OkHttpClient okHttpClient, Client client, ClientThread clientThread)
    {
        this.plugin = plugin;
        this.config = config;
        this.configManager = configManager;
        this.okHttpClient = okHttpClient;
        this.client = client;
        this.clientThread = clientThread;

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

        trackListPanel = new JPanel();
        trackListPanel.setLayout(new BoxLayout(trackListPanel, BoxLayout.Y_AXIS));
        trackListPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        add(trackListPanel);

        revalidate();
        rebuild();
    }

    private void onFilterChanged()
    {
        filterText = searchField.getText().trim().toLowerCase();
        rebuild();
    }

    void rebuild()
    {
        clientThread.invokeLater(() ->
        {
            List<TrackData> tracks = plugin.getVisibleTracks();
            Map<Integer, Boolean> unlockedState = plugin.getUnlockedState();

            long total = plugin.getTotalTracks();
            long unlocked = plugin.getUnlockedCount();
            summaryLabel.setText("Tracks: " + unlocked + " / " + total + " unlocked");

            boolean hasData = !plugin.getUnlockedState().isEmpty();

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
        });
    }

    private JPanel createTrackRow(TrackData track, Map<Integer, Boolean> unlockedState)
    {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        Boolean unlocked = unlockedState.get(track.dbRow);
        boolean isUnlocked = unlocked != null && unlocked;

        JLabel nameLabel = new JLabel(track.displayName);
        nameLabel.setForeground(isUnlocked ? COLOR_UNLOCKED : COLOR_LOCKED);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(nameLabel);

        if (!isUnlocked && !track.unlockHint.isEmpty())
        {
            JLabel hintLabel = new JLabel(track.unlockHint);
            hintLabel.setForeground(COLOR_HINT);
            hintLabel.setFont(hintLabel.getFont().deriveFont(10f));
            hintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(hintLabel);
        }

        JLabel areaLabel = new JLabel(AreaResolver.getAreaName(track.areaId));
        areaLabel.setForeground(new Color(120, 120, 120));
        areaLabel.setFont(areaLabel.getFont().deriveFont(9f));
        areaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(areaLabel);

        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.setToolTipText("<html><body style='width:250px'>"
            + "<b>" + track.displayName + "</b><br>"
            + "<i>" + AreaResolver.getAreaName(track.areaId) + "</i><br>"
            + (!track.unlockHint.isEmpty() ? track.unlockHint + "<br>" : "")
            + "<br><span style='color:#aaaaaa'>Click to open OSRS Wiki</span>"
            + "</body></html>");

        row.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                resolveAndBrowse(track.displayName);
            }
        });

        return row;
    }

    private void resolveAndBrowse(String trackName)
    {
        String plainUrl = wikiUrlForTrack(trackName);

        if (!config.wikiLookup())
        {
            browseUrl(plainUrl);
            return;
        }

        String apiEncoded = URLEncoder.encode(trackName, StandardCharsets.UTF_8);
        String apiUrl = "https://oldschool.runescape.wiki/api.php?action=query&titles="
            + apiEncoded + "_(music_track)&format=json&redirects=1";

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
                try (ResponseBody body = response.body())
                {
                    String json = body.string();
                    if (!json.contains("\"missing\""))
                    {
                        browseUrl(plainUrl + "_(music_track)");
                    }
                    else
                    {
                        browseUrl(plainUrl);
                    }
                }
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
