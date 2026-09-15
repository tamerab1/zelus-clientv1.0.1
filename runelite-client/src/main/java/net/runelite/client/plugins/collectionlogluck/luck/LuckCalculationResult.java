package net.runelite.client.plugins.collectionlogluck.luck;

import java.awt.Color;

public class LuckCalculationResult
{
	private final double luck;
	private final double dryness;
	private final double overallLuck;
	private final Color luckColor;

	public LuckCalculationResult(double luck, double dryness)
	{
		this.luck = luck;
		this.dryness = dryness;
		this.overallLuck = LuckUtils.getOverallLuck(luck, dryness);
		this.luckColor = LuckUtils.getOverallLuckColor(overallLuck);
	}

	public double getLuck()
	{
		return luck;
	}

	public double getDryness()
	{
		return dryness;
	}

	public double getOverallLuck()
	{
		return overallLuck;
	}

	public Color getLuckColor()
	{
		return luckColor;
	}
}
