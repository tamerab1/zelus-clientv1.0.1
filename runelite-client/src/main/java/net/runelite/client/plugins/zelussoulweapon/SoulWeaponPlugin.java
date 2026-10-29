package net.runelite.client.plugins.zelussoulweapon;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.widgets.Widget;
import net.runelite.api.Point;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.Text;

/**
 * Zelus Soulbound weapons (2026-10-18): a live progress box -- the weapon's icon, its tier and how far it is to the next
 * one -- updated on every kill. Driven by the server's "[Soulbound] ... tier N, a / b (P%)" game messages
 * (io.ruin.model.content.soulweapons.SoulWeapons: one per Soul XP gain and one at login), so it needs no extra protocol.
 * Also sways the weapon previews in the selector (1175), the Check window (1174) and the Soul Tree (1176), and brings
 * the Soul Tree to life: rising soul particles around the weapon, a breathing glow, pulsing halos on mastered talents
 * and hover tooltips (the server writes each node's / aura card's tooltip into a hidden text component).
 */
@PluginDescriptor(
	name = "Zelus Soulbound Weapon",
	description = "Shows your Soulbound weapon's tier and progress to the next tier",
	tags = {"zelus", "soulbound", "weapon", "tier", "xp"},
	enabledByDefault = true
)
public class SoulWeaponPlugin extends Plugin
{
	/** The nine Soulbound looks: 31824..31840, even ids (blade, recurve, spire x base / Empowered / Ascended). */
	private static final int FIRST_LOOK = 42624, LAST_LOOK = 42640, DEFAULT_ICON = 42624;
	// "[Soulbound] +3 Soul XP | Tier 8 | 3,614 / 7,540 (47%)" (and the older "tier 8, 3,614 / 7,540 (47%)")
	private static final Pattern PROGRESS = Pattern.compile(
		"(?i)\\[Soulbound\\] .*?tier (\\d+)(?:(?:,| \\|) ([\\d,]+) / ([\\d,]+) \\((\\d+)%\\)| \\(max\\))");

	@Inject
	private Client client;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private SoulTreeTooltipOverlay tooltipOverlay;

	/** The Soul Tree tooltip under the mouse this frame (null = none), drawn by {@link SoulTreeTooltipOverlay}. */
	volatile String hoverText;

	private TierBox box;

	/** {group, child} of every model preview to sway: selector cards (1175: 24 / 73 / 122) and the Check window (1174:20). */
	private static final int[][] SWAY = {{1175, 24}, {1175, 73}, {1175, 122}, {1174, 20}, {1176, 25}};

	/**
	 * Soul Tree (1176) component ids, from .dev/cache-restore-tool BuildSoulTreeInterface: the weapon glow, the particle
	 * dots (in the weapon's clip layer), the nodes (track ring = hover area, mastered halo, hidden tooltip text) and the
	 * aura cards (card = hover area, hidden tooltip text).
	 */
	// Soul Tree round 7 (2026-10-28, BuildSoulTreeInterface): the 3x2 matrix, the weapon on a dais (no glow sprite)
	// round 8 (2026-10-29): three cards; each node is an 84x56 card (its frame is the hover area for the tooltip)
	// round 9 (2026-10-29): Weapon / Talents pages; the stage's model is 260x118, the node cards 104x48
	private static final int TREE = 1176, TREE_FIRST_PARTICLE = 26, TREE_PARTICLES = 8, TREE_FIRST_NODE = 59,
		TREE_NODES = 6, TREE_NODE_STRIDE = 20, TREE_NODE_HALO = 8, TREE_NODE_TIP = 19, TREE_FIRST_AURA = 203,
		TREE_AURA_STRIDE = 11, TREE_AURA_TIP = 10, TREE_MODAL = 239, TREE_GEM = 6, TREE_GEM_Y = 45;
	/** Particle life (client ticks), rise height and the clip box's floor; the halos and glow breathe on PULSE_PERIOD. */
	private static final int PARTICLE_LIFE = 140, PARTICLE_RISE = 84, PARTICLE_FLOOR = 108, PULSE_PERIOD = 120;
	/**
	 * A gentle sway (yaw +-SWAY_AMPLITUDE of 2048, one cycle every SWAY_PERIOD client ticks of 20 ms) around the angle the
	 * server set -- the item's own icon showcase angle. A full spin turned thin weapons (the spire) edge-on and unreadable.
	 */
	private static final int SWAY_AMPLITUDE = 150, SWAY_PERIOD = 300;
	private static final int SPIN_SPEED = 7; // yaw units per client tick (2048 a turn)
	/** Per preview: {showcase yaw the server set, the yaw this plugin last wrote}. */
	private final int[][] sway = new int[SWAY.length][];
	private int swayTick;

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		swayTick++;
		int offset = (int) Math.round(SWAY_AMPLITUDE * Math.sin(2 * Math.PI * swayTick / SWAY_PERIOD));
		for (int i = 0; i < SWAY.length; i++)
		{
			Widget w = client.getWidget(SWAY[i][0], SWAY[i][1]);
			if (w == null || w.isHidden())
			{
				sway[i] = null;
				continue;
			}
			// a yaw this plugin didn't write is a fresh one from the server: it becomes the new showcase angle
			if (sway[i] == null || w.getRotationY() != sway[i][1])
			{
				sway[i] = new int[]{w.getRotationY(), 0};
			}
			// the check window and the Soul Tree turn the weapon a full, continuous circle (one turn ~ 6 s); the
			// three-look select window keeps its gentle sway so the looks stay side by side
			boolean spin = SWAY[i][0] == 1174 || SWAY[i][0] == 1176;
			int yaw = (sway[i][0] + (spin ? swayTick * SPIN_SPEED : offset)) & 2047;
			w.setRotationZ(0); // the server sends a showcase camera (zoom, X, Y); an item icon's own Z tilt doesn't apply
			w.setRotationY(yaw);
			sway[i][1] = yaw;
		}
		animateSoulTree();
	}

	/** Soul Tree: particles drift up out of the weapon's glow and fade; the glow and the mastered halos breathe. */
	private void animateSoulTree()
	{
		Widget root = client.getWidget(TREE, 0);
		if (root == null || root.isHidden())
		{
			return;
		}
		double breath = (1 + Math.sin(2 * Math.PI * swayTick / PULSE_PERIOD)) / 2; // 0..1
		// the points gem bobs gently (+-2 px on a slow sine) beside "Available Points"
		Widget gem = client.getWidget(TREE, TREE_GEM);
		if (gem != null)
		{
			gem.setOriginalY(TREE_GEM_Y + (int) Math.round(2 * Math.sin(2 * Math.PI * swayTick / PULSE_PERIOD)));
			gem.revalidate();
		}
		for (int n = 0; n < TREE_NODES; n++)
		{
			Widget halo = client.getWidget(TREE, TREE_FIRST_NODE + n * TREE_NODE_STRIDE + TREE_NODE_HALO);
			if (halo != null && !halo.isHidden())
			{
				halo.setOpacity((int) (40 + 170 * (1 - breath))); // 255 = invisible
			}
		}
		for (int i = 0; i < TREE_PARTICLES; i++)
		{
			Widget dot = client.getWidget(TREE, TREE_FIRST_PARTICLE + i);
			if (dot == null)
			{
				continue;
			}
			int age = (swayTick + i * (PARTICLE_LIFE / TREE_PARTICLES) * 7) % PARTICLE_LIFE;
			double life = age / (double) PARTICLE_LIFE;
			int baseX = 76 + i * 15;
			dot.setOriginalX(baseX + (int) Math.round(3 * Math.sin(2 * Math.PI * (life * 1.5 + i * 0.37))));
			dot.setOriginalY(PARTICLE_FLOOR - (int) (PARTICLE_RISE * life));
			dot.setOpacity((int) (255 * Math.abs(2 * life - 1))); // fades in, then out as it rises
			dot.setHidden(false);
			dot.revalidate();
		}
	}

	/** Soul Tree tooltips: the hovered node's or aura card's details, written by the server into a hidden text. */
	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		hoverText = null;
		Widget root = client.getWidget(TREE, 0);
		Widget modal = client.getWidget(TREE, TREE_MODAL);
		if (root == null || root.isHidden() || (modal != null && !modal.isHidden()))
		{
			return; // closed, or the Rank Up confirmation is up: nothing under it is hoverable
		}
		Point mouse = client.getMouseCanvasPosition();
		for (int n = 0; n < TREE_NODES; n++)
		{
			int node = TREE_FIRST_NODE + n * TREE_NODE_STRIDE;
			if (showTip(mouse, client.getWidget(TREE, node), client.getWidget(TREE, node + TREE_NODE_TIP)))
			{
				return;
			}
		}
		for (int a = 0; a < 3; a++)
		{
			int card = TREE_FIRST_AURA + a * TREE_AURA_STRIDE;
			if (showTip(mouse, client.getWidget(TREE, card), client.getWidget(TREE, card + TREE_AURA_TIP)))
			{
				return;
			}
		}
	}

	private boolean showTip(Point mouse, Widget area, Widget tip)
	{
		if (area == null || tip == null || area.isHidden() || tip.getText() == null || tip.getText().isEmpty()
			|| !area.getBounds().contains(mouse.getX(), mouse.getY()))
		{
			return false;
		}
		hoverText = tip.getText();
		return true;
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(tooltipOverlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(tooltipOverlay);
		hoverText = null;
		clear();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		Matcher m = PROGRESS.matcher(Text.removeTags(event.getMessage()));
		if (!m.find())
		{
			return;
		}
		int tier = Integer.parseInt(m.group(1));
		boolean max = m.group(2) == null;
		int pct = max ? 100 : Integer.parseInt(m.group(4));
		String tooltip = max ? "Soulbound weapon: tier " + tier + " (max)"
			: "Soulbound weapon: tier " + tier + "</br>" + m.group(2) + " / " + m.group(3) + " Soul XP to tier " + (tier + 1);
		int wielded = wieldedSoulWeapon();
		int icon = wielded != -1 ? wielded : soulWeaponId();
		if (box == null)
		{
			// the picture must exist BEFORE the box is registered: the manager scales it on add, and a box without a
			// scaled picture is never drawn (2026-10-18: the box vanished this way)
			box = new TierBox();
			box.update(icon, tier, pct, max);
			box.setTooltip(tooltip);
			infoBoxManager.addInfoBox(box);
			return;
		}
		box.update(icon, tier, pct, max);
		box.setTooltip(tooltip);
	}

	/** Wielding another Soulbound look (an evolution, a swap) changes the icon at once, not at the next kill. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int wielded;
		if (box != null && event.getContainerId() == InventoryID.EQUIPMENT.getId() && (wielded = wieldedSoulWeapon()) != -1
			&& wielded != box.iconId)
		{
			box.update(wielded, box.tier, box.pct, box.max);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			clear();
		}
	}

	/** The Soulbound look in the weapon slot, or -1: the box is only drawn while one is wielded. */
	private int wieldedSoulWeapon()
	{
		ItemContainer c = client.getItemContainer(InventoryID.EQUIPMENT);
		Item weapon = c == null ? null : c.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		int id = weapon == null ? -1 : weapon.getId();
		return id >= FIRST_LOOK && id <= LAST_LOOK && id % 2 == 0 ? id : -1;
	}

	/** The Soulbound look worn (or carried), for the box's icon. */
	private int soulWeaponId()
	{
		for (InventoryID id : new InventoryID[]{InventoryID.EQUIPMENT, InventoryID.INVENTORY})
		{
			ItemContainer c = client.getItemContainer(id);
			if (c == null)
			{
				continue;
			}
			for (Item item : c.getItems())
			{
				int itemId = item.getId();
				if (itemId >= FIRST_LOOK && itemId <= LAST_LOOK && itemId % 2 == 0)
				{
					return itemId;
				}
			}
		}
		return box != null ? box.iconId : DEFAULT_ICON;
	}

	private void clear()
	{
		if (box != null)
		{
			infoBoxManager.removeInfoBox(box);
			box = null;
		}
	}

	/**
	 * The progress box. Drawn only while a Soulbound weapon is in the weapon slot (render() is checked every frame, so it
	 * appears / disappears the moment the weapon is wielded or removed). The tier is painted on the icon's top-left in
	 * soul cyan and the percent is the box's bottom text, both with a drop shadow, so they never crowd each other.
	 */
	private class TierBox extends InfoBox
	{
		int iconId = -1;
		int tier;
		int pct;
		boolean max;

		TierBox()
		{
			super(null, SoulWeaponPlugin.this);
		}

		void update(int icon, int tier, int pct, boolean max)
		{
			boolean redraw = icon != iconId || tier != this.tier || max != this.max || getImage() == null;
			this.iconId = icon;
			this.tier = tier;
			this.pct = pct;
			this.max = max;
			if (redraw)
			{
				repaint();
			}
		}

		/** The weapon icon with "T7" (gold at max tier) in the top-left corner; redone once the icon finishes loading. */
		void repaint()
		{
			AsyncBufferedImage item = itemManager.getImage(iconId);
			compose(item);
			item.onLoaded(() -> compose(item));
		}

		private void compose(BufferedImage item)
		{
			BufferedImage img = new BufferedImage(item.getWidth(), item.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			g.drawImage(item, 0, 0, null);
			g.setFont(FontManager.getRunescapeSmallFont());
			String label = "T" + tier;
			int y = g.getFontMetrics().getAscent() - 1;
			g.setColor(Color.BLACK);
			g.drawString(label, 1, y + 1);
			g.setColor(max ? new Color(0xFFD700) : new Color(0x00FFFF));
			g.drawString(label, 0, y);
			g.dispose();
			setImage(img);
			infoBoxManager.updateInfoBoxImage(this); // re-scale: a changed picture is otherwise never redrawn
		}

		@Override
		public boolean render()
		{
			return wieldedSoulWeapon() != -1;
		}

		@Override
		public String getText()
		{
			return max ? "MAX" : pct + "%";
		}

		@Override
		public Color getTextColor()
		{
			return max ? new Color(0xFFD700) : new Color(0x00FF00);
		}
	}
}
