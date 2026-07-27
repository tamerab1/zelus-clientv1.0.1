package net.runelite.client.plugins.provablyfair;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import javax.inject.Inject;
import static net.runelite.api.MenuAction.RUNELITE_OVERLAY;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

class ProvablyFairOverlay extends OverlayPanel
{
	private final ProvablyFairPlugin plugin;

	@Inject
	ProvablyFairOverlay(ProvablyFairPlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_RIGHT);
		addMenuEntry(RUNELITE_OVERLAY, "Change client seed", "Provably Fair overlay", e -> plugin.promptForClientSeed());
		addMenuEntry(RUNELITE_OVERLAY, "Copy last proof", "Provably Fair overlay", e -> copyProofToClipboard());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (plugin.getClientSeed() == null && plugin.getServerSeedHash() == null)
		{
			// haven't seen a "Provably fair -" message yet this session (open ::casino at least once)
			return null;
		}

		panelComponent.getChildren().clear();

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Provably Fair")
			.color(Color.GREEN)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Client seed: " + truncate(plugin.getClientSeed()))
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Seed hash: " + truncate(plugin.getServerSeedHash()))
			.build());

		if (plugin.getLastRevealedServerSeed() != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Last reveal: " + truncate(plugin.getLastRevealedServerSeed()))
				.build());

			panelComponent.getChildren().add(LineComponent.builder()
				.left("Nonce: " + plugin.getLastRevealedNonce())
				.build());
		}

		return super.render(graphics);
	}

	private void copyProofToClipboard()
	{
		if (plugin.getLastRevealedServerSeed() == null)
		{
			return;
		}

		String proof = "serverSeed=" + plugin.getLastRevealedServerSeed()
			+ ", clientSeed=" + plugin.getClientSeed()
			+ ", nonce=" + plugin.getLastRevealedNonce();

		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(proof), null);
	}

	private static String truncate(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.length() <= 12 ? s : s.substring(0, 5) + ".." + s.substring(s.length() - 5);
	}
}
