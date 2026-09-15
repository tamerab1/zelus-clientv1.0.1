package net.runelite.client.plugins.collectionlogluck.luck;

import java.awt.Color;

/**
 * Ported from the original collection-log-luck plugin's util/LuckUtils.java (formatting and
 * color-grading helpers only -- unchanged math, just relocated).
 */
public final class LuckUtils
{
	private LuckUtils()
	{
	}

	// Return green when overall luck = 1, red when overall luck = 0, and interpolate for values in
	// between. Interpolation is done in HSB space, not per RGB component, so the midpoint is yellow.
	public static Color getOverallLuckColor(double overallFraction)
	{
		// green is 1/3rd of the way around the HSB wheel
		return new Color(Color.HSBtoRGB((float) (overallFraction / 3.0), 1.0f, 1.0f)).darker().darker();
	}

	public static double getOverallLuck(double luckFraction, double drynessFraction)
	{
		double overallFraction = (luckFraction - drynessFraction + 1) / 2.0;
		return Math.max(0, Math.min(1, overallFraction));
	}

	// Given a number (e.g. 3), return its ordinal suffix ("rd").
	public static String getOrdinalSuffix(int n)
	{
		int tensDigit = n % 10;
		int percentile = n % 100;
		if (tensDigit == 1 && percentile != 11)
		{
			return "st";
		}
		else if (tensDigit == 2 && percentile != 12)
		{
			return "nd";
		}
		else if (tensDigit == 3 && percentile != 13)
		{
			return "rd";
		}
		return "th";
	}
}
