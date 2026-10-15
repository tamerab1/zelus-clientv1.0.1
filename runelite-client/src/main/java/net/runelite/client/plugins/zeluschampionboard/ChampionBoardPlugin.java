package net.runelite.client.plugins.zeluschampionboard;

import javax.inject.Inject;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Zelus: the live Pit Championship ladder on the Championship Board (object 44930). The server keeps the top 5 names
 * and points in interface 1170 (never opened) for players near the Pit; this draws them onto the board's painted
 * TOP CHAMPIONS rows in the world. The full top 20 is the board's "View" window (interface 1169).
 */
@PluginDescriptor(
	name = "Zelus Championship Board",
	description = "Live top 5 on the Pit Championship Board",
	enabledByDefault = true
)
public class ChampionBoardPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private ChampionBoardOverlay overlay;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
	}
}
