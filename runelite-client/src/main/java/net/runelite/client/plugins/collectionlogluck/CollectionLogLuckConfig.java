package net.runelite.client.plugins.collectionlogluck;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(CollectionLogLuckConfig.GROUP)
public interface CollectionLogLuckConfig extends Config
{
	String GROUP = "collectionlogluck";

	@ConfigItem(
		keyName = "showOverlayText",
		name = "Show luck percentages",
		description = "Draws the luck percentage under each collection log item.",
		position = 1
	)
	default boolean showOverlayText()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOverlayBackground",
		name = "Show luck color background",
		description = "Tints each collection log item slot green/red based on luck.",
		position = 2
	)
	default boolean showOverlayBackground()
	{
		return true;
	}

	@ConfigItem(
		keyName = "replacePercentileWithDrycalcNumber",
		name = "Show dry-streak % instead of percentile",
		description = "Show the chance of being at least this dry, instead of an overall luck percentile.",
		position = 3
	)
	default boolean replacePercentileWithDrycalcNumber()
	{
		return false;
	}
}
