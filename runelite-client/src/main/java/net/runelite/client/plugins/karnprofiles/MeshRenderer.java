package net.runelite.client.plugins.karnprofiles;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Draws a {@link ProfileMesh}'s frames into images, once, so the login screen only blits them. A small software
 * rasteriser: the model turned a little towards the viewer and seen from slightly above, faces painted far to near.
 * Model space is the engine's: y grows downwards (feet at y = 0), the front faces -z.
 */
final class MeshRenderer
{
	private MeshRenderer()
	{
	}

	/** turned this far (radians) so it reads as 3D, and seen from this far above */
	static final double YAW = Math.toRadians(-18), PITCH = Math.toRadians(-8);

	/** The frames as images on one shared canvas, and where the feet (model origin) land on it. */
	static final class Frames
	{
		final List<BufferedImage> images = new ArrayList<>();
		int anchorX, anchorY;
	}

	/** The mesh's height in model units (from its first frame), to size several meshes with one scale. */
	static int height(ProfileMesh m)
	{
		if (m == null || m.frames.isEmpty())
		{
			return 0;
		}
		int[] v = m.frames.get(0);
		int min = 0;
		for (int i = 0; i < m.vertexCount; i++)
		{
			min = Math.min(min, v[i * 3 + 1]);
		}
		return -min;
	}

	/** Every frame at {@code scale} pixels per model unit. */
	static Frames render(ProfileMesh m, double scale)
	{
		Frames out = new Frames();
		if (m == null || m.frames.isEmpty())
		{
			return out;
		}
		double cy = Math.cos(YAW), sy = Math.sin(YAW), cp = Math.cos(PITCH), sp = Math.sin(PITCH);
		int n = m.vertexCount;
		List<double[][]> projected = new ArrayList<>();
		double minX = 0, maxX = 0, minY = 0, maxY = 0;
		for (int[] v : m.frames)
		{
			double[] px = new double[n], py = new double[n], pz = new double[n];
			for (int i = 0; i < n; i++)
			{
				double x = v[i * 3], y = v[i * 3 + 1], z = v[i * 3 + 2];
				double x1 = x * cy + z * sy, z1 = -x * sy + z * cy;
				double y1 = y * cp - z1 * sp, z2 = y * sp + z1 * cp;
				px[i] = x1 * scale;
				py[i] = y1 * scale;
				pz[i] = z2;
				minX = Math.min(minX, px[i]);
				maxX = Math.max(maxX, px[i]);
				minY = Math.min(minY, py[i]);
				maxY = Math.max(maxY, py[i]);
			}
			projected.add(new double[][]{px, py, pz});
		}
		int pad = 2;
		int w = (int) Math.ceil(maxX - minX) + pad * 2, h = (int) Math.ceil(maxY - minY) + pad * 2;
		out.anchorX = (int) Math.round(-minX) + pad;
		out.anchorY = (int) Math.round(-minY) + pad;

		Integer[] order = new Integer[m.faceCount];
		double[] depth = new double[m.faceCount];
		for (double[][] p : projected)
		{
			double[] px = p[0], py = p[1], pz = p[2];
			for (int f = 0; f < m.faceCount; f++)
			{
				order[f] = f;
				depth[f] = pz[m.a[f]] + pz[m.b[f]] + pz[m.c[f]];
			}
			// far (large z) first
			Arrays.sort(order, (i, j) -> Double.compare(depth[j], depth[i]));
			BufferedImage img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			int[] xs = new int[3], ys = new int[3];
			Polygon tri = new Polygon(xs, ys, 3);
			for (int f : order)
			{
				int rgb = m.rgb[f];
				if (rgb == -1)
				{
					continue;
				}
				int a = m.a[f], b = m.b[f], c = m.c[f];
				tri.xpoints[0] = (int) Math.round(px[a] + out.anchorX);
				tri.ypoints[0] = (int) Math.round(py[a] + out.anchorY);
				tri.xpoints[1] = (int) Math.round(px[b] + out.anchorX);
				tri.ypoints[1] = (int) Math.round(py[b] + out.anchorY);
				tri.xpoints[2] = (int) Math.round(px[c] + out.anchorX);
				tri.ypoints[2] = (int) Math.round(py[c] + out.anchorY);
				tri.invalidate();
				int alpha = m.alpha[f];
				g.setComposite(alpha >= 255 ? AlphaComposite.SrcOver : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha / 255f));
				Color col = new Color(rgb);
				g.setColor(col);
				g.fillPolygon(tri);
				// the edges too, or hairline cracks show between neighbouring faces
				g.drawPolygon(tri);
			}
			g.dispose();
			out.images.add(img);
		}
		return out;
	}
}
