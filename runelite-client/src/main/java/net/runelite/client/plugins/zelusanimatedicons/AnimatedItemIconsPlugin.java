package net.runelite.client.plugins.zelusanimatedicons;

import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ClientTick;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Zelus: animates the textures of animated-texture items in their inventory/bank/equipment icons.
 * The game renders each item icon once and caches the sprite, so an animated texture normally
 * shows as a still frame there. While one of these items is held, worn or in the bank, the item
 * sprite cache is cleared every few client ticks so the icons are re-rendered with the texture's
 * current animation frame. Only icons actually being drawn are re-rendered.
 */
@PluginDescriptor(
	name = "Zelus Animated Item Icons",
	description = "Animates the textures of Zelus animated-texture items in inventory, bank and equipment icons",
	enabledByDefault = true
)
public class AnimatedItemIconsPlugin extends Plugin
{
	// Client ticks are 20 ms: refresh about 10 times a second.
	private static final int REFRESH_CLIENT_TICKS = 5;

	// Items whose models use animated textures (Judicator set, texture test items, textured items).
	private static final Set<Integer> ANIMATED_ITEMS = Set.of(
		59552, 59553, 59554, 59555, 59556, 59557,
		31606, 31608, 31610, 31612,
		31614, 31616, 31618, 31620, 31622, 31624,
		31428,
		// Round 4: textured upgrades, partyhats/santa hats, pets
		31626, 31628, 31630, 31632, 31634, 31636, 31638, 31640, 31642, 31644,
		31646, 31648, 31650, 31652, 31654, 31656, 31658, 31660, 31662, 31664,
		31666, 31668, 31670, 31672, 31674, 31676, 31678, 31680, 31682, 31684,
		31686, 31688, 31690, 31692, 31694, 31696, 31698, 31700, 31702, 31704,
		31706, 31708,
		31712, 31718, 31724, 31730, 31736, 31742, // tier 2 combat pets (electric texture 187)
		31714, 31720, 31726, 31732, 31738, 31744, // tier 3 combat pets (cosmic texture 186)
		31746, // Ascended Bestiary
		31748, // Astral Spore
		31754, // Infernal Fury Gloves (boxing T4, lava texture 195)
		31756, // Cosmic Champion Gloves (boxing T5, cosmic blend texture 196)
		31758, // Boxing Mystery Chest (royal flow texture 197)
		59551 // Infernal Wings (lava texture 59)
	);

	@Inject
	private Client client;

	private int ticks;


	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || ++ticks < REFRESH_CLIENT_TICKS)
		{
			return;
		}
		ticks = 0;
		if (holds(InventoryID.INV) || holds(InventoryID.WORN) || holds(InventoryID.BANK))
		{
			client.getItemSpriteCache().reset();
		}
	}

	private boolean holds(int containerId)
	{
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return false;
		}
		for (Item item : container.getItems())
		{
			if (ANIMATED_ITEMS.contains(item.getId()))
			{
				return true;
			}
		}
		return false;
	}
}
