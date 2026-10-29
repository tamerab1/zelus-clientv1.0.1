package net.runelite.client.plugins.zeluslogin;

import java.lang.reflect.Field;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Rev 241's title screen opens on Jagex's "Upgrade to a Jagex Account" panel ("Switch to a Jagex Account for improved
 * security", Download Launcher / Login), whose buttons go to Jagex's web authorisation. Zelus logins are
 * username/password, so the login screen is moved straight to state 2, the username/password box, whenever it sits
 * on that panel: state 0 (where the client starts and returns to after a logout) or 38.
 * <p>
 * The client tick isn't posted on the title screen, so this polls on a timer and switches on the client thread. The
 * API has no setter for the login state: it is the obfuscated static {@code ar.cq}, read by
 * {@link Client#getLoginIndex()} as {@code cq * -1568212415}. Those names belong to this injected client (rev 241);
 * if they don't resolve, the plugin logs once and does nothing.
 */
@Slf4j
@PluginDescriptor(
	name = "Zelus Login Screen",
	description = "Skips the Jagex Account upgrade panel on the login screen",
	hidden = true,
	enabledByDefault = true
)
public class ZelusLoginScreenPlugin extends Plugin
{
	private static final int LOGIN_STATE_WELCOME = 0;
	private static final int LOGIN_STATE_JAGEX_ACCOUNT_UPGRADE = 38;
	private static final int LOGIN_STATE_USERNAME_PASSWORD = 2;
	private static final String LOGIN_STATE_CLASS = "ar";
	private static final String LOGIN_STATE_FIELD = "cq";
	private static final int LOGIN_STATE_GETTER_MULTIPLIER = -1568212415;
	private static final String LOGIN_STRINGS_CLASS = "ks";
	private static final String[] LOGIN_MESSAGE_SOURCES = {"io", "iw", "is"};
	private static final String[] LOGIN_MESSAGE_FIELDS = {"cg", "ct", "co"};

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	private ScheduledFuture<?> poll;
	private Field loginState;
	private volatile boolean unavailable;

	@Override
	protected void startUp()
	{
		poll = executor.scheduleWithFixedDelay(() -> clientThread.invoke(this::skipUpgradePanel), 100, 100, TimeUnit.MILLISECONDS);
	}

	@Override
	protected void shutDown()
	{
		if (poll != null)
		{
			poll.cancel(false);
			poll = null;
		}
	}

	private void skipUpgradePanel()
	{
		if (unavailable || client.getGameState() != GameState.LOGIN_SCREEN)
		{
			return;
		}
		int state = client.getLoginIndex();
		if (state != LOGIN_STATE_WELCOME && state != LOGIN_STATE_JAGEX_ACCOUNT_UPGRADE)
		{
			return;
		}
		try
		{
			if (loginState == null)
			{
				Class<?> c = Class.forName(LOGIN_STATE_CLASS, true, client.getClass().getClassLoader());
				loginState = c.getDeclaredField(LOGIN_STATE_FIELD);
				loginState.setAccessible(true);
			}
			loginState.setInt(null, LOGIN_STATE_USERNAME_PASSWORD * inverse(LOGIN_STATE_GETTER_MULTIPLIER));
			if (client.getLoginIndex() != LOGIN_STATE_USERNAME_PASSWORD)
			{
				throw new IllegalStateException("login state did not change (" + client.getLoginIndex() + ")");
			}
			setLoginMessage();
			// the client's own welcome -> login transition fills the remembered username; skipping it means doing that here
			String remembered = client.getPreferences().getRememberedUsername();
			if (remembered != null && !remembered.isEmpty() && (client.getUsername() == null || client.getUsername().isEmpty()))
			{
				client.setUsername(remembered);
			}
			log.info("Zelus login screen: skipped login state {} to the username/password box (remembered username: {})",
				state, remembered == null || remembered.isEmpty() ? "none" : "set");
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			unavailable = true;
			log.warn("Zelus login screen: can't skip the Jagex Account panel on this client", e);
		}
	}

	/**
	 * The client's own switch to state 2 ({@code ar.ce}) also fills the three login message lines {@code ar.cg/ct/co}
	 * from {@code ks.io/iw/is} ("", "Enter your username/email &amp; password.", ""). Skipping it left the box blank.
	 */
	private void setLoginMessage() throws ReflectiveOperationException
	{
		ClassLoader loader = client.getClass().getClassLoader();
		Class<?> strings = Class.forName(LOGIN_STRINGS_CLASS, true, loader);
		Class<?> login = Class.forName(LOGIN_STATE_CLASS, true, loader);
		for (int i = 0; i < LOGIN_MESSAGE_FIELDS.length; i++)
		{
			Field source = strings.getDeclaredField(LOGIN_MESSAGE_SOURCES[i]);
			Field target = login.getDeclaredField(LOGIN_MESSAGE_FIELDS[i]);
			source.setAccessible(true);
			target.setAccessible(true);
			target.set(null, source.get(null));
		}
	}

	/** multiplicative inverse of an odd int modulo 2^32 (Newton's iteration) */
	private static int inverse(int m)
	{
		int x = m;
		for (int i = 0; i < 5; i++)
		{
			x *= 2 - m * x;
		}
		return x;
	}
}
