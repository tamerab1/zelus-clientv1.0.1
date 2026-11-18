package net.runelite.client.ui;

import java.awt.Graphics2D;
import java.util.function.Consumer;

/**
 * Karn: a painter for the login screen. Plugin overlays only render while logged in (OverlayRenderer), so the Karn
 * character picker paints through this instead: Hooks.draw calls it every frame on the login screen, after the engine
 * has drawn the login screen and before the frame is shown (and before the GPU plugin uploads it).
 */
public final class LoginScreenPainter
{
	private LoginScreenPainter()
	{
	}

	private static volatile Consumer<Graphics2D> painter;

	public static void set(Consumer<Graphics2D> p)
	{
		painter = p;
	}

	public static void clear(Consumer<Graphics2D> p)
	{
		if (painter == p)
		{
			painter = null;
		}
	}

	public static Consumer<Graphics2D> get()
	{
		return painter;
	}
}
