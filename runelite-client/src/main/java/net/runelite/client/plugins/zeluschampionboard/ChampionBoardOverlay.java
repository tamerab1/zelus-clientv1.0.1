package net.runelite.client.plugins.zeluschampionboard;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Writes the live top 5 onto the Championship Board's painted rows.
 * <p>
 * The board face (model 60132, .dev/cache-restore-tool BuildChampionshipBoard) is a flat quad at model x = 4 spanning
 * z -140..140 and y -384..-166, painted as a 1120 x 872 image (4 px per model unit; painted x runs with +z, painted y
 * down). A painted point (px, py) is therefore the model point (4, -384 + py / 4, -140 + px / 4). It is scaled by the
 * object's model scale (108 / 128), turned by the object's orientation and projected with the client camera; each
 * string is drawn through the affine map of the board plane at that point, so it lies flat on the parchment at any
 * camera angle, and only while the face is turned toward the camera.
 */
class ChampionBoardOverlay extends Overlay
{
	private static final int BOARD_OBJECT = 44930, DATA_GROUP = 1170, ROWS = 5, POINTS = 5;
	private static final double SCALE = 108 / 128.0, FACE_X = 5, PAINT_W = 1120, PAINT_H = 872;
	/** The painted table: first row at y 508, rows 70 apart; names from x 200, points end at x 878. */
	private static final double ROW_Y = 508, ROW_H = 70, NAME_X = 200, POINTS_RIGHT = 878, BASELINE = 44;
	private static final int MAX_DISTANCE = 24 * 128;
	private static final Font NAME_FONT = new Font("Georgia", Font.BOLD, 36), POINTS_FONT = new Font("Georgia", Font.BOLD, 38);
	private static final Color INK = new Color(0x2a1a08), POINTS_INK = new Color(0x1d5a1d), HIGHLIGHT = new Color(255, 245, 220, 110);

	private final Client client;

	@Inject
	ChampionBoardOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		String[] names = new String[ROWS], points = new String[ROWS];
		boolean any = false;
		for (int i = 0; i < ROWS; i++)
		{
			Widget n = client.getWidget(DATA_GROUP, i), p = client.getWidget(DATA_GROUP, POINTS + i);
			names[i] = n == null ? "" : n.getText();
			points[i] = p == null ? "" : p.getText();
			any |= names[i] != null && !names[i].isEmpty();
		}
		if (!any)
		{
			return null;
		}
		GameObject board = findBoard();
		if (board == null)
		{
			return null;
		}
		Board b = new Board(client, board);
		if (!b.facesCamera())
		{
			return null;
		}
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		for (int i = 0; i < ROWS; i++)
		{
			if (names[i] == null || names[i].isEmpty())
			{
				continue;
			}
			double baseline = ROW_Y + i * ROW_H + BASELINE;
			draw(g, b, names[i], NAME_FONT, INK, NAME_X, baseline, false);
			draw(g, b, points[i], POINTS_FONT, POINTS_INK, POINTS_RIGHT, baseline, true);
		}
		return null;
	}

	private static void draw(Graphics2D g, Board b, String text, Font font, Color colour, double px, double py, boolean rightAligned)
	{
		if (text == null || text.isEmpty())
		{
			return;
		}
		AffineTransform plane = b.planeAt(px, py);
		if (plane == null)
		{
			return;
		}
		AffineTransform keep = g.getTransform();
		g.transform(plane);
		g.setFont(font);
		double w = g.getFontMetrics().stringWidth(text);
		float x = (float) (rightAligned ? -w : 0);
		// keep long names inside the row: shrink to fit the 650 px name slot
		if (!rightAligned && w > 650)
		{
			g.scale(650 / w, 1);
		}
		g.setColor(HIGHLIGHT);
		g.drawString(text, x + 2, 2);
		g.setColor(colour);
		g.drawString(text, x, 0);
		g.setTransform(keep);
	}

	private GameObject findBoard()
	{
		WorldView view = client.getTopLevelWorldView();
		Scene scene = view == null ? null : view.getScene();
		if (scene == null)
		{
			return null;
		}
		Tile[][] tiles = scene.getTiles()[view.getPlane()];
		for (Tile[] column : tiles)
		{
			for (Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				for (GameObject object : tile.getGameObjects())
				{
					if (object != null && object.getId() == BOARD_OBJECT
						&& object.getSceneMinLocation().equals(tile.getSceneLocation()))
					{
						int dx = object.getX() - client.getCameraX(), dy = object.getY() - client.getCameraY();
						return (long) dx * dx + (long) dy * dy <= (long) MAX_DISTANCE * MAX_DISTANCE ? object : null;
					}
				}
			}
		}
		return null;
	}

	/** The board's placement and the camera for this frame. */
	private static final class Board
	{
		final double ox, oy, oz, rotSin, rotCos;
		final double camX, camY, camZ, pitchSin, pitchCos, yawSin, yawCos, midX, midY, offX, offY, scale;

		Board(Client client, GameObject object)
		{
			ox = object.getX();
			oy = object.getY();
			oz = object.getZ();
			int rotate = object.getModelOrientation() & 2047;
			rotSin = Perspective.SINE[rotate] / 65536.0;
			rotCos = Perspective.COSINE[rotate] / 65536.0;
			camX = client.getCameraX();
			camY = client.getCameraY();
			camZ = client.getCameraZ();
			int pitch = client.getCameraPitch() & 2047, yaw = client.getCameraYaw() & 2047;
			pitchSin = Perspective.SINE[pitch] / 65536.0;
			pitchCos = Perspective.COSINE[pitch] / 65536.0;
			yawSin = Perspective.SINE[yaw] / 65536.0;
			yawCos = Perspective.COSINE[yaw] / 65536.0;
			midX = client.getViewportWidth() / 2.0;
			midY = client.getViewportHeight() / 2.0;
			offX = client.getViewportXOffset();
			offY = client.getViewportYOffset();
			scale = client.getScale();
		}

		/** Model point -> world local (east, north, height). */
		double[] world(double mx, double my, double mz)
		{
			mx *= SCALE;
			my *= SCALE;
			mz *= SCALE;
			double east = mx * rotCos + mz * rotSin, north = mz * rotCos - mx * rotSin;
			return new double[]{ox + east, oy + north, oz + my};
		}

		/** Painted point -> screen, or null behind the camera. */
		double[] screen(double px, double py)
		{
			double[] w = world(FACE_X, -384 + py / 4, -140 + px / 4);
			double x = w[0] - camX, y = w[1] - camY, z = w[2] - camZ;
			double x1 = x * yawCos + y * yawSin, y1 = y * yawCos - x * yawSin;
			double y2 = z * pitchCos - y1 * pitchSin, depth = y1 * pitchCos + z * pitchSin;
			if (depth < 50)
			{
				return null;
			}
			return new double[]{midX + x1 * scale / depth + offX, midY + y2 * scale / depth + offY};
		}

		/** The face's normal is model +x: visible when the camera is on that side of the plane. */
		boolean facesCamera()
		{
			double[] p = world(FACE_X, -275, 0), n = world(FACE_X + 100, -275, 0);
			double nx = n[0] - p[0], ny = n[1] - p[1];
			return nx * (camX - p[0]) + ny * (camY - p[1]) > 0;
		}

		/** Affine map from painted units around (px, py) to the screen (exact at that point, linear nearby). */
		AffineTransform planeAt(double px, double py)
		{
			double[] o = screen(px, py), u = screen(px + 100, py), v = screen(px, py + 100);
			if (o == null || u == null || v == null)
			{
				return null;
			}
			return new AffineTransform((u[0] - o[0]) / 100, (u[1] - o[1]) / 100, (v[0] - o[0]) / 100, (v[1] - o[1]) / 100, o[0], o[1]);
		}
	}
}
