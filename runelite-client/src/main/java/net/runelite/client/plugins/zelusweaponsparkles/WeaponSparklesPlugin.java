package net.runelite.client.plugins.zelusweaponsparkles;

import javax.inject.Inject;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Zelus: golden stardust that clings to the Twisted bow (celestial). Unlike a server graphic (which plays at the
 * player's origin), the sparkles are emitted from the bow's own vertices in the player's animated model every frame,
 * so they follow the bow through idle, walk, run and attack animations. NPCs dressed in the bow (the Cape Forge Master)
 * sparkle too, and the item icon twinkles in the inventory, bank and equipment (CelestialItemOverlay).
 */
@PluginDescriptor(
	name = "Zelus Weapon Sparkles",
	description = "Golden stardust along the Twisted bow (celestial)",
	enabledByDefault = true
)
public class WeaponSparklesPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private WeaponSparklesOverlay overlay;

	@Inject
	private CelestialItemOverlay itemOverlay;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(itemOverlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(itemOverlay);
		overlay.clear();
	}
}
