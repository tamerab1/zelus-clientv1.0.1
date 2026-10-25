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
		42406, 42408, 42410, 42412,
		42414, 42416, 42418, 42420, 42422, 42424,
		42228,
		// Round 4: textured upgrades, partyhats/santa hats, pets
		42426, 42428, 42430, 42432, 42434, 42436, 42438, 42440, 42442, 42444,
		42446, 42448, 42450, 42452, 42454, 42456, 42458, 42460, 42462, 42464,
		42466, 42468, 42470, 42472, 42474, 42476, 42478, 42480, 42482, 42484,
		42486, 42488, 42490, 42492, 42494, 42496, 42498, 42500, 42502, 42504,
		42506, 42508,
		42512, 42518, 42524, 42530, 42536, 42542, // tier 2 combat pets (electric texture 445)
		42514, 42520, 42526, 42532, 42538, 42544, // tier 3 combat pets (cosmic texture 444)
		42546, // Ascended Bestiary
		42548, // Astral Spore
		42616, // Combat Pet Shard
		42618, // Nightfall bow (celestial), texture 235
		42554, // Infernal Fury Gloves (boxing T4, lava texture 453)
		42556, // Cosmic Champion Gloves (boxing T5, cosmic blend texture 454)
		42558, // Boxing Mystery Chest (royal flow texture 455)
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
