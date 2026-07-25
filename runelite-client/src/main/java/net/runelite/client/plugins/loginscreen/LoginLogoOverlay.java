package net.runelite.client.plugins.loginscreen;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class LoginLogoOverlay extends Overlay
{
	private final Client client;
	private BufferedImage background;

	@Inject
	LoginLogoOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		// UNDER_WIDGETS renders after the game engine (logo included) but before interface widgets
		// so our background covers the logo while the login form stays visible on top
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	void setBackground(BufferedImage bg)
	{
		this.background = bg;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (client.getGameState() != GameState.LOGIN_SCREEN || background == null)
		{
			return null;
		}

		// Paint the full background over the entire canvas, covering the engine-rendered logo
		graphics.drawImage(background, 0, 0, null);
		return null;
	}
}
