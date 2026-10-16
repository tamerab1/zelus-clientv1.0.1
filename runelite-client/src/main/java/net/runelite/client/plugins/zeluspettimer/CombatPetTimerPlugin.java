package net.runelite.client.plugins.zeluspettimer;

import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.ui.overlay.infobox.Timer;
import net.runelite.client.util.Text;

/**
 * Zelus combat pet bursts: a countdown infobox (like the overload / vengeance timers) while a pet summoned with
 * the Ascended Bestiary's "Spawn" option is out. Driven by the server's [Bestiary] game messages
 * (io.ruin.model.entity.npc.combatpet.BestiaryBurst), so it needs no extra protocol.
 */
@PluginDescriptor(
	name = "Zelus Combat Pet Timer",
	description = "Shows how long your Ascended Bestiary combat pet has left",
	tags = {"zelus", "bestiary", "pet", "timer"},
	enabledByDefault = true
)
public class CombatPetTimerPlugin extends Plugin
{
	private static final int BESTIARY_ITEM = 31746;
	private static final Pattern START = Pattern.compile("\\[Bestiary\\] Your (.+?) answers the call for (\\d+) seconds");

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private ItemManager itemManager;

	private Timer timer;

	@Override
	protected void shutDown()
	{
		clear();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());
		Matcher start = START.matcher(message);
		if (start.find())
		{
			start(start.group(1), Integer.parseInt(start.group(2)));
		}
		else if (message.contains("returns to the Bestiary") || message.contains("companion returns to you"))
		{
			clear();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			clear();
		}
	}

	private void start(String petName, int seconds)
	{
		clear();
		timer = new Timer(seconds, ChronoUnit.SECONDS, itemManager.getImage(BESTIARY_ITEM), this);
		timer.setTooltip(petName + " - combat pet");
		infoBoxManager.addInfoBox(timer);
	}

	private void clear()
	{
		if (timer != null)
		{
			infoBoxManager.removeInfoBox(timer);
			timer = null;
		}
	}
}
