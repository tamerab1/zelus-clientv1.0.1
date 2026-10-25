package net.runelite.client.plugins.zeluslogin;

import java.lang.reflect.Field;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ClientTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Rev 241's login screen opens on Jagex's "Upgrade to a Jagex Account" panel (login state 38: "Switch to a
 * Jagex Account for improved security", Download Launcher / Login) and its buttons send you to Jagex's web
 * authorisation. Zelus logins are username/password, so whenever the login screen enters state 38 it is moved
 * to state 2, the standard username/password box.
 * <p>
 * The API has no setter for the login state: it is the obfuscated static {@code ar.cq}, read by
 * {@link Client#getLoginIndex()} as {@code cq * -1568212415}. Those names are tied to this injected client
 * (rev 241); if they don't resolve, the plugin logs once and does nothing.
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
	private static final int LOGIN_STATE_JAGEX_ACCOUNT_UPGRADE = 38;
	private static final int LOGIN_STATE_USERNAME_PASSWORD = 2;
	private static final String LOGIN_STATE_CLASS = "ar";
	private static final String LOGIN_STATE_FIELD = "cq";
	private static final int LOGIN_STATE_GETTER_MULTIPLIER = -1568212415;

	@Inject
	private Client client;

	private Field loginState;
	private boolean unavailable;

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		if (unavailable || client.getGameState() != GameState.LOGIN_SCREEN
			|| client.getLoginIndex() != LOGIN_STATE_JAGEX_ACCOUNT_UPGRADE)
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
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			unavailable = true;
			log.warn("Zelus login screen: can't skip the Jagex Account panel on this client", e);
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
