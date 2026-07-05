package com.robisa693.musiccapehelper;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.Text;

/**
 * Guides the player to the right world map area when the marked track can only
 * be shown by a map area other than the one currently loaded: outlines the map
 * list button at the bottom of the world map, and once the list is open,
 * outlines the entry for the suggested area - or, when that entry is scrolled
 * out of view, shows which way to scroll to reach it.
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
            Widget entry = findEntry(list.getDynamicChildren(), areaName);
            if (entry == null)
            {
                entry = findEntry(list.getStaticChildren(), areaName);
            }
            if (entry == null)
            {
                return null;
            }

            Rectangle listBounds = list.getBounds();
            Rectangle entryBounds = entry.getBounds();
            if (entryBounds.y + entryBounds.height <= listBounds.y)
            {
                drawScrollHint(graphics, listBounds, true);
            }
            else if (entryBounds.y >= listBounds.y + listBounds.height)
            {
                drawScrollHint(graphics, listBounds, false);
            }
            else
            {
                Shape oldClip = graphics.getClip();
                graphics.setClip(listBounds);
                outline(graphics, entryBounds);
                graphics.setClip(oldClip);
            }
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

    private static Widget findEntry(Widget[] children, String areaName)
    {
        if (children == null)
        {
            return null;
        }
        for (Widget child : children)
        {
            if (child == null)
            {
                continue;
            }
            String text = child.getText();
            if (text != null && Text.removeTags(text).equalsIgnoreCase(areaName))
            {
                return child;
            }
        }
        return null;
    }

    private void outline(Graphics2D graphics, Rectangle bounds)
    {
        graphics.setColor(config.highlightColor());
        graphics.setStroke(new BasicStroke(2f));
        graphics.drawRect(bounds.x - 1, bounds.y - 1, bounds.width + 2, bounds.height + 2);
    }

    /**
     * An arrow with a "Scroll up/down" label at the top or bottom edge of the
     * list, on a dark backing so it reads against the list's own colors.
     */
    private void drawScrollHint(Graphics2D graphics, Rectangle listBounds, boolean up)
    {
        graphics.setFont(FontManager.getRunescapeSmallFont());
        FontMetrics metrics = graphics.getFontMetrics();
        String label = up ? "Scroll up" : "Scroll down";
        int textWidth = metrics.stringWidth(label);

        int boxWidth = 16 + textWidth + 12;
        int boxHeight = 18;
        int boxX = listBounds.x + (listBounds.width - boxWidth) / 2;
        int boxY = up ? listBounds.y + 2 : listBounds.y + listBounds.height - boxHeight - 2;

        graphics.setColor(new Color(0, 0, 0, 170));
        graphics.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 6, 6);

        int arrowX = boxX + 10;
        int arrowMidY = boxY + boxHeight / 2;
        Polygon arrow = new Polygon();
        if (up)
        {
            arrow.addPoint(arrowX, arrowMidY - 5);
            arrow.addPoint(arrowX - 5, arrowMidY + 4);
            arrow.addPoint(arrowX + 5, arrowMidY + 4);
        }
        else
        {
            arrow.addPoint(arrowX, arrowMidY + 5);
            arrow.addPoint(arrowX - 5, arrowMidY - 4);
            arrow.addPoint(arrowX + 5, arrowMidY - 4);
        }
        graphics.setColor(config.highlightColor());
        graphics.fillPolygon(arrow);

        int textX = arrowX + 9;
        int textY = arrowMidY + metrics.getAscent() / 2 - 1;
        graphics.drawString(label, textX, textY);
    }
}
