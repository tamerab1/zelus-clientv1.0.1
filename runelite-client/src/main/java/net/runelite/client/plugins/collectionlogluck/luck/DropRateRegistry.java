package net.runelite.client.plugins.collectionlogluck.luck;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Zelus's own drop-rate database for the collection log luck plugin.
 *
 * Unlike the original plugin (which shipped a ~4500-line hardcoded per-item OSRS rate table and
 * fetched player collection log data from collectionlog.net), Zelus has custom drop rates,
 * custom items, and no collectionlog.net access. This registry is generated directly from this
 * server's own live data -- data/npcs/drops/newDrops/&lt;npcId&gt;.json, cross-referenced with npc
 * names read from the cache (npc display names only live in the binary cache, not in any JSON
 * data file) -- so it's always accurate to whatever rates are actually live, custom bosses
 * included, with no separate "OSRS default" table to fall out of sync.
 *
 * To regenerate after changing a boss's drop table:
 *   1. cd .dev/cache-restore-tool
 *   2. java -cp "out_reencode;<contents of cp_with_jna.txt>" DumpAllNpcNames <cachePath> npc_names.tsv
 *   3. python3 gen_drop_rates.py drop_rates.json npc_names.tsv
 *   4. copy the result over
 *   client/runelite-client/src/main/resources/net/runelite/client/plugins/collectionlogluck/drop_rates.json
 */
@Slf4j
public class DropRateRegistry
{
	private static final String RESOURCE_PATH = "/net/runelite/client/plugins/collectionlogluck/drop_rates.json";

	// itemId -> bossName -> rate entry. An item can drop from multiple bosses at different rates,
	// so the boss name of whichever collection log page is currently open picks the right entry.
	private final Map<Integer, Map<String, DropRateEntry>> registry;

	public DropRateRegistry()
	{
		registry = load();
	}

	private static Map<Integer, Map<String, DropRateEntry>> load()
	{
		try (InputStream is = DropRateRegistry.class.getResourceAsStream(RESOURCE_PATH))
		{
			if (is == null)
			{
				log.warn("collectionlogluck: drop_rates.json resource not found, luck calculations disabled");
				return Collections.emptyMap();
			}

			Gson gson = new Gson();
			Type rawType = new TypeToken<Map<String, Map<String, RawEntry>>>()
			{
			}.getType();
			Map<String, Map<String, RawEntry>> raw = gson.fromJson(
				new InputStreamReader(is, StandardCharsets.UTF_8), rawType);

			Map<Integer, Map<String, DropRateEntry>> result = new HashMap<>();
			for (Map.Entry<String, Map<String, RawEntry>> itemEntry : raw.entrySet())
			{
				int itemId;
				try
				{
					itemId = Integer.parseInt(itemEntry.getKey());
				}
				catch (NumberFormatException e)
				{
					continue;
				}

				Map<String, DropRateEntry> byBoss = new HashMap<>();
				for (Map.Entry<String, RawEntry> bossEntry : itemEntry.getValue().entrySet())
				{
					RawEntry rawEntry = bossEntry.getValue();
					byBoss.put(bossEntry.getKey(),
						new DropRateEntry(rawEntry.dropRate, rawEntry.minAmount, rawEntry.maxAmount));
				}
				result.put(itemId, byBoss);
			}

			log.debug("collectionlogluck: loaded drop rates for {} items", result.size());
			return result;
		}
		catch (IOException e)
		{
			log.warn("collectionlogluck: failed to load drop_rates.json", e);
			return Collections.emptyMap();
		}
	}

	// Returns the drop rate for this item from this specific boss, or null if unknown/unsupported.
	// bossName comes from the collection log's own displayed text, and the registry's keys come
	// independently from the game cache's npc names -- both should name the same boss the same way,
	// but an exact-match-only lookup is one stray space or apostrophe style away from silently
	// missing a boss that's actually in the registry. Fall back to a case/whitespace-insensitive
	// match before giving up, and log misses so a genuine gap is distinguishable from a string quirk.
	public DropRateEntry getRate(String bossName, int itemId)
	{
		Map<String, DropRateEntry> byBoss = registry.get(itemId);
		if (byBoss == null)
		{
			return null;
		}
		DropRateEntry exact = byBoss.get(bossName);
		if (exact != null)
		{
			return exact;
		}
		String normalizedTarget = normalize(bossName);
		for (Map.Entry<String, DropRateEntry> entry : byBoss.entrySet())
		{
			if (normalize(entry.getKey()).equals(normalizedTarget))
			{
				return entry.getValue();
			}
		}
		log.info("[collectionlogluck] no drop rate entry for item {} under boss \"{}\" (registry has this item under: {})",
			itemId, bossName, byBoss.keySet());
		return null;
	}

	private static String normalize(String name)
	{
		return name.toLowerCase().replaceAll("\\s+", " ").trim();
	}

	private static class RawEntry
	{
		int dropRate;
		int minAmount;
		int maxAmount;
	}
}
