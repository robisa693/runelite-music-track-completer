package com.robisa693.musiccapehelper;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.Text;

/**
 * Guides the player to the right world map area when the marked track can only
 * be shown by a map area other than the one currently loaded: outlines the map
 * list button at the bottom of the world map, and once the list is open,
 * outlines the entry for the suggested area.
 */
class MusicMapListHighlightOverlay extends Overlay
{
    private static final int WORLDMAP_INTERFACE_GROUP = InterfaceID.Worldmap.UNIVERSE >>> 16;

    private final Client client;
    private final MusicCapeHelperConfig config;
    private final MapNavigator mapNavigator;

    MusicMapListHighlightOverlay(Client client, MusicCapeHelperConfig config, MapNavigator mapNavigator)
    {
        this.client = client;
        this.config = config;
        this.mapNavigator = mapNavigator;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.MANUAL);
        drawAfterInterface(WORLDMAP_INTERFACE_GROUP);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        String areaName = mapNavigator.getSuggestedAreaName();
        if (areaName == null)
        {
            return null;
        }

        Widget mapWidget = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
        if (mapWidget == null || mapWidget.isHidden())
        {
            return null;
        }

        Widget list = client.getWidget(InterfaceID.Worldmap.MAPLIST_LIST);
        if (list != null && !list.isHidden())
        {
            // The list is open: outline the entry for the suggested area,
            // clipped to the list viewport so scrolled-out rows don't leak.
            Shape oldClip = graphics.getClip();
            graphics.setClip(list.getBounds());
            outlineMatchingEntry(graphics, list.getDynamicChildren(), areaName);
            outlineMatchingEntry(graphics, list.getStaticChildren(), areaName);
            graphics.setClip(oldClip);
            return null;
        }

        // The list is closed: outline the map list button so the player opens it.
        Widget button = client.getWidget(InterfaceID.Worldmap.MAPLIST_DISPLAY);
        if (button != null && !button.isHidden())
        {
            outline(graphics, button.getBounds());
        }
        return null;
    }

    private void outlineMatchingEntry(Graphics2D graphics, Widget[] children, String areaName)
    {
        if (children == null)
        {
            return;
        }
        for (Widget child : children)
        {
            if (child == null || child.isHidden())
            {
                continue;
            }
            String text = child.getText();
            if (text != null && Text.removeTags(text).equalsIgnoreCase(areaName))
            {
                outline(graphics, child.getBounds());
            }
        }
    }

    private void outline(Graphics2D graphics, Rectangle bounds)
    {
        graphics.setColor(config.highlightColor());
        graphics.setStroke(new BasicStroke(2f));
        graphics.drawRect(bounds.x - 1, bounds.y - 1, bounds.width + 2, bounds.height + 2);
    }
}
