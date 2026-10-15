package net.runelite.client.plugins.zelusweaponsparkles;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Inject;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * The Twisted bow (celestial) icon twinkles wherever it is shown (inventory, bank, equipment): a few gold glints along
 * the bow's diagonal pulse in and out at their own pace, plus a faint warm halo, so the item reads as special at a glance
 * without hiding the sprite.
 */
class CelestialItemOverlay extends WidgetItemOverlay
{
	private static final int GLINTS = 4;
	private static final long CYCLE_MS = 1400;

	/** Each glint: a spot along the bow (0..1 along its bottom-left to top-right diagonal), a side offset and a phase. */
	private final float[] along = new float[GLINTS], side = new float[GLINTS], phase = new float[GLINTS];
	private final long[] born = new long[GLINTS];

	@Inject
	CelestialItemOverlay()
	{
		showOnInventory();
		showOnBank();
		showOnEquipment();
		for (int i = 0; i < GLINTS; i++)
		{
			respawn(i, System.currentTimeMillis() - (long) (CYCLE_MS * i / (float) GLINTS));
		}
	}

	private void respawn(int i, long now)
	{
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		along[i] = 0.15f + rnd.nextFloat() * 0.7f;
		side[i] = (rnd.nextFloat() - 0.5f) * 6f;
		phase[i] = 0.7f + rnd.nextFloat() * 0.6f;
		born[i] = now;
	}

	@Override
	public void renderItemOverlay(Graphics2D g, int itemId, WidgetItem item)
	{
		if (!WeaponSparklesOverlay.isCelestial(itemId))
		{
			return;
		}
		Rectangle r = item.getCanvasBounds();
		if (r == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		// faint pulsing halo behind the glints
		float pulse = (float) (0.5 + 0.5 * Math.sin(now / 420.0));
		g.setColor(new Color(255, 214, 90, (int) (18 + 22 * pulse)));
		g.fillOval(r.x + 4, r.y + 4, r.width - 8, r.height - 8);
		for (int i = 0; i < GLINTS; i++)
		{
			long life = (long) (CYCLE_MS * phase[i]);
			float t = (now - born[i]) / (float) life;
			if (t >= 1f)
			{
				respawn(i, now);
				t = 0f;
			}
			// rise and fall: 0 -> 1 -> 0
			float a = t < 0.5f ? t * 2f : (1f - t) * 2f;
			int x = Math.round(r.x + 6 + along[i] * (r.width - 12) + side[i]);
			int y = Math.round(r.y + r.height - 6 - along[i] * (r.height - 12) + side[i]);
			int alpha = Math.max(0, Math.min(255, (int) (a * 255)));
			g.setColor(new Color(255, 236, 150, alpha / 3));
			g.fillRect(x - 2, y - 2, 5, 5);
			g.setColor(new Color(255, 250, 215, alpha));
			g.fillRect(x - 2, y, 5, 1);
			g.fillRect(x, y - 2, 1, 5);
		}
	}
}
