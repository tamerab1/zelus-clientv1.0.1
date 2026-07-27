package net.runelite.client.plugins.provablyfair;

import com.google.inject.Provides;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Surfaces the server's "Provably fair - ..." chat messages (sent by
 * io.ruin.model.content.casino.CasinoBlackjackInterface, and eventually the other casino
 * games as they get provably-fair support) in a persistent overlay instead of scrollback
 * text, and offers a one-click way to change your client seed.
 *
 * There is no custom packet/varp channel for this -- it works purely by parsing the
 * existing chat messages the server already sends, so it needs zero server-side changes
 * to stay in sync as more games get wired up, as long as they follow the same message
 * format.
 */
@PluginDescriptor(
	name = "Provably Fair",
	description = "Shows the current provably-fair seed/hash for casino games and lets you change your client seed",
	tags = {"overlay", "gambling", "casino", "provably fair"}
)
public class ProvablyFairPlugin extends Plugin
{
	// "Provably fair - client seed: <seed> | this round's seed hash: <hash>. Change your client seed with ::casinoseed <value>."
	private static final Pattern OPEN_PATTERN = Pattern.compile(
		"Provably fair - client seed: (\\S+) \\| this round's seed hash: (\\S+)");

	// "Provably fair - server seed: <seed> | client seed: <seed> | nonce: <n>. Next round's seed hash: <hash>"
	private static final Pattern REVEAL_PATTERN = Pattern.compile(
		"Provably fair - server seed: (\\S+) \\| client seed: (\\S+) \\| nonce: (\\S+)\\. Next round's seed hash: (\\S+)");

	// "Your provably fair client seed is now: <seed> | this round's seed hash: <hash>"
	private static final Pattern SEED_SET_PATTERN = Pattern.compile(
		"Your provably fair client seed is now: (\\S+) \\| this round's seed hash: (\\S+)");

	// "Provably fair - session closed."
	private static final Pattern CLOSE_PATTERN = Pattern.compile("Provably fair - session closed\\.");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private ProvablyFairOverlay overlay;

	@Inject
	private OverlayManager overlayManager;

	@Getter(AccessLevel.PACKAGE)
	private String clientSeed;

	@Getter(AccessLevel.PACKAGE)
	private String serverSeedHash;

	@Getter(AccessLevel.PACKAGE)
	private String lastRevealedServerSeed;

	@Getter(AccessLevel.PACKAGE)
	private String lastRevealedNonce;

	@Provides
	ProvablyFairConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ProvablyFairConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		clientSeed = null;
		serverSeedHash = null;
		lastRevealedServerSeed = null;
		lastRevealedNonce = null;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		String message = event.getMessage();
		if (!message.contains("Provably fair") && !message.contains("provably fair client seed"))
		{
			return;
		}

		boolean matched = true;

		Matcher m = OPEN_PATTERN.matcher(message);
		if (m.find())
		{
			clientSeed = m.group(1);
			serverSeedHash = m.group(2);
		}
		else if ((m = REVEAL_PATTERN.matcher(message)).find())
		{
			lastRevealedServerSeed = m.group(1);
			clientSeed = m.group(2);
			lastRevealedNonce = m.group(3);
			serverSeedHash = m.group(4);
		}
		else if ((m = SEED_SET_PATTERN.matcher(message)).find())
		{
			clientSeed = m.group(1);
			serverSeedHash = m.group(2);
		}
		else if (CLOSE_PATTERN.matcher(message).find())
		{
			clientSeed = null;
			serverSeedHash = null;
			lastRevealedServerSeed = null;
			lastRevealedNonce = null;
		}
		else
		{
			matched = false;
		}

		if (matched)
		{
			// this message only exists to carry data to this plugin -- the overlay is the
			// intended place to see it, so don't also spam the chatbox with the raw text.
			event.getMessageNode().setRuneLiteFormatMessage("");
		}
	}

	/** Opens a text prompt for a new client seed, then hands it to the server via ::casinoseed. */
	void promptForClientSeed()
	{
		chatboxPanelManager.openTextInput("New provably fair client seed")
			.value(clientSeed == null ? "" : clientSeed)
			.onDone((newSeed) ->
			{
				if (newSeed == null || newSeed.isEmpty())
				{
					return;
				}
				clientThread.invoke(() -> client.setVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT, "::casinoseed " + newSeed));
			})
			.build();
	}
}
