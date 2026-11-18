package net.runelite.client.rs;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;

/**
 * Karn Beta (2026-11-18): the old Zelus launcher still auto-updates to this client (it downloads the same client.jar
 * from the release), but it runs it from ~/.zelus/betaclient.jar, while the Karn Beta launcher uses ~/.karn. When the
 * running jar sits under .zelus, tell the player to install the Karn Beta launcher; they can still continue and play.
 * Shown on every launch through the old launcher, until they switch.
 */
@Slf4j
public final class OldLauncherNotice
{
	static final String DOWNLOAD_PAGE = "https://karnps.com/play";

	private OldLauncherNotice()
	{
	}

	static boolean startedByZelusLauncher()
	{
		try
		{
			File jar = new File(OldLauncherNotice.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			String path = jar.getAbsolutePath().replace('\\', '/').toLowerCase();
			return path.contains("/.zelus/");
		}
		catch (Exception e)
		{
			return false;
		}
	}

	public static void showIfNeeded()
	{
		if (!startedByZelusLauncher())
		{
			return;
		}
		try
		{
			int[] choice = {1};
			SwingUtilities.invokeAndWait(() -> choice[0] = JOptionPane.showOptionDialog(null,
				"<html><b>Zelus is now Karn Beta!</b><br><br>"
					+ "You opened the game with the old <b>Zelus launcher</b>.<br>"
					+ "Please download the new <b>Karn Beta launcher</b> from karnps.com/play<br>"
					+ "and use it from now on (it replaces the Zelus launcher when you install it).<br><br>"
					+ "Karn Beta is a fresh start: everyone begins with a brand-new character.</html>",
				"Karn Beta - new launcher",
				JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
				new String[]{"Download Karn Beta", "Continue for now"}, "Download Karn Beta"));
			if (choice[0] == 0 && Desktop.isDesktopSupported())
			{
				Desktop.getDesktop().browse(URI.create(DOWNLOAD_PAGE));
			}
		}
		catch (Exception e)
		{
			log.warn("Old launcher notice failed", e);
		}
	}
}
