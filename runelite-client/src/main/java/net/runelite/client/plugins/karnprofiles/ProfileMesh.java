package net.runelite.client.plugins.karnprofiles;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Model;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;

/**
 * A character's 3D shape as the game drew it, saved so the login screen can show it before logging in: the faces (three
 * vertex indices, a finished RGB colour and an alpha each) and one vertex array per captured frame of the idle loop.
 * The colours are worked out at capture time from the lit model (the engine's HSL palette, textures as their average
 * colour), so the login screen needs nothing from the cache.
 */
final class ProfileMesh
{
	static final int VERSION = 1;

	int vertexCount;
	int faceCount;
	int[] a, b, c;
	/** RGB per face, -1 = hidden */
	int[] rgb;
	/** 255 = opaque */
	int[] alpha;
	final List<int[]> frames = new ArrayList<>();

	/** The model's faces and colours (from its first captured frame). The model's arrays are copied at once. */
	static ProfileMesh faces(Model m, TextureProvider textures)
	{
		if (textures != null)
		{
			Palette.build(textures.getBrightness()); // the player's own brightness setting
		}
		ProfileMesh p = new ProfileMesh();
		p.vertexCount = m.getVerticesCount();
		p.faceCount = m.getFaceCount();
		p.a = copy(m.getFaceIndices1(), p.faceCount);
		p.b = copy(m.getFaceIndices2(), p.faceCount);
		p.c = copy(m.getFaceIndices3(), p.faceCount);
		int[] c1 = m.getFaceColors1(), c2 = m.getFaceColors2(), c3 = m.getFaceColors3();
		short[] tex = m.getFaceTextures();
		byte[] trans = m.getFaceTransparencies();
		p.rgb = new int[p.faceCount];
		p.alpha = new int[p.faceCount];
		for (int f = 0; f < p.faceCount; f++)
		{
			int col3 = c3[f];
			p.alpha[f] = trans == null ? 255 : 255 - (trans[f] & 0xff);
			if (col3 == -2 || p.alpha[f] <= 0)
			{
				p.rgb[f] = -1;
				continue;
			}
			int t = tex == null ? -1 : tex[f];
			if (t != -1)
			{
				// a lit textured face carries a lightness per vertex (0-127) instead of a colour
				int lum = col3 == -1 ? c1[f] : (c1[f] + c2[f] + col3) / 3;
				p.rgb[f] = shade(textureColour(textures, t), Math.max(0, Math.min(127, lum)) / 96.0);
			}
			else if (col3 == -1)
			{
				p.rgb[f] = Palette.rgb(c1[f]);
			}
			else
			{
				p.rgb[f] = average(Palette.rgb(c1[f]), Palette.rgb(c2[f]), Palette.rgb(col3));
			}
		}
		return p;
	}

	/** Adds the model's current vertices as a frame; false when it no longer matches these faces. */
	boolean addFrame(Model m)
	{
		if (m.getVerticesCount() != vertexCount || m.getFaceCount() != faceCount)
		{
			return false;
		}
		float[] x = m.getVerticesX(), y = m.getVerticesY(), z = m.getVerticesZ();
		int[] v = new int[vertexCount * 3];
		for (int i = 0; i < vertexCount; i++)
		{
			v[i * 3] = Math.round(x[i]);
			v[i * 3 + 1] = Math.round(y[i]);
			v[i * 3 + 2] = Math.round(z[i]);
		}
		frames.add(v);
		return true;
	}

	void write(DataOutputStream out) throws IOException
	{
		out.writeInt(VERSION);
		out.writeInt(vertexCount);
		out.writeInt(faceCount);
		for (int f = 0; f < faceCount; f++)
		{
			out.writeInt(a[f]);
			out.writeInt(b[f]);
			out.writeInt(c[f]);
			out.writeInt(rgb[f]);
			out.writeByte(alpha[f]);
		}
		out.writeInt(frames.size());
		for (int[] v : frames)
		{
			for (int i : v)
			{
				out.writeShort(i);
			}
		}
	}

	static ProfileMesh read(DataInputStream in) throws IOException
	{
		if (in.readInt() != VERSION)
		{
			throw new IOException("unknown mesh version");
		}
		ProfileMesh p = new ProfileMesh();
		p.vertexCount = in.readInt();
		p.faceCount = in.readInt();
		p.a = new int[p.faceCount];
		p.b = new int[p.faceCount];
		p.c = new int[p.faceCount];
		p.rgb = new int[p.faceCount];
		p.alpha = new int[p.faceCount];
		for (int f = 0; f < p.faceCount; f++)
		{
			p.a[f] = in.readInt();
			p.b[f] = in.readInt();
			p.c[f] = in.readInt();
			p.rgb[f] = in.readInt();
			p.alpha[f] = in.readUnsignedByte();
		}
		int n = in.readInt();
		for (int k = 0; k < n; k++)
		{
			int[] v = new int[p.vertexCount * 3];
			for (int i = 0; i < v.length; i++)
			{
				v[i] = in.readShort();
			}
			p.frames.add(v);
		}
		return p;
	}

	private static int[] copy(int[] s, int n)
	{
		int[] d = new int[n];
		System.arraycopy(s, 0, d, 0, n);
		return d;
	}

	private static int average(int x, int y, int z)
	{
		int r = (((x >> 16) & 0xff) + ((y >> 16) & 0xff) + ((z >> 16) & 0xff)) / 3;
		int g = (((x >> 8) & 0xff) + ((y >> 8) & 0xff) + ((z >> 8) & 0xff)) / 3;
		int b = ((x & 0xff) + (y & 0xff) + (z & 0xff)) / 3;
		return r << 16 | g << 8 | b;
	}

	private static int shade(int rgb, double k)
	{
		int r = (int) Math.min(255, ((rgb >> 16) & 0xff) * k), g = (int) Math.min(255, ((rgb >> 8) & 0xff) * k),
			b = (int) Math.min(255, (rgb & 0xff) * k);
		return r << 16 | g << 8 | b;
	}

	private static int textureColour(TextureProvider provider, int id)
	{
		if (provider == null)
		{
			return 0x808080;
		}
		try
		{
			// the pixels of a texture the scene hasn't drawn yet aren't there (the GPU renderer keeps its own copy): load them
			Texture[] all = provider.getTextures();
			Texture t = all == null || id >= all.length ? null : all[id];
			int[] px = t == null ? null : t.getPixels();
			if (px == null || px.length == 0)
			{
				px = provider.load(id);
			}
			if (px != null && px.length > 0)
			{
				long r = 0, g = 0, b = 0, n = 0;
				for (int p : px)
				{
					if ((p & 0xffffff) == 0)
					{
						continue;
					}
					r += (p >> 16) & 0xff;
					g += (p >> 8) & 0xff;
					b += p & 0xff;
					n++;
				}
				if (n > 0)
				{
					return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
				}
			}
		}
		catch (RuntimeException ignored)
		{
			// fall through to the texture's own average colour
		}
		try
		{
			return Palette.rgb(provider.getDefaultColor(id));
		}
		catch (RuntimeException ignored)
		{
			return 0x808080;
		}
	}
}
