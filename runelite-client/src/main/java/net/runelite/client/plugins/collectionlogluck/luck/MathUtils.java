package net.runelite.client.plugins.collectionlogluck.luck;

/**
 * Self-contained binomial distribution math -- avoids pulling in Apache Commons Math (not already
 * a dependency of runelite-client) just for a cumulative distribution function. Ported from the
 * standard binomial CDF formula used by the original collection-log-luck plugin's use of
 * {@code org.apache.commons.math3.distribution.BinomialDistribution}.
 */
public final class MathUtils
{
	private MathUtils()
	{
	}

	// ln(n!) via the Gamma function identity ln(n!) = lnGamma(n+1), computed with the
	// Lanczos approximation. Avoids overflow for n in the thousands, unlike a direct factorial.
	private static double logFactorial(int n)
	{
		return logGamma(n + 1.0);
	}

	private static final double[] LANCZOS_G = {
		676.5203681218851, -1259.1392167224028, 771.32342877765313,
		-176.61502916214059, 12.507343278686905, -0.13857109526572012,
		9.9843695780195716e-6, 1.5056327351493116e-7
	};

	private static double logGamma(double x)
	{
		if (x < 0.5)
		{
			return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1 - x);
		}
		x -= 1;
		double a = 0.99999999999980993;
		double t = x + 7.5;
		for (int i = 0; i < LANCZOS_G.length; i++)
		{
			a += LANCZOS_G[i] / (x + i + 1);
		}
		return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
	}

	private static double logBinomialCoefficient(int n, int k)
	{
		return logFactorial(n) - logFactorial(k) - logFactorial(n - k);
	}

	// P(X = k) for X ~ Binomial(n, p)
	public static double binomialProbability(int n, double p, int k)
	{
		if (k < 0 || k > n)
		{
			return 0;
		}
		if (p <= 0)
		{
			return k == 0 ? 1 : 0;
		}
		if (p >= 1)
		{
			return k == n ? 1 : 0;
		}
		double logProb = logBinomialCoefficient(n, k) + k * Math.log(p) + (n - k) * Math.log(1 - p);
		return Math.exp(logProb);
	}

	// P(X <= k) for X ~ Binomial(n, p)
	public static double binomialCumulativeProbability(int n, double p, int k)
	{
		if (k < 0)
		{
			return 0;
		}
		if (k >= n)
		{
			return 1;
		}
		double cumulative = 0;
		for (int i = 0; i <= k; i++)
		{
			cumulative += binomialProbability(n, p, i);
		}
		// clamp for floating point drift
		return Math.max(0, Math.min(1, cumulative));
	}
}
