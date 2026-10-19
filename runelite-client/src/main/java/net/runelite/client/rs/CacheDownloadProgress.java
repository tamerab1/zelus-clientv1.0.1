package net.runelite.client.rs;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.SplashScreen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows the game engine's startup cache download (2026-10-19, "it stays on loading then just freezes").
 * <p>
 * The splash screen closes and the game window opens BEFORE the engine has finished downloading its cache
 * from the server, and the engine's own loading screen gave no sign of progress during a long download --
 * so players closed the client thinking it had frozen. If the client hasn't reached the login screen
 * shortly after its window opens, this re-opens the splash over it and keeps it updated twice a second
 * with what is actually happening on disk: percentage, megabytes and download speed, or "waiting for
 * server" when nothing arrives. It closes the moment the login screen appears
 * ({@link CacheVersionGuard#markSessionSafe()}).
 * <p>
 * The engine downloads into jagexcache/oldschool/LIVE; the 100% mark is how big that folder was the last
 * time this machine reached the login screen (remembered in .runelite/cache_download_size), or an
 * estimate the first time. The percentage never claims 100 before the login screen.
 */
@Slf4j
public final class CacheDownloadProgress
{
	private static final File EXPECTED_FILE = new File(RuneLite.RUNELITE_DIR, "cache_download_size");
	private static final long DEFAULT_EXPECTED = 160L << 20;
	private static final long SHOW_AFTER_MS = 1500, STALL_MS = 15_000, GIVE_UP_MS = 20 * 60_000;
	private static final double MB = 1 << 20;

	private static final AtomicBoolean done = new AtomicBoolean();
	private static ScheduledExecutorService timer;
	private static volatile boolean shown;
	// only touched by start() (before the timer runs) and the timer thread
	private static long startedAt, startSize, lastSize, lastGrowthAt, expected;
	private static double bytesPerSecond;

	private CacheDownloadProgress()
	{
	}

	/** Call once the game window is showing; does nothing if the login screen was already reached. */
	public static synchronized void start()
	{
		if (done.get() || timer != null)
		{
			return;
		}
		expected = readExpected();
		startedAt = lastGrowthAt = System.currentTimeMillis();
		startSize = lastSize = CacheVersionGuard.cacheSize();
		timer = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "Cache download progress");
			t.setDaemon(true);
			return t;
		});
		timer.scheduleAtFixedRate(CacheDownloadProgress::tick, 500, 500, TimeUnit.MILLISECONDS);
	}

	/**
	 * Closes the progress window for good. reachedLogin: the login screen appeared, so the cache size now
	 * is remembered as the next launch's 100% mark.
	 */
	public static synchronized void finish(boolean reachedLogin)
	{
		if (!done.compareAndSet(false, true))
		{
			return;
		}
		if (timer != null)
		{
			timer.shutdownNow();
		}
		if (shown)
		{
			SplashScreen.stop();
		}
		if (reachedLogin)
		{
			long size = CacheVersionGuard.cacheSize();
			if (size > 0)
			{
				try
				{
					Files.write(EXPECTED_FILE.toPath(), Long.toString(size).getBytes(StandardCharsets.UTF_8));
				}
				catch (IOException e)
				{
					log.debug("Couldn't remember the cache size", e);
				}
			}
		}
	}

	// Not synchronized: SplashScreen.init() waits for the Swing thread, which may itself be calling finish()
	// (the js5crc dialog path) -- so a window opened just as finish() ran is closed again right here.
	private static void tick()
	{
		try
		{
			if (done.get())
			{
				return;
			}
			long now = System.currentTimeMillis();
			if (now - startedAt > GIVE_UP_MS)
			{
				finish(false);
				return;
			}
			long size = CacheVersionGuard.cacheSize();
			long delta = Math.max(0, size - lastSize);
			if (delta > 0)
			{
				lastGrowthAt = now;
			}
			bytesPerSecond = bytesPerSecond * 0.8 + delta * 2 * 0.2;
			lastSize = size;

			if (!shown)
			{
				if (now - startedAt < SHOW_AFTER_MS)
				{
					return;
				}
				SplashScreen.init();
				shown = true;
				if (done.get())
				{
					SplashScreen.stop();
					return;
				}
			}

			SplashScreen.raiseOverClient();
			boolean downloading = size > startSize || size < expected * 0.9;
			if (downloading)
			{
				double fraction = Math.min(0.99, size / (double) Math.max(expected, size + 1));
				int percent = (int) (fraction * 100);
				String progress = now - lastGrowthAt > STALL_MS
					? String.format("%d%% - %.1f MB - waiting for server", percent, size / MB)
					: String.format("%d%% - %.1f MB - %.1f MB/s", percent, size / MB, bytesPerSecond / MB);
				SplashScreen.stage(fraction, "Downloading game data", "Please keep Zelus open", progress);
			}
			else
			{
				SplashScreen.stage(0.99, "Loading game data", "Almost there...", null);
			}
		}
		catch (Exception e)
		{
			// never let the progress window take the client down
			log.warn("Cache download progress failed", e);
			finish(false);
		}
	}

	private static long readExpected()
	{
		try
		{
			if (EXPECTED_FILE.exists())
			{
				long v = Long.parseLong(new String(Files.readAllBytes(EXPECTED_FILE.toPath()), StandardCharsets.UTF_8).trim());
				if (v > (8L << 20))
				{
					return v;
				}
			}
		}
		catch (IOException | NumberFormatException e)
		{
			log.debug("Couldn't read the remembered cache size", e);
		}
		return DEFAULT_EXPECTED;
	}
}
