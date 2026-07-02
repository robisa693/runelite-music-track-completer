package com.robisa693.musictrackcompleter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("musictrackcompleter")
public interface MusicTrackCompleterConfig extends Config
{
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
        name = "Wiki Integration",
        description = "OSRS Wiki lookup options",
        position = 10
    )
    String wikiSection = "wiki";

    @ConfigItem(
        keyName = "wikiLookup",
        name = "Wiki lookup on click",
        description = "When enabled, clicking a track opens its OSRS Wiki page.<br>This sends a lookup request to oldschool.runescape.wiki.",
        position = 11,
        section = wikiSection
    )
    default boolean wikiLookup()
    {
        return false;
    }
}
