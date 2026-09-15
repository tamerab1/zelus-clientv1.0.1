package net.runelite.client.plugins.collectionlogluck;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.plugins.collectionlogluck.luck.LuckCalculationResult;
import net.runelite.client.plugins.collectionlogluck.luck.LuckUtils;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * Ported from the original plugin's CollectionLogWidgetItemOverlay.java.
 *
 * WidgetItemOverlay's base render() only clips to the item's parent bounds when the ITEM ICON's own
 * canvas bounds straddle the parent's edge -- it has no idea this subclass draws extra text below
 * that rectangle. On the last visible row of a page, the icon itself can be fully inside the
 * interface while the luck text drawn just under it pokes out past the interface's bottom edge onto
 * the 3D game view underneath. So every draw here explicitly clips to the item's own parent widget
 * bounds first, rather than relying on the base class's straddle-only clip.
 */
@Slf4j
public class CollectionLogWidgetItemOverlay extends WidgetItemOverlay
{
	// on a scale from 0 to 255
	private static final int LUCK_OVERLAY_ALPHA = 40;
	private static final int LUCK_OVERLAY_TEXT_ALPHA = 200;

	private static final int COLLECTION_LOG_GROUP_ID = 1134;

	// The "Completion Rewards" popup (collectionlog/CollectionLogUpdated.java's viewRewards()/
	// closeViewRewards()) is a CHILD widget inside this SAME interface group, toggled via
	// setHidden(1134, 529, ...) -- it isn't a separate interface, so drawAfterInterface(1134) keeps
	// firing (and drawing luck text) even while this popup is open and visually covering the item
	// grid underneath. Skip drawing entirely whenever it's visible.
	private static final int REWARDS_POPUP_COMPONENT = 529;

	@Inject
	private CollectionLogLuckPlugin plugin;

	@Inject
	private CollectionLogLuckConfig config;

	// Throttles the clip-bounds diagnostic log (see findClipBounds) to once per distinct value
	// instead of once per item per frame.
	private String lastLoggedChain;

	public CollectionLogWidgetItemOverlay()
	{
		super();
		drawAfterInterface(COLLECTION_LOG_GROUP_ID);
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		// Record this every frame regardless of whether luck is calculable below -- this is the
		// only reliable source of item quantity for this custom interface (see
		// CollectionLogLuckPlugin#recordItemSeen for why the widget tree can't be scanned directly).
		// Records the item AND resolves the boss name in one call so both use the exact same
		// synchronous widget read -- see CollectionLogLuckPlugin#recordItemSeen for why a separately
		// cached boss name (read at a different moment) caused real cross-boss data corruption.
		String bossName = plugin.recordItemSeen(itemId, widgetItem.getQuantity());
		if (bossName == null)
		{
			return;
		}

		// Defensive: don't draw over an item slot the game itself has hidden, and don't draw at all
		// while the rewards popup (or anything else covering the grid) is showing.
		Widget itemWidget = widgetItem.getWidget();
		if (itemWidget == null || itemWidget.isHidden())
		{
			return;
		}
		Widget rewardsPopup = plugin.getClient().getWidget(COLLECTION_LOG_GROUP_ID, REWARDS_POPUP_COMPONENT);
		if (rewardsPopup != null && !rewardsPopup.isSelfHidden())
		{
			return;
		}

		LuckCalculationResult result = plugin.calculateLuck(bossName, itemId);
		if (result == null)
		{
			return;
		}

		Rectangle bounds = widgetItem.getCanvasBounds();
		Color luckColor = result.getLuckColor();

		Rectangle originalClip = graphics.getClipBounds();
		Rectangle clipBounds = findClipBounds(widgetItem.getWidget());
		if (clipBounds != null)
		{
			graphics.setClip(clipBounds);
		}

		if (config.showOverlayBackground())
		{
			Color renderColor = new Color(luckColor.getRed(), luckColor.getGreen(), luckColor.getBlue(), LUCK_OVERLAY_ALPHA);
			graphics.setColor(renderColor);
			graphics.fill3DRect(bounds.x, bounds.y, bounds.width, bounds.height, false);
		}

		if (config.showOverlayText())
		{
			double toDisplay = config.replacePercentileWithDrycalcNumber()
				? 1 - result.getDryness() : result.getOverallLuck();
			int rounded = (int) Math.round(100 * toDisplay);
			String symbol = config.replacePercentileWithDrycalcNumber() ? "%" : LuckUtils.getOrdinalSuffix(rounded);
			String text = rounded + symbol;

			graphics.setColor(Color.BLACK);
			graphics.drawString(text, bounds.x + 1, bounds.y + bounds.height + 1);

			Color textColor = new Color(luckColor.getRed(), luckColor.getGreen(), luckColor.getBlue(), LUCK_OVERLAY_TEXT_ALPHA)
				.brighter().brighter();
			graphics.setColor(textColor);
			graphics.drawString(text, bounds.x, bounds.y + bounds.height);
		}

		graphics.setClip(originalClip);
	}

	// Walking all the way to the outermost ancestor overshot -- it left interface 1134's own subtree
	// entirely and landed on shared client chrome sized to the whole game canvas, which clips
	// nothing in practice. Instead, walk up only as long as the ancestor is still part of group 1134
	// (a widget's packed id is (groupId << 16 | childId), so getId() >>> 16 gives the group), and use
	// the last widget still inside that group as the clip source -- that should be interface 1134's
	// own outermost container, sized to the actual visible popup box, not shared UI chrome.
	private Rectangle findClipBounds(Widget widget)
	{
		StringBuilder chain = new StringBuilder();
		Widget current = widget;
		Widget lastInGroup = widget;
		int depth = 0;
		while (depth < 20)
		{
			chain.append(String.format("[depth=%d id=%d group=%d bounds=%s] ",
				depth, current.getId(), current.getId() >>> 16, current.getBounds()));
			Widget parent = current.getParent();
			if (parent == null)
			{
				break;
			}
			if ((parent.getId() >>> 16) != COLLECTION_LOG_GROUP_ID)
			{
				// parent has left our interface's group -- current is the last one still inside it
				break;
			}
			current = parent;
			lastInGroup = current;
			depth++;
		}

		Rectangle bounds = lastInGroup.getBounds();
		String chainStr = chain.toString();
		if (!chainStr.equals(lastLoggedChain))
		{
			log.info("[collectionlogluck] clip ancestor chain: {}", chainStr);
			log.info("[collectionlogluck] clip bounds resolved (last widget still in group {}): {}",
				COLLECTION_LOG_GROUP_ID, bounds);
			lastLoggedChain = chainStr;
		}

		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
		{
			return null;
		}
		return bounds;
	}
}
