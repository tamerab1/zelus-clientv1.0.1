package net.runelite.client.plugins.zelussoulweapon;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The Soul Tree's hover tooltip (2026-10-18 round 3). RuneLite's own tooltip is see-through, so the labels under it
 * bled into the text. This one is a fully opaque dark-slate card (#111622, #334155 border), drawn on the top-most
 * overlay layer, anchored ABOVE the cursor (flipped below only when there is no room) and kept inside the game canvas,
 * so the aura row's tooltips no longer drop under the window. Text comes from {@link SoulWeaponPlugin#hoverText}:
 * "</br>" separates lines and &lt;col=rrggbb&gt; / &lt;/col&gt; colour runs.
 */
class SoulTreeTooltipOverlay extends Overlay
{
	private static final Color BACKGROUND = new Color(0x111622), BORDER = new Color(0x334155), TEXT = new Color(0xd8d0c0);
	private static final int PAD = 6, GAP = 14, LINE_GAP = 2;

	private final Client client;
	private final SoulWeaponPlugin plugin;

	@Inject
	SoulTreeTooltipOverlay(Client client, SoulWeaponPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.TOOLTIP);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(PRIORITY_HIGHEST);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		String text = plugin.hoverText;
		Point mouse = client.getMouseCanvasPosition();
		if (text == null || mouse == null)
		{
			return null;
		}
		g.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = g.getFontMetrics();
		String[] lines = text.split("</br>");
		int width = 0;
		for (String line : lines)
		{
			width = Math.max(width, fm.stringWidth(line.replaceAll("<[^>]*>", "")));
		}
		int lineHeight = fm.getHeight() + LINE_GAP;
		int w = width + PAD * 2, h = lines.length * lineHeight + PAD * 2 - LINE_GAP;
		int canvasW = client.getCanvasWidth(), canvasH = client.getCanvasHeight();
		int x = Math.max(2, Math.min(mouse.getX() - w / 2, canvasW - w - 2));
		int y = mouse.getY() - h - GAP; // above the cursor
		if (y < 2)
		{
			y = Math.min(mouse.getY() + GAP + 6, canvasH - h - 2);
		}

		g.setColor(BACKGROUND);
		g.fillRect(x, y, w, h);
		g.setColor(BORDER);
		g.drawRect(x, y, w - 1, h - 1);

		int baseline = y + PAD + fm.getAscent();
		for (String line : lines)
		{
			int tx = x + PAD;
			for (Object[] run : runs(line))
			{
				String s = (String) run[0];
				g.setColor(Color.BLACK);
				g.drawString(s, tx + 1, baseline + 1);
				g.setColor((Color) run[1]);
				g.drawString(s, tx, baseline);
				tx += fm.stringWidth(s);
			}
			baseline += lineHeight;
		}
		return null;
	}

	/** Splits a line into {text, colour} runs on &lt;col=..&gt; / &lt;/col&gt;; other tags are dropped. */
	private static List<Object[]> runs(String line)
	{
		List<Object[]> out = new ArrayList<>();
		Color colour = TEXT;
		int i = 0;
		while (i < line.length())
		{
			int lt = line.indexOf('<', i);
			if (lt != i)
			{
				int end = lt < 0 ? line.length() : lt;
				out.add(new Object[]{line.substring(i, end), colour});
				i = end;
				continue;
			}
			int gt = line.indexOf('>', lt);
			if (gt < 0)
			{
				out.add(new Object[]{line.substring(lt), colour});
				break;
			}
			String tag = line.substring(lt + 1, gt);
			if (tag.startsWith("col="))
			{
				try
				{
					colour = new Color(Integer.parseInt(tag.substring(4), 16));
				}
				catch (NumberFormatException ignored)
				{
				}
			}
			else if (tag.equals("/col"))
			{
				colour = TEXT;
			}
			i = gt + 1;
		}
		return out;
	}
}
