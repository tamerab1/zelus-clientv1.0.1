package net.runelite.client.plugins.karnprofiles;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.api.Point;
import net.runelite.client.plugins.loginscreen.LoginScreenPlugin;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;

/**
 * Paints the Karn character picker over the login screen (through LoginScreenPainter, every frame) and answers clicks.
 * Everything is laid out on the login screen's own 765 x 503 area, centred at the top of the canvas as the engine
 * draws it. The models are drawn once per size in the background (MeshRenderer) and then just blitted.
 */
final class PickerPainter
{
	static final int W = 765, H = 503;
	private static final int MAX_SLOTS = 4, SLOT_W = 160, PICK_FEET = 432, PICK_HEIGHT = 150, DETAIL_HEIGHT = 250;
	/** a model frame is shown this long (capture keeps every 2nd 20 ms client tick) */
	private static final int FRAME_MS = 40;

	private static final Color GOLD = new Color(0xffc35a), GOLD_DARK = new Color(0x7a5418), PALE = new Color(0xf0e6d8),
		MUTED = new Color(0xb8a890), CRIMSON = new Color(0x8e1016), CRIMSON_GLOW = new Color(0xd8261c),
		PANEL = new Color(12, 8, 10, 228), BOX = new Color(16, 10, 12, 215), RED = new Color(0xff4a3a);

	private final KarnProfilesPlugin plugin;
	private final BufferedImage background = ImageUtil.loadImageResource(LoginScreenPlugin.class, "login_background.png");
	private final BufferedImage logo = ImageUtil.loadImageResource(LoginScreenPlugin.class, "login_logo.png");
	/** rendered model frames per profile key + "/" + size */
	private final Map<String, MeshRenderer.Frames[]> rendered = new ConcurrentHashMap<>();
	private final Map<String, Boolean> rendering = new ConcurrentHashMap<>();
	/** click targets from the last frame painted */
	private volatile List<Hit> hits = new ArrayList<>();

	private static final class Hit
	{
		final Rectangle area;
		final Runnable action;

		Hit(Rectangle area, Runnable action)
		{
			this.area = area;
			this.action = action;
		}
	}

	PickerPainter(KarnProfilesPlugin plugin)
	{
		this.plugin = plugin;
	}

	void invalidate()
	{
		rendered.clear();
		rendering.clear();
	}

	/** True when (x, y) hit one of ours (then it's handled and the engine mustn't see the click). */
	boolean click(int x, int y)
	{
		for (Hit h : hits)
		{
			if (h.area.contains(x, y))
			{
				h.action.run();
				return true;
			}
		}
		return false;
	}

	// ================================================================== paint

	void paint(Graphics2D g)
	{
		List<Hit> next = new ArrayList<>();
		int ox = Math.max(0, (plugin.client.getCanvasWidth() - W) / 2), oy = 0;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		Point m = plugin.client.getMouseCanvasPosition();
		int mx = m == null ? -1 : m.getX(), my = m == null ? -1 : m.getY();
		switch (plugin.mode)
		{
			case PICKER:
				paintBackground(g, ox, oy);
				paintPicker(g, ox, oy, mx, my, next);
				break;
			case DETAIL:
				paintBackground(g, ox, oy);
				Profile p = plugin.selected;
				if (p != null)
				{
					paintDetail(g, ox, oy, mx, my, p, next);
				}
				break;
			default:
				paintBackground(g, ox, oy);
				paintForm(g, ox, oy, mx, my, next);
				break;
		}
		hits = next;
	}

	private void paintBackground(Graphics2D g, int ox, int oy)
	{
		g.drawImage(background, ox, oy, W, H, null);
		g.drawImage(logo, ox + (W - logo.getWidth()) / 2, oy - 4, null);
	}

	private void paintPicker(Graphics2D g, int ox, int oy, int mx, int my, List<Hit> hits)
	{
		List<Profile> all = plugin.profiles;
		int n = Math.min(MAX_SLOTS, all.size());
		int start = ox + (W - n * SLOT_W) / 2;
		for (int i = 0; i < n; i++)
		{
			Profile p = all.get(i);
			int cx = start + i * SLOT_W + SLOT_W / 2, feet = oy + PICK_FEET;
			Rectangle area = new Rectangle(cx - SLOT_W / 2 + 6, feet - PICK_HEIGHT - 50, SLOT_W - 12, PICK_HEIGHT + 62);
			boolean over = area.contains(mx, my);
			glow(g, cx, feet, over ? 120 : 96, over);
			MeshRenderer.Frames[] f = frames(p, PICK_HEIGHT);
			int top = feet - PICK_HEIGHT;
			if (f != null)
			{
				blit(g, f[0], cx - 10, feet);
				if (f[1] != null)
				{
					blit(g, f[1], cx + 40, feet);
				}
				if (!f[0].images.isEmpty())
				{
					top = feet - f[0].anchorY;
				}
			}
			else if (p.player == null)
			{
				text(g, FontManager.getRunescapeSmallFont(), MUTED, "Log in to show", cx, feet - 60, true);
			}
			label(g, cx, Math.max(oy + 150, top - 40), p.name(), "Level " + p.combatLevel, over);
			hits.add(new Hit(area, () -> plugin.select(p)));
		}
		Rectangle add = new Rectangle(ox + (W - 160) / 2, oy + 462, 160, 30);
		button(g, add, "+ New profile", GOLD, add.contains(mx, my), false);
		hits.add(new Hit(add, plugin::newProfile));
	}

	private void paintDetail(Graphics2D g, int ox, int oy, int mx, int my, Profile p, List<Hit> hits)
	{
		int feet = oy + 470, cx = ox + 200;
		glow(g, cx, feet, 150, true);
		MeshRenderer.Frames[] f = frames(p, DETAIL_HEIGHT);
		if (f != null)
		{
			blit(g, f[0], cx - 20, feet);
			if (f[1] != null)
			{
				glow(g, cx + 95, feet, 90, false);
				blit(g, f[1], cx + 95, feet);
			}
		}

		Rectangle panel = new Rectangle(ox + 400, oy + 198, 345, 290);
		frame(g, panel);
		int x = panel.x + 22, r = panel.x + panel.width - 22, y = panel.y;
		text(g, FontManager.getRunescapeBoldFont(), GOLD, p.name(), x, y + 32, false);
		text(g, FontManager.getRunescapeFont(), PALE, "Combat level " + p.combatLevel, x, y + 52, false);
		rule(g, x, r, y + 64);
		row(g, x, r, y + 88, "Total level", String.format(Locale.ENGLISH, "%,d", p.totalLevel));
		row(g, x, r, y + 110, "Pet", p.petName == null ? "None" : p.petName);
		row(g, x, r, y + 132, "Last played", lastPlayed(p.lastPlayed));
		rule(g, x, r, y + 146);

		Rectangle play = new Rectangle(x, y + 162, 142, 34), del = new Rectangle(r - 142, y + 162, 142, 34);
		button(g, play, "Play", GOLD, play.contains(mx, my), true);
		button(g, del, plugin.deleteArmed == p ? "Sure? Delete" : "Delete", RED, del.contains(mx, my), false);
		Rectangle back = new Rectangle(panel.x + (panel.width - 110) / 2, y + 208, 110, 28);
		button(g, back, "Back", PALE, back.contains(mx, my), false);
		hits.add(new Hit(play, () -> plugin.play(p)));
		hits.add(new Hit(del, () -> plugin.delete(p)));
		hits.add(new Hit(back, plugin::back));

		Font small = FontManager.getRunescapeSmallFont();
		if (p.password != null)
		{
			String s = "Password saved on this PC.";
			text(g, small, MUTED, s, panel.x + panel.width / 2 - 26, y + 262, true);
			int fx = panel.x + panel.width / 2 + g.getFontMetrics(small).stringWidth(s) / 2 - 20;
			Rectangle forget = new Rectangle(fx, y + 252, 46, 14);
			text(g, small, forget.contains(mx, my) ? GOLD : GOLD_DARK.brighter(), "Forget", fx + 23, y + 262, true);
			hits.add(new Hit(forget, () -> plugin.forgetPassword(p)));
		}
	}

	/**
	 * The Karn login box (instead of the OSRS one): username, password, Remember password, Log in, and the client's own
	 * login messages ("Invalid username or password." ...). While connecting it says so in the same box.
	 */
	private void paintForm(Graphics2D g, int ox, int oy, int mx, int my, List<Hit> hits)
	{
		boolean connecting = plugin.client.getGameState() == net.runelite.api.GameState.LOGGING_IN;
		Rectangle panel = new Rectangle(ox + (W - 330) / 2, oy + 186, 330, 222);
		frame(g, panel);
		int x = panel.x + 26, w = panel.width - 52, y = panel.y;
		Font bold = FontManager.getRunescapeBoldFont(), plain = FontManager.getRunescapeFont(), small = FontManager.getRunescapeSmallFont();

		String note = plugin.formNote, message = connecting ? "Connecting to server..." : plugin.attemptFailed ? plugin.failureMessage() : "";
		String top = note != null ? note : message.isEmpty() ? "Enter your username & password" : message;
		Color topColour = note != null || (!message.isEmpty() && !connecting) ? RED : GOLD;
		text(g, bold, topColour, ellipsis(g, bold, top, w), panel.x + panel.width / 2, y + 30, true);

		Rectangle user = new Rectangle(x, y + 58, w, 26), pass = new Rectangle(x, y + 108, w, 26);
		text(g, small, MUTED, "Username", x, y + 53, false);
		field(g, user, plugin.formUser.toString(), plugin.formFocus == 0 && !connecting, user.contains(mx, my));
		text(g, small, MUTED, "Password", x, y + 103, false);
		field(g, pass, "*".repeat(plugin.formPass.length()), plugin.formFocus == 1 && !connecting, pass.contains(mx, my));
		hits.add(new Hit(user, () -> plugin.formFocus = 0));
		hits.add(new Hit(pass, () -> plugin.formFocus = 1));

		if (plugin.canRemember())
		{
			int bx = x, by = y + 146;
			Rectangle tick = new Rectangle(bx - 2, by - 2, 150, 16);
			g.setColor(plugin.rememberTicked ? CRIMSON_GLOW : new Color(40, 30, 30));
			g.fillRect(bx, by, 11, 11);
			g.setColor(GOLD);
			g.setStroke(new BasicStroke(1f));
			g.drawRect(bx, by, 11, 11);
			if (plugin.rememberTicked)
			{
				g.setStroke(new BasicStroke(2f));
				g.drawLine(bx + 2, by + 6, bx + 5, by + 9);
				g.drawLine(bx + 5, by + 9, bx + 10, by + 2);
			}
			text(g, small, tick.contains(mx, my) ? GOLD : PALE, "Remember password", bx + 18, by + 10, false);
			hits.add(new Hit(tick, () -> plugin.rememberTicked = !plugin.rememberTicked));
		}

		boolean back = !plugin.profiles.isEmpty();
		Rectangle login = back ? new Rectangle(x, y + 174, (w - 12) / 2, 34) : new Rectangle(x, y + 174, w, 34);
		button(g, login, connecting ? "Please wait..." : "Log in", GOLD, login.contains(mx, my) && !connecting, true);
		if (!connecting)
		{
			hits.add(new Hit(login, plugin::submit));
		}
		if (back)
		{
			Rectangle chars = new Rectangle(x + w - (w - 12) / 2, y + 174, (w - 12) / 2, 34);
			button(g, chars, "Characters", PALE, chars.contains(mx, my), false);
			hits.add(new Hit(chars, plugin::back));
		}
	}

	/** A text box: dark well, gold edge when focused, blinking caret. */
	private static void field(Graphics2D g, Rectangle b, String value, boolean focused, boolean over)
	{
		RoundRectangle2D r = new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, 6, 6);
		g.setColor(new Color(8, 5, 6, 235));
		g.fill(r);
		g.setStroke(new BasicStroke(focused ? 1.8f : 1.2f));
		g.setColor(focused ? GOLD : over ? GOLD_DARK.brighter() : GOLD_DARK);
		g.draw(r);
		Font f = FontManager.getRunescapeFont();
		String shown = value;
		FontMetrics fm = g.getFontMetrics(f);
		while (fm.stringWidth(shown) > b.width - 18 && shown.length() > 0)
		{
			shown = shown.substring(1); // keep the end (where the caret is) in view
		}
		int ty = b.y + (b.height + fm.getAscent()) / 2 - 2;
		text(g, f, PALE, shown, b.x + 8, ty, false);
		if (focused && (System.currentTimeMillis() / 500) % 2 == 0)
		{
			int cx = b.x + 9 + fm.stringWidth(shown);
			g.setColor(GOLD);
			g.fillRect(cx, b.y + 6, 1, b.height - 12);
		}
	}

	private static String ellipsis(Graphics2D g, Font f, String s, int max)
	{
		FontMetrics fm = g.getFontMetrics(f);
		if (fm.stringWidth(s) <= max)
		{
			return s;
		}
		while (s.length() > 1 && fm.stringWidth(s + "...") > max)
		{
			s = s.substring(0, s.length() - 1);
		}
		return s + "...";
	}

	// ================================================================== models

	/** [player, pet] for this size, or null while they're still being drawn (or there's no saved model). */
	private MeshRenderer.Frames[] frames(Profile p, int height)
	{
		if (p.player == null || p.player.frames.isEmpty())
		{
			return null;
		}
		String k = p.key + "/" + height;
		MeshRenderer.Frames[] f = rendered.get(k);
		if (f == null && rendering.putIfAbsent(k, Boolean.TRUE) == null)
		{
			plugin.renderer.submit(() ->
			{
				double scale = height / (double) Math.max(1, MeshRenderer.height(p.player));
				MeshRenderer.Frames pl = MeshRenderer.render(p.player, scale);
				MeshRenderer.Frames pe = p.pet == null ? null : MeshRenderer.render(p.pet, scale);
				rendered.put(k, new MeshRenderer.Frames[]{pl, pe});
			});
		}
		return f;
	}

	private static void blit(Graphics2D g, MeshRenderer.Frames f, int feetX, int feetY)
	{
		if (f == null || f.images.isEmpty())
		{
			return;
		}
		BufferedImage img = f.images.get((int) ((System.currentTimeMillis() / FRAME_MS) % f.images.size()));
		g.drawImage(img, feetX - f.anchorX, feetY - f.anchorY, null);
	}

	// ================================================================== pieces

	private static void glow(Graphics2D g, int cx, int cy, int width, boolean strong)
	{
		Ellipse2D e = new Ellipse2D.Float(cx - width / 2f, cy - width / 7f, width, width / 3.5f);
		g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), width / 2f, new float[]{0f, 0.6f, 1f},
			new Color[]{new Color(216, 38, 28, strong ? 170 : 110), new Color(142, 16, 22, strong ? 80 : 50), new Color(0, 0, 0, 0)}));
		g.fill(e);
	}

	/** The name plate above a character (as in the reference: name, then the level). */
	private static void label(Graphics2D g, int cx, int y, String name, String level, boolean over)
	{
		Font bold = FontManager.getRunescapeBoldFont(), plain = FontManager.getRunescapeSmallFont();
		int w = Math.max(g.getFontMetrics(bold).stringWidth(name), g.getFontMetrics(plain).stringWidth(level)) + 20;
		RoundRectangle2D r = new RoundRectangle2D.Float(cx - w / 2f, y, w, 34, 6, 6);
		g.setColor(BOX);
		g.fill(r);
		g.setStroke(new BasicStroke(over ? 2f : 1.2f));
		g.setColor(over ? GOLD : GOLD_DARK);
		g.draw(r);
		text(g, bold, over ? GOLD : PALE, name, cx, y + 15, true);
		text(g, plain, MUTED, level, cx, y + 29, true);
	}

	private static void frame(Graphics2D g, Rectangle b)
	{
		RoundRectangle2D plate = new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, 14, 14);
		g.setColor(PANEL);
		g.fill(plate);
		g.setStroke(new BasicStroke(3f));
		g.setPaint(new GradientPaint(b.x, b.y, GOLD, b.x, b.y + b.height, GOLD_DARK));
		g.draw(plate);
		g.setStroke(new BasicStroke(1.2f));
		g.setColor(CRIMSON);
		g.draw(new RoundRectangle2D.Float(b.x + 5, b.y + 5, b.width - 10, b.height - 10, 10, 10));
		for (int[] p : new int[][]{{b.x, b.y}, {b.x + b.width, b.y}, {b.x, b.y + b.height}, {b.x + b.width, b.y + b.height}})
		{
			Polygon d = new Polygon(new int[]{p[0], p[0] + 7, p[0], p[0] - 7}, new int[]{p[1] - 7, p[1], p[1] + 7, p[1]}, 4);
			g.setPaint(new GradientPaint(p[0], p[1] - 7, CRIMSON_GLOW, p[0], p[1] + 7, CRIMSON));
			g.fill(d);
			g.setStroke(new BasicStroke(1.5f));
			g.setColor(GOLD);
			g.draw(d);
		}
	}

	private static void rule(Graphics2D g, int x, int r, int y)
	{
		g.setPaint(new GradientPaint(x, 0, new Color(0, 0, 0, 0), (x + r) / 2f, 0, GOLD_DARK, true));
		g.fillRect(x, y, r - x, 1);
	}

	private static void row(Graphics2D g, int x, int r, int y, String label, String value)
	{
		Font f = FontManager.getRunescapeFont();
		text(g, f, MUTED, label, x, y, false);
		text(g, f, PALE, value, r - g.getFontMetrics(f).stringWidth(value), y, false);
	}

	private static void button(Graphics2D g, Rectangle b, String label, Color text, boolean over, boolean primary)
	{
		RoundRectangle2D r = new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, 8, 8);
		Color face = primary ? CRIMSON : new Color(0x2a1a1a), lit = primary ? CRIMSON_GLOW : new Color(0x4a2a26);
		g.setPaint(new GradientPaint(b.x, b.y, over ? lit.brighter() : lit, b.x, b.y + b.height, over ? lit : face));
		g.fill(r);
		g.setStroke(new BasicStroke(over ? 2.2f : 1.6f));
		g.setPaint(new GradientPaint(b.x, b.y, GOLD, b.x, b.y + b.height, GOLD_DARK));
		g.draw(r);
		Font f = FontManager.getRunescapeBoldFont();
		FontMetrics fm = g.getFontMetrics(f);
		text(g, f, text, label, (int) b.getCenterX(), b.y + (b.height + fm.getAscent()) / 2 - 2, true);
	}

	/** OSRS-style text with a 1 px black shadow; centred on x when asked. */
	private static void text(Graphics2D g, Font f, Color c, String s, int x, int y, boolean centre)
	{
		g.setFont(f);
		int tx = centre ? x - g.getFontMetrics().stringWidth(s) / 2 : x;
		g.setColor(Color.BLACK);
		g.drawString(s, tx + 1, y + 1);
		g.setColor(c);
		g.drawString(s, tx, y);
	}

	static String lastPlayed(long millis)
	{
		if (millis <= 0)
		{
			return "Never";
		}
		ZoneId zone = ZoneId.systemDefault();
		LocalDate then = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate(), today = LocalDate.now(zone);
		long days = ChronoUnit.DAYS.between(then, today);
		if (days <= 0)
		{
			return "Today";
		}
		if (days == 1)
		{
			return "Yesterday";
		}
		if (days < 7)
		{
			return days + " days ago";
		}
		return then.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
	}
}
