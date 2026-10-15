package net.runelite.client.plugins.zelusmenutags;

import java.util.regex.Pattern;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Zelus: the right-click menu (and the hover text built from it) doesn't understand {@code <shad=...>} tags, so player
 * titles -- authored as {@code <col=X><shad=000000>ZLUS </shad></col>} for chat and overhead names -- showed the raw
 * markup ("Attack <shad=000000>ZLUS </shad>Mr Boolt33"). Strips the shadow tags from every entry as it is added; the
 * colour tags, which the menu does draw, are kept.
 */
@PluginDescriptor(
	name = "Zelus Menu Tags",
	description = "Removes unsupported <shad> tags from right-click menu entries",
	enabledByDefault = true,
	hidden = true
)
public class MenuTagsPlugin extends Plugin
{
	private static final Pattern SHAD = Pattern.compile("</?shad(=[^>]*)?>");

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		MenuEntry entry = event.getMenuEntry();
		String target = entry.getTarget();
		if (target != null && target.contains("shad"))
		{
			entry.setTarget(SHAD.matcher(target).replaceAll(""));
		}
		String option = entry.getOption();
		if (option != null && option.contains("shad"))
		{
			entry.setOption(SHAD.matcher(option).replaceAll(""));
		}
	}
}
