package com.robisa693.musiccapehelper;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("musiccapehelper")
public interface MusicCapeHelperConfig extends Config
{
    enum ClickMode
    {
        WIKI,
        MAP
    }

    @ConfigSection(
        name = "Display",
        description = "Display options",
        position = 0
    )
    String displaySection = "display";

    @ConfigItem(
        keyName = "showMissingOnly",
        name = "Show missing only",
        description = "Only show tracks that are still locked",
        position = 1,
        section = displaySection
    )
    default boolean showMissingOnly()
    {
        return false;
    }

    @ConfigItem(
        keyName = "groupByArea",
        name = "Group by area",
        description = "Group tracks by area instead of showing a flat list",
        position = 2,
        section = displaySection
    )
    default boolean groupByArea()
    {
        return true;
    }

    @ConfigSection(
        name = "Click Behaviour",
        description = "What happens when you click a track",
        position = 10
    )
    String clickSection = "click";

    @ConfigItem(
        keyName = "clickMode",
        name = "Click mode",
        description = "Wiki: open OSRS Wiki page. Map: open in-game world map at the track's location.",
        position = 11,
        section = clickSection
    )
    default ClickMode clickMode()
    {
        return ClickMode.WIKI;
    }

    @ConfigSection(
        name = "In-game Guidance",
        description = "How the marked track location is shown in the game world",
        position = 20
    )
    String guidanceSection = "guidance";

    @ConfigItem(
        keyName = "showSceneHighlight",
        name = "Highlight location in scene",
        description = "Highlight the track's unlock spot on the ground when you are nearby (works in dungeons too)",
        position = 21,
        section = guidanceSection
    )
    default boolean showSceneHighlight()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showHintArrow",
        name = "Show hint arrow",
        description = "Show the minimap hint arrow when you are near the marked track's unlock spot",
        position = 22,
        section = guidanceSection
    )
    default boolean showHintArrow()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showMapAreaHighlight",
        name = "Highlight area on world map",
        description = "Draw the track's unlock area as a highlighted region on the world map, not just a dot",
        position = 23,
        section = guidanceSection
    )
    default boolean showMapAreaHighlight()
    {
        return true;
    }

    @ConfigItem(
        keyName = "highlightColor",
        name = "Highlight color",
        description = "Color used for the map area, scene highlight and marker",
        position = 24,
        section = guidanceSection
    )
    default java.awt.Color highlightColor()
    {
        return new java.awt.Color(255, 200, 0);
    }
}
