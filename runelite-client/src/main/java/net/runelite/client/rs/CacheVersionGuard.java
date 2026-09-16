package net.runelite.client.rs;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * One-time, fully automatic local cache wipe on a large server-side cache rebuild.
 *
 * 2026-09-16 incident: the v1.1.89 update rewrote a huge portion of the binary cache in one shot
 * (new items, relocated models, baked recolors, ground-item proxy inserts). Any player whose local
 * jagexcache predates that rebuild got a hard error_game_js5crc crash instead of a graceful
 * incremental re-download -- this client fork's JS5 sync can't reconcile a diff that large against
 * an old local cache. A brand-new/empty cache always connects fine, since there's nothing to diff
 * against. Confirmed by direct reproduction: restoring an old cache snapshot and connecting to the
 * live production server reproduced the exact same crash; a cleared cache did not.
 *
 * Rather than ask players to manually delete AppData folders, this detects "first launch since
 * CACHE_REVISION last changed" and wipes the local jagexcache automatically, exactly once, before
 * the client (or anything else) ever touches it.
 *
 * HOW TO USE: bump CACHE_REVISION to any new distinct string in the same release that ships another
 * large/structural cache rebuild. A normal incremental content patch (new item, new boss, a data
 * file tweak) does NOT need a bump -- only a rebuild invasive enough to reproduce this crash class
 * (mass relocation, mass recolor bake, etc). This must run and complete before ReasonClientLoader
 * (or anything else) reads jagexcache -- see the call in RuneLite.main().
 */
@Slf4j
public final class CacheVersionGuard
{
	private static final String CACHE_REVISION = "v1.1.89";

	// See beginSession()/markSessionSafe()'s javadocs -- a SEPARATE, always-on safety net
	// (independent of CACHE_REVISION) for a player closing the client mid-download, or any other
	// early crash, rather than the launcher's own supervisor process: unlike the launcher (a
	// separate executable already installed on every player's machine, with no self-update
	// mechanism of its own), this class ships inside client.jar itself, which every existing
	// installed launcher already re-downloads automatically on every launch -- so a fix placed
	// here reaches every player immediately, with no new launcher download required.
	private static final File SESSION_MARKER = new File(RuneLite.RUNELITE_DIR, "session_active");

	// See wipeCacheNow()/applyPendingWipe()'s javadocs -- 2026-09-16 follow-up #2: a same-process
	// delete attempted from inside a still-running, already-crashing JVM can fail on Windows because
	// that same process's own (unmodified, obfuscated) JS5 engine can still hold
	// main_file_cache.dat2 open on a file handle. This marker makes the wipe durable regardless: it
	// is a plain new file that is never locked by anything, so writing it always succeeds, and
	// applyPendingWipe() (called at the very top of runIfNeeded(), before this NEW process has
	// opened a single cache file) finishes the job with zero lock contention.
	private static final File WIPE_PENDING_MARKER = new File(RuneLite.RUNELITE_DIR, "force_wipe_pending");

	private CacheVersionGuard()
	{
	}

	public static void runIfNeeded()
	{
		applyPendingWipe();

		File marker = new File(RuneLite.RUNELITE_DIR, "cache_" + CACHE_REVISION + "_wiped");
		if (marker.exists())
		{
			return;
		}

		try
		{
			File jagexCache = new File(System.getProperty("user.home"), ".zelus/.runelite/jagexcache");
			if (jagexCache.exists())
			{
				log.info("Cache revision {} not yet applied on this machine -- wiping local jagexcache once", CACHE_REVISION);
				deleteRecursively(jagexCache);
			}

			// The xtea region-key cache is keyed to specific cache content too -- clear it
			// alongside jagexcache so a stale region key can't cause a related desync.
			deleteIfExists(new File(RuneLite.CACHE_DIR, "xtea"));
			deleteIfExists(new File(RuneLite.CACHE_DIR, "xtea.json"));

			marker.getParentFile().mkdirs();
			Files.write(marker.toPath(), CACHE_REVISION.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			// Never block startup over a best-effort cache wipe -- worst case, the player still
			// hits the same error_game_js5crc this exists to prevent, no worse off than before.
			log.warn("Failed to apply cache revision wipe for {}", CACHE_REVISION, e);
		}
	}

	/**
	 * Detects "the previous launch on this machine never got past its risky startup phase" --
	 * regardless of CACHE_REVISION -- and wipes jagexcache if so, before writing a fresh marker for
	 * THIS launch. Call once, at the very top of RuneLite.main(), right alongside runIfNeeded().
	 * <p>
	 * How: a marker file is written here at the START of every launch, and only removed by
	 * {@link #markSessionSafe()} once the client actually reaches the login screen (proof the JS5
	 * sync succeeded -- a crash during that sync, by definition, never gets this far). If that
	 * marker is STILL present when this method runs, the only way that's possible is that the
	 * previous launch started, wrote it, and then died before ever reaching the login screen --
	 * exactly the class of failure a player closing the client mid-download produces, and exactly
	 * the class runIfNeeded()'s one-shot-per-revision wipe can't help with a second time. This
	 * check doesn't need to know WHY the previous run died (its crash may not even be catchable --
	 * the actual JS5 engine is unmodified, obfuscated third-party code, not this fork's own), only
	 * THAT it did, which this marker proves independently of any specific error message.
	 */
	public static void beginSession()
	{
		try
		{
			if (SESSION_MARKER.exists())
			{
				log.info("Previous session never reached the login screen -- wiping local jagexcache "
						+ "in case it was left mid-download or otherwise corrupt");
				File jagexCache = new File(System.getProperty("user.home"), ".zelus/.runelite/jagexcache");
				if (jagexCache.exists())
				{
					deleteRecursively(jagexCache);
				}
				deleteIfExists(new File(RuneLite.CACHE_DIR, "xtea"));
				deleteIfExists(new File(RuneLite.CACHE_DIR, "xtea.json"));
			}

			SESSION_MARKER.getParentFile().mkdirs();
			Files.write(SESSION_MARKER.toPath(), new byte[0]);
		}
		catch (IOException e)
		{
			log.warn("Failed to run session-crash check", e);
		}
	}

	/**
	 * Marks this session as having gotten safely past its risky startup phase -- see
	 * {@link #beginSession()}. Call once, the first time the client reaches
	 * {@link net.runelite.api.GameState#LOGIN_SCREEN} (or later); see the subscriber registered in
	 * RuneLite.start().
	 */
	public static void markSessionSafe()
	{
		deleteIfExists(SESSION_MARKER);
	}

	/**
	 * 2026-09-16 follow-up: {@link #beginSession()} only catches a crash that happens BEFORE
	 * {@link #markSessionSafe()} fires at the login screen. JS5 keeps streaming new
	 * regions/models/textures for the entire play session, not just during initial login -- so a
	 * crash (or a black-screen freeze the player has to force-close) that happens well INTO an
	 * already-logged-in session leaves a marker that was already cleared long ago, and the next
	 * launch has no way to know the cache might now be inconsistent. That gap is exactly what
	 * produced a same-session "was playing fine -> black screen -> force closed -> relaunched ->
	 * error_game_js5crc" report.
	 * <p>
	 * Fix: don't wait for a heuristic on the NEXT launch at all. The moment THIS launch's engine
	 * actually reports a js5crc error (see the call in RSAppletStub), wipe the local cache
	 * synchronously, right then, before the error dialog even opens. The very next relaunch --
	 * whether the player does it themselves or an auto-recovery layer does it -- starts from a
	 * guaranteed-clean cache, deterministically, regardless of when in the session the crash
	 * happened or what caused it. This makes {@link #beginSession()}'s marker-based detection a
	 * backstop for crash classes that never reach this dialog at all (a hard JVM/native crash with
	 * no error dialog), not the primary defense for js5crc anymore.
	 */
	public static void wipeCacheNow(String reason)
	{
		// Durable half first: a plain new file, never locked by anything, so this always succeeds.
		// Guarantees the wipe happens on the NEXT launch even if the immediate attempt below can't
		// finish right now. See this field's javadoc for why the immediate attempt alone can't be
		// trusted on Windows.
		try
		{
			WIPE_PENDING_MARKER.getParentFile().mkdirs();
			Files.write(WIPE_PENDING_MARKER.toPath(), reason.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.warn("Failed to write pending-wipe marker for {}", reason, e);
		}

		// Best-effort immediate attempt: if this session's own JS5 engine has already released its
		// file handles by this point, the player gets a clean cache right away with no need to
		// relaunch at all. If a file is still locked, deleteRecursively throws partway through --
		// that's fine, whatever it already deleted stays deleted, and applyPendingWipe() finishes
		// the rest on the next launch once nothing holds those files open anymore.
		try
		{
			log.info("Attempting immediate jagexcache wipe due to: {}", reason);
			File jagexCache = new File(System.getProperty("user.home"), ".zelus/.runelite/jagexcache");
			if (jagexCache.exists())
			{
				deleteRecursively(jagexCache);
			}
			deleteIfExists(new File(RuneLite.CACHE_DIR, "xtea"));
			deleteIfExists(new File(RuneLite.CACHE_DIR, "xtea.json"));
			// Fully succeeded already -- no need to make the next launch redo this.
			deleteIfExists(WIPE_PENDING_MARKER);
		}
		catch (IOException e)
		{
			log.info("Immediate wipe for {} couldn't finish (likely a cache file this session's own "
				+ "JS5 engine still has open) -- already queued to finish automatically on the next "
				+ "launch instead", reason);
		}
	}

	/**
	 * Finishes a wipe {@link #wipeCacheNow(String)} couldn't complete immediately. Must run before
	 * ANYTHING else (including {@link #runIfNeeded()}'s own check, which calls this first) ever
	 * touches jagexcache in this process -- by construction this is always a brand-new JVM that has
	 * not opened a single cache file yet, so unlike the crashing process that left this marker
	 * behind, there is no file lock to fight here.
	 */
	private static void applyPendingWipe()
	{
		if (!WIPE_PENDING_MARKER.exists())
		{
			return;
		}

		try
		{
			log.info("Applying cache wipe requested by a previous crashed session");
			File jagexCache = new File(System.getProperty("user.home"), ".zelus/.runelite/jagexcache");
			if (jagexCache.exists())
			{
				deleteRecursively(jagexCache);
			}
			deleteIfExists(new File(RuneLite.CACHE_DIR, "xtea"));
			deleteIfExists(new File(RuneLite.CACHE_DIR, "xtea.json"));
			deleteIfExists(WIPE_PENDING_MARKER);
		}
		catch (IOException e)
		{
			// Leave the marker in place -- worst case this retries again on the launch after this
			// one, never worse than before.
			log.warn("Failed to apply pending cache wipe -- will retry on next launch", e);
		}
	}

	private static void deleteIfExists(File file)
	{
		if (file.exists() && !file.delete())
		{
			log.warn("Failed to delete {}", file);
		}
	}

	private static void deleteRecursively(File file) throws IOException
	{
		File[] children = file.listFiles();
		if (children != null)
		{
			for (File child : children)
			{
				deleteRecursively(child);
			}
		}
		if (!file.delete() && file.exists())
		{
			throw new IOException("Unable to delete " + file);
		}
	}
}
