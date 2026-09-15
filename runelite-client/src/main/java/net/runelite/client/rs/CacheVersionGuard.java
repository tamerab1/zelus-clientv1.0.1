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

	private CacheVersionGuard()
	{
	}

	public static void runIfNeeded()
	{
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
