package com.robisa693.musiccapehelper;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Highlights the marked track's unlock spot on the ground when it is inside the
 * loaded scene - the same mechanism the clue scroll plugin uses, so it works in
 * dungeons and instances where the world map cannot help.
 */
class MusicSceneOverlay extends Overlay
{
    private final Client client;
    private final MusicCapeHelperConfig config;
    private final MapNavigator mapNavigator;

    @Inject
    MusicSceneOverlay(Client client, MusicCapeHelperConfig config, MapNavigator mapNavigator)
    {
        this.client = client;
        this.config = config;
        this.mapNavigator = mapNavigator;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showSceneHighlight())
        {
            return null;
        }

        String track = mapNavigator.getActiveTrack();
        if (track == null)
        {
            return null;
        }

        Color color = config.highlightColor();
        for (MapNavigator.ActiveLocation loc : mapNavigator.getActiveLocations())
        {
            LocalPoint lp = LocalPoint.fromWorld(client, loc.point);
            if (lp == null)
            {
                continue;
            }

            OverlayUtil.renderTileOverlay(client, graphics, lp, mapNavigator.getMapIcon(), color);

            Point textLoc = Perspective.getCanvasTextLocation(client, graphics, lp, track, 40);
            if (textLoc != null)
            {
                OverlayUtil.renderTextLocation(graphics, textLoc, track, color);
            }
        }

        return null;
    }
}
