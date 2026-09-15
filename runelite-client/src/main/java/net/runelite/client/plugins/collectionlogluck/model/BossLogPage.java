package net.runelite.client.plugins.collectionlogluck.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * One boss/category's collection log page, built entirely from widget contents the moment the
 * player views that page -- never fetched from a network API. Accumulates across every page the
 * player has viewed this session (like the original plugin's local seenItemCounts/seenKillCounts
 * caches), so luck can be looked up for any page browsed so far, not only whichever page is
 * currently open.
 */
public class BossLogPage
{
	private final String bossName;
	private int killCount;
	private final Map<Integer, Integer> itemQuantities = new HashMap<>();

	public BossLogPage(String bossName)
	{
		this.bossName = bossName;
	}

	public String getBossName()
	{
		return bossName;
	}

	public int getKillCount()
	{
		return killCount;
	}

	public void setKillCount(int killCount)
	{
		this.killCount = killCount;
	}

	public void putItemQuantity(int itemId, int quantity)
	{
		itemQuantities.put(itemId, quantity);
	}

	// 0 if the item wasn't seen on this page (not obtained, or not part of this boss's log at all)
	public int getItemQuantity(int itemId)
	{
		return itemQuantities.getOrDefault(itemId, 0);
	}

	public boolean hasItem(int itemId)
	{
		return itemQuantities.containsKey(itemId);
	}

	public Set<Integer> trackedItemIds()
	{
		return itemQuantities.keySet();
	}
}
