package net.runelite.client.plugins.collectionlogluck;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.widgets.Widget;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.collectionlogluck.luck.BinomialLuckCalculator;
import net.runelite.client.plugins.collectionlogluck.luck.DropRateEntry;
import net.runelite.client.plugins.collectionlogluck.luck.DropRateRegistry;
import net.runelite.client.plugins.collectionlogluck.luck.LuckCalculationResult;
import net.runelite.client.plugins.collectionlogluck.luck.LuckUtils;
import net.runelite.client.plugins.collectionlogluck.model.BossLogPage;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calculates and displays luck for Zelus collection log items, ported from peanubnutter's
 * collection-log-luck RuneLite plugin (https://github.com/peanubnutter/collection-log-luck).
 *
 * Two things differ deliberately from the original, both driven by Zelus not being live OSRS:
 *  - Zero network access. The original queried collectionlog.net both for a player's own
 *    progress (as a durable cross-session store) and reads the SERVER's own custom collection
 *    log widget (interface 1134, see collectionlog/CollectionLogUpdated.java) directly via the
 *    client's widget tree the moment a page is viewed -- see {@link #refreshCurrentPage()}.
 *  - Drop rates come from {@link DropRateRegistry}, generated from this server's own
 *    data/npcs/drops/newDrops/*.json, not a hardcoded OSRS rate table.
 */
@Slf4j
@PluginDescriptor(
	name = "Collection Log Luck",
	description = "Calculates and displays luck for Zelus collection log items.",
	tags = {"collection", "log", "luck"}
)
public class CollectionLogLuckPlugin extends Plugin
{
	// Zelus's collection log interface -- see collectionlog/CollectionLogUpdated.java server-side.
	private static final int COLLECTION_LOG_GROUP_ID = 1134;
	private static final int NAME_COMPONENT = 321;
	private static final int KILL_COUNT_COMPONENT = 323;

	private static final Pattern KILL_COUNT_PATTERN = Pattern.compile(":\\s*([\\d,]+)\\s*$");
	private static final String LUCK_COMMAND = "!luck";
	private static final String DRY_COMMAND = "!dry";

	@Inject
	private Client client;

	@Inject
	private ChatCommandManager chatCommandManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private CollectionLogWidgetItemOverlay overlay;

	@Inject
	private CollectionLogLuckConfig config;

	private DropRateRegistry dropRateRegistry;

	// Accumulates every collection log page viewed this session, keyed by boss name. Populated
	// entirely from widget reads, per class javadoc -- never fetched.
	private final Map<String, BossLogPage> cachedPages = new LinkedHashMap<>();

	private String currentBossName;

	@Provides
	CollectionLogLuckConfig provideConfig(net.runelite.client.config.ConfigManager configManager)
	{
		return configManager.getConfig(CollectionLogLuckConfig.class);
	}

	@Override
	protected void startUp()
	{
		dropRateRegistry = new DropRateRegistry();
		overlayManager.add(overlay);
		chatCommandManager.registerCommandAsync(LUCK_COMMAND, (message, target) -> processCommand(message, target, false));
		chatCommandManager.registerCommandAsync(DRY_COMMAND, (message, target) -> processCommand(message, target, true));
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		chatCommandManager.unregisterCommand(LUCK_COMMAND);
		chatCommandManager.unregisterCommand(DRY_COMMAND);
		cachedPages.clear();
		currentBossName = null;
	}

	// Called once per visible item, every frame the collection log is open (from
	// CollectionLogWidgetItemOverlay#renderItemOverlay) -- the one place this client reliably exposes
	// a widget's real itemId/quantity for this custom interface.
	//
	// This used to rely on a separate tick-debounced background refresh (triggered off script 149,
	// settling ~2 ticks later) to keep a cached currentBossName field up to date, on the theory that
	// re-reading two widgets' text on every single frame was unnecessary work. That created a real
	// bug: WidgetItemOverlay reads live widget state every frame, but the debounced name lagged
	// behind by up to ~1.2 seconds during a page switch. Items belonging to the NEW page got recorded
	// under the OLD page's still-cached boss name for that whole window, silently corrupting that
	// boss's data (and leaving the new page's own items looking never-obtained). Confirmed live:
	// Alchemical Hydra's items were being written into "Abyssal Sire"'s page moments after switching.
	// Reading two widget texts + a regex match is cheap enough to just do synchronously, every call,
	// with no caching at all -- that fully removes the race instead of narrowing its window.
	public String recordItemSeen(int itemId, int quantity)
	{
		String bossName = syncCurrentPageIdentity();
		if (bossName == null)
		{
			return null;
		}
		BossLogPage page = cachedPages.computeIfAbsent(bossName, BossLogPage::new);
		page.putItemQuantity(itemId, quantity);
		return bossName;
	}

	// Reads the currently displayed collection log page's identity (boss name + kill count) directly
	// out of the widget tree, live, and returns the resolved boss name (or null if the collection log
	// isn't actually showing a page right now). No network call, no server-memory-only Java field --
	// exactly what the player's client is showing them at the moment this is called.
	private String syncCurrentPageIdentity()
	{
		Widget nameWidget = client.getWidget(COLLECTION_LOG_GROUP_ID, NAME_COMPONENT);
		if (nameWidget == null || nameWidget.getText() == null || nameWidget.getText().isEmpty())
		{
			return null;
		}
		String bossName = Text.removeTags(nameWidget.getText()).trim();
		if (bossName.isEmpty())
		{
			return null;
		}

		BossLogPage page = cachedPages.computeIfAbsent(bossName, BossLogPage::new);

		Widget killCountWidget = client.getWidget(COLLECTION_LOG_GROUP_ID, KILL_COUNT_COMPONENT);
		if (killCountWidget != null && killCountWidget.getText() != null)
		{
			String stripped = Text.removeTags(killCountWidget.getText());
			Matcher matcher = KILL_COUNT_PATTERN.matcher(stripped);
			if (matcher.find())
			{
				try
				{
					page.setKillCount(Integer.parseInt(matcher.group(1).replace(",", "")));
				}
				catch (NumberFormatException e)
				{
					// leave killCount as whatever it was before, rather than clobbering good data
				}
			}
		}

		currentBossName = bossName;
		return bossName;
	}

	public String getCurrentBossName()
	{
		return currentBossName;
	}

	public Client getClient()
	{
		return client;
	}

	// Returns null if the item/boss combination isn't tracked or can't be calculated.
	public LuckCalculationResult calculateLuck(String bossName, int itemId)
	{
		if (bossName == null)
		{
			return null;
		}
		DropRateEntry rate = dropRateRegistry.getRate(bossName, itemId);
		if (rate == null)
		{
			return null;
		}
		BossLogPage page = cachedPages.get(bossName);
		if (page == null)
		{
			return null;
		}

		int killCount = page.getKillCount();
		int rawObtained = page.getItemQuantity(itemId);

		// No signal at all -- 0 kills and 0 obtained is neither lucky nor dry, it's just no data.
		// The raw math treats this as exactly 50th percentile (see BinomialLuckCalculator), which
		// reads as a misleading "average luck" claim rather than "nothing to calculate yet".
		if (killCount <= 0 && rawObtained <= 0)
		{
			return null;
		}

		// Obtained the item but this boss's own tracked KC is 0 -- happens for items whose real
		// source isn't (only) the boss itself (e.g. a minion or a key/chest tied to the boss's log
		// page). There's no boss-specific roll count to run the binomial model against, but getting
		// the item at all with a recorded 0 kills is unambiguously the luckiest possible outcome
		// relative to what's tracked, so show maximal luck rather than hiding it or treating it as
		// an error.
		if (killCount <= 0)
		{
			return new LuckCalculationResult(1.0, 0.0);
		}

		// Stackable drops (e.g. 2-3 ore per successful roll) report a raw item quantity that isn't
		// directly comparable to a kill count -- 26 ore obtained over 7 kills is entirely normal
		// (and not "impossible", which is what a naive 26 > 7 comparison would conclude) if each
		// successful roll yields several units. Normalize to an equivalent roll count first.
		double averagePerRoll = rate.getAveragePerRoll();
		int numSuccesses = (int) Math.round(rawObtained / averagePerRoll);

		double dropChance = rate.getDropChancePerRoll();

		double luck = BinomialLuckCalculator.calculateLuck(killCount, dropChance, numSuccesses);
		double dryness = BinomialLuckCalculator.calculateDryness(killCount, dropChance, numSuccesses);
		if (luck == BinomialLuckCalculator.INCALCULABLE || dryness == BinomialLuckCalculator.INCALCULABLE)
		{
			return null;
		}
		return new LuckCalculationResult(luck, dryness);
	}

	private void processCommand(ChatMessage chatMessage, String message, boolean dryMode)
	{
		String itemQuery = message.trim();
		if (itemQuery.isEmpty())
		{
			return;
		}

		String needle = itemQuery.toLowerCase();
		for (Map.Entry<String, BossLogPage> pageEntry : cachedPages.entrySet())
		{
			BossLogPage page = pageEntry.getValue();
			for (int itemId : page.trackedItemIds())
			{
				String itemName = itemManager.getItemComposition(itemId).getName();
				if (itemName == null || !itemName.toLowerCase().contains(needle))
				{
					continue;
				}

				LuckCalculationResult result = calculateLuck(pageEntry.getKey(), itemId);
				if (result == null)
				{
					continue;
				}

				double toDisplay = dryMode ? 1 - result.getDryness() : result.getOverallLuck();
				int rounded = (int) Math.round(100 * toDisplay);
				String symbol = dryMode ? "%" : LuckUtils.getOrdinalSuffix(rounded);

				String response = itemName + " (" + pageEntry.getKey() + "): " + rounded + symbol
					+ (dryMode ? " chance of being at least this dry" : " percentile");
				sendChatMessage(response);
				return;
			}
		}

		sendChatMessage("No cached data for an item matching \"" + itemQuery
			+ "\" -- open its collection log page first.");
	}

	private void sendChatMessage(String message)
	{
		String formatted = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append(message)
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(formatted)
			.build());
	}
}
