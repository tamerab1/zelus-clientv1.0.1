package net.runelite.client.rs;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;

/**
 * Uncaught exceptions also go to their own small file, %USERPROFILE%/.zelus/logs/client_crash.log (2026-10-21, players
 * reporting crashes): client.log rotates and mixes in everything else, so a player can send just this file. Capped at
 * 1 MB (the old file is kept once as client_crash.log.1). Never throws: a crash logger must not cause a crash.
 */
@Slf4j
public final class CrashLog
{
	private static final long MAX_BYTES = 1024 * 1024;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private CrashLog()
	{
	}

	public static File file()
	{
		return new File(System.getProperty("user.home"), ".zelus" + File.separator + "logs" + File.separator + "client_crash.log");
	}

	public static synchronized void write(Thread thread, Throwable throwable)
	{
		try
		{
			File file = file();
			file.getParentFile().mkdirs();
			if (file.length() > MAX_BYTES)
			{
				File old = new File(file.getPath() + ".1");
				old.delete();
				file.renameTo(old);
			}
			try (PrintWriter out = new PrintWriter(new FileWriter(file, true)))
			{
				out.println("==== " + LocalDateTime.now().format(TIME) + "  thread \"" + (thread == null ? "?" : thread.getName()) + "\"  java "
					+ System.getProperty("java.version") + "  " + System.getProperty("os.name"));
				throwable.printStackTrace(out);
				out.println();
			}
		}
		catch (Throwable t)
		{
			log.warn("Unable to write the crash log", t);
		}
	}
}
