package net.runelite.client.plugins.karnprofiles;

/**
 * The engine's colour table: a packed HSL colour (6 bits hue, 3 saturation, 7 lightness) to RGB, built the way the
 * client builds its Rasterizer3D palette, at its default brightness.
 */
final class Palette
{
	private Palette()
	{
	}

	private static final int[] TABLE = new int[65536];
	private static double built = -1;

	static
	{
		build(0.75);
	}

	/** Rebuilds the table for the client's brightness setting (0.6 brightest .. 0.9 darkest), as the engine does. */
	static synchronized void build(double brightness)
	{
		if (brightness == built)
		{
			return;
		}
		built = brightness;
		int i = 0;
		for (int hs = 0; hs < 512; hs++)
		{
			double hue = (hs >> 3) / 64.0 + 0.0078125;
			double sat = (hs & 7) / 8.0 + 0.0625;
			for (int l = 0; l < 128; l++)
			{
				double lum = l / 128.0, r = lum, g = lum, b = lum;
				if (sat != 0.0)
				{
					double q = lum < 0.5 ? lum * (1.0 + sat) : lum + sat - lum * sat;
					double p = 2.0 * lum - q;
					r = channel(p, q, hue + 1.0 / 3.0);
					g = channel(p, q, hue);
					b = channel(p, q, hue - 1.0 / 3.0);
				}
				int ri = (int) (Math.pow(r, brightness) * 256.0), gi = (int) (Math.pow(g, brightness) * 256.0),
					bi = (int) (Math.pow(b, brightness) * 256.0);
				TABLE[i++] = Math.min(255, ri) << 16 | Math.min(255, gi) << 8 | Math.min(255, bi);
			}
		}
	}

	private static double channel(double p, double q, double t)
	{
		if (t < 0)
		{
			t += 1.0;
		}
		if (t > 1)
		{
			t -= 1.0;
		}
		if (6.0 * t < 1.0)
		{
			return p + (q - p) * 6.0 * t;
		}
		if (2.0 * t < 1.0)
		{
			return q;
		}
		if (3.0 * t < 2.0)
		{
			return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
		}
		return p;
	}

	static int rgb(int hsl)
	{
		return TABLE[hsl & 0xffff];
	}
}
