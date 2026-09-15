package net.runelite.client.plugins.collectionlogluck.luck;

/**
 * Ported from the original plugin's luck/drop/BinomialDrop.java. Every Zelus drop we track is a
 * simple fixed "1/dropRate chance per kill" roll (that's the whole newDrops/&lt;npcId&gt;.json
 * schema), so this single binomial model covers all of them -- the original plugin's other ~15
 * Drop subclasses (interchangeable sets, pity systems, dupe protection, poisson-binomial for
 * variable rolls-per-kc content like ToA/CoX) exist to special-case specific OSRS activities that
 * don't have an equivalent generic representation here, so they were not ported. Unsupported
 * combinations return an incalculable result rather than a wrong number.
 */
public final class BinomialLuckCalculator
{
	private BinomialLuckCalculator()
	{
	}

	public static final double INCALCULABLE = -1;

	/**
	 * @param killCount     kills of the boss this item drops from
	 * @param dropChance    chance per kill, e.g. 1.0/880
	 * @param numSuccesses  number of this item the player has obtained
	 * @return percent chance of having received fewer drops in the same KC than this player has
	 * (i.e. what fraction of players would be drier), or {@link #INCALCULABLE} if it can't be computed
	 */
	public static double calculateLuck(int killCount, double dropChance, int numSuccesses)
	{
		if (numSuccesses <= 0)
		{
			return 0;
		}
		if (numSuccesses > killCount)
		{
			// can happen if the player obtained the item from an unaccounted-for source
			return INCALCULABLE;
		}
		return MathUtils.binomialCumulativeProbability(killCount, dropChance, numSuccesses - 1);
	}

	/**
	 * @return percent chance of having received more drops in the same KC than this player has
	 * (i.e. what fraction of players would be luckier), or {@link #INCALCULABLE} if it can't be computed
	 */
	public static double calculateDryness(int killCount, double dropChance, int numSuccesses)
	{
		if (killCount <= 0)
		{
			return 0;
		}
		if (numSuccesses > killCount)
		{
			return INCALCULABLE;
		}
		return 1 - MathUtils.binomialCumulativeProbability(killCount, dropChance, numSuccesses);
	}
}
