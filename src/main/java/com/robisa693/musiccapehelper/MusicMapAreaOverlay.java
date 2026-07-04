package com.robisa693.musiccapehelper;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the marked track's unlock area as a translucent highlighted region on the
 * world map (instead of just a point marker), using the polygon outlines scraped
 * from the wiki. Coordinate-to-pixel math mirrors WorldMapOverlay#mapWorldPointToGraphicsPoint.
 */
class MusicMapAreaOverlay extends Overlay
{
    private static final int WORLDMAP_INTERFACE_GROUP = InterfaceID.Worldmap.UNIVERSE >>> 16;

    private final Client client;
    private final MusicCapeHelperConfig config;
    private final MapNavigator mapNavigator;

    @Inject
    MusicMapAreaOverlay(Client client, MusicCapeHelperConfig config, MapNavigator mapNavigator)
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
        if (!config.showMapAreaHighlight() || mapNavigator.getActiveTrack() == null)
        {
            return null;
        }

        Widget mapWidget = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
        WorldMap worldMap = client.getWorldMap();
        if (mapWidget == null || mapWidget.isHidden() || worldMap == null)
        {
            return null;
        }

        Rectangle bounds = mapWidget.getBounds();
        Shape oldClip = graphics.getClip();
        graphics.setClip(bounds);

        Color base = config.highlightColor();
        Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), 50);

        for (MapNavigator.ActiveLocation loc : mapNavigator.getActiveLocations())
        {
            if (loc.polygon == null || loc.polygon.size() < 3 || !mapNavigator.isSurface(loc.point))
            {
                continue;
            }

            Polygon poly = new Polygon();
            boolean valid = true;
            for (List<Number> vertex : loc.polygon)
            {
                if (vertex == null || vertex.size() < 2)
                {
                    valid = false;
                    break;
                }
                java.awt.Point p = worldToMapPixel(worldMap, bounds, vertex.get(0).intValue(), vertex.get(1).intValue());
                poly.addPoint(p.x, p.y);
            }

            if (!valid || poly.npoints < 3)
            {
                continue;
            }

            graphics.setColor(fill);
            graphics.fillPolygon(poly);
            graphics.setColor(base);
            graphics.setStroke(new BasicStroke(2f));
            graphics.drawPolygon(poly);
        }

        graphics.setClip(oldClip);
        return null;
    }

    /**
     * Converts a world coordinate to a pixel inside the world map widget.
     * Same arithmetic as WorldMapOverlay#mapWorldPointToGraphicsPoint.
     */
    private static java.awt.Point worldToMapPixel(WorldMap worldMap, Rectangle bounds, int wx, int wy)
    {
        float pixelsPerTile = worldMap.getWorldMapZoom();
        net.runelite.api.Point mapPos = worldMap.getWorldMapPosition();

        int widthInTiles = (int) Math.ceil(bounds.getWidth() / pixelsPerTile);
        int heightInTiles = (int) Math.ceil(bounds.getHeight() / pixelsPerTile);

        int yTileMax = mapPos.getY() - heightInTiles / 2;
        int yTileOffset = (yTileMax - wy - 1) * -1;
        int xTileOffset = wx + widthInTiles / 2 - mapPos.getX();

        int xGraphDiff = (int) (xTileOffset * pixelsPerTile);
        int yGraphDiff = (int) (yTileOffset * pixelsPerTile);

        yGraphDiff -= pixelsPerTile - Math.ceil(pixelsPerTile / 2);
        xGraphDiff += pixelsPerTile - Math.ceil(pixelsPerTile / 2);

        yGraphDiff = bounds.height - yGraphDiff;
        yGraphDiff += (int) bounds.getY();
        xGraphDiff += (int) bounds.getX();

        return new java.awt.Point(xGraphDiff, yGraphDiff);
    }
}
