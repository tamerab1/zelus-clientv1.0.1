package net.runelite.client.plugins.collectionlogluck.luck;

// One boss's roll info for one item, e.g. Sylvaroth's Ancient Cleaver at 1/880 per kill.
public class DropRateEntry
{
	private final int dropRate;
	private final int minAmount;
	private final int maxAmount;

	public DropRateEntry(int dropRate, int minAmount, int maxAmount)
	{
		this.dropRate = dropRate;
		this.minAmount = minAmount;
		this.maxAmount = maxAmount;
	}

	// dropRate is stored as "1 in dropRate", i.e. a chance of 1/dropRate per roll.
	public double getDropChancePerRoll()
	{
		return 1.0 / dropRate;
	}

	public int getDropRate()
	{
		return dropRate;
	}

	public int getMinAmount()
	{
		return minAmount;
	}

	public int getMaxAmount()
	{
		return maxAmount;
	}

	// For stackable drops (e.g. 2-3 ore per successful roll), the raw obtained quantity isn't
	// directly comparable to a kill count -- it needs converting to an equivalent number of
	// successful rolls first. 1 for fixed single-unit drops (the vast majority), so this is a
	// no-op for those.
	public double getAveragePerRoll()
	{
		return (minAmount + maxAmount) / 2.0;
	}
}
