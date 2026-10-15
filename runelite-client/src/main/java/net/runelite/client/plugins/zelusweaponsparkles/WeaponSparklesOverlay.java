package net.runelite.client.plugins.zelusweaponsparkles;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.kit.KitType;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Gold specks that cling to the Twisted bow (celestial): players wielding it, and NPCs dressed in it (BOW_NPCS).
 * <p>
 * The bow is found by colour: its limbs are recoloured to a navy (hue 43, saturation 4) that the lit face colours keep
 * (lighting only changes lightness), so every visible face with that hue/saturation is part of the bow.
 * <p>
 * Each speck is anchored IN MODEL SPACE: it remembers its owner, a bow face and a point on that face, and is
 * re-projected from the live animated model every frame, so it rides along with the bow while the wielder runs, turns
 * or the camera rotates. A speck only drifts a few pixels off its anchor and lives 0.25-0.4s; it vanishes at once if
 * its owner is gone.
 * <p>
 * Occlusion: overlays draw after the 3D scene and the client exposes no depth buffer, so this does its own depth test.
 * Every speck gets a camera-space depth (interpolated from its face's projected vertices); the models of the static
 * scenery around the wielder (game objects, walls, wall decorations - never actors, projectiles or graphics) are
 * projected with the same camera, and a speck is hidden only where one of their triangles covers that pixel CLOSER to
 * the camera than the speck. (2026-10-15: an earlier version compared object centres and hid every speck behind any big
 * floor-level object in front of the wielder, so the sparkles vanished from some camera angles.)
 */
@Slf4j
class WeaponSparklesOverlay extends Overlay
{
	/** Diagnostics, logged every 5s while a bow is around (2026-10-15 hunt for invisible sparkles). */
	private int dWielders, dBowFaces, dSpawned, dGone, dBehind, dOccluded, dDrawn, dOccluders;
	private long dLast;
	static final int CELESTIAL_TWISTED_BOW = 31778;
	/**
	 * Every celestial weapon (Division Rivals ornament kit, 2026-10-15): Twisted bow, Armadyl godsword, Voidwaker and
	 * Harmonised nightmare staff (celestial). All are recoloured to the same navy (hue 43, saturation 4), which is how
	 * the sparkles find their faces on the model.
	 */
	static boolean isCelestial(int itemId) {
		return itemId == CELESTIAL_TWISTED_BOW || itemId == 31784 || itemId == 31786 || itemId == 31788;
	}
	/** NPCs that wear the bow (a baked copy with the same navy limbs): the Cape Forge Master at home. */
	private static final int[] BOW_NPCS = {3873};
	/** (hsl >> 7) & 0x1ff of the bow's navy limbs: hue 43, saturation 4. */
	private static final int BOW_HUE_SAT = 43 << 3 | 4;
	/**
	 * The Rivals Champion cape (31782, worn by players; baked onto the Rivals Overseer npc 3351): subtle star specks
	 * on its gold trim, which the cache recolour puts on hue 9 / saturation 6 (2026-10-15).
	 */
	static final int CHAMPION_CAPE = 31782, OVERSEER_NPC = 3351;
	/** Zelus Cosmic Wings (donator, cape slot): star motes from the whole indigo wing (recoloured 62/76 -> 47886/47898, zelus_donator_items.txt). */
	static final int COSMIC_WINGS = 31810;
	private static final int WINGS_HUE_SAT = 46 << 3 | 6;
	private static final Color[] STAR_TINTS = {
		new Color(255, 255, 255), new Color(200, 220, 255), new Color(220, 200, 255), new Color(255, 244, 210)
	};
	/** Set while tracking a Cosmic Wings wearer (the wings share the cape slot, so never with the Champion cape). */
	private boolean wingsMode;
	/** The cape's gold: 9/6 is mostly the collar, 8/6 the main trim down to the hem (rivals_items.txt recolours). */
	private static final int CAPE_HUE_SAT = 9 << 3 | 6, CAPE_TRIM_HUE_SAT = 8 << 3 | 6;
	private static final int SPARKS_PER_FRAME = 2;
	private static final int MAX_PARTICLES = 120;
	/** Pixels a speck may drift off its anchor point over its life. */
	private static final float MAX_DRIFT = 5f;
	/** Tiles around the wielder searched for occluding scenery. */
	private static final int OCCLUDER_RADIUS = 6;
	/** A scenery surface must be this much nearer the camera (local units, 128 = a tile) to hide a speck. */
	private static final float DEPTH_TOLERANCE = 24f;
	/** Same near plane as the client's projection. */
	private static final float NEAR = 50f;

	private static final Color[] TINTS = {
		new Color(255, 214, 90), new Color(255, 236, 150), new Color(255, 250, 215), new Color(240, 190, 60)
	};

	private final Client client;
	private final List<Spark> sparks = new ArrayList<>();
	private long lastNanos;

	private static final class Spark
	{
		Actor owner;
		int face;
		float u, v;
		float driftX, driftY, age, life;
		int size;
		Color tint;
	}

	/** A model projected for this frame: screen x/y and camera depth per vertex (depth < NEAR = behind the camera). */
	private static final class Projected
	{
		float[] x, y, depth;
		int[] i1, i2, i3;
		int faceCount;
		/** faceColors1, and faceColors3 (-2 marks a face that is not drawn). */
		int[] colours1, colours3;
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		/** World footprint (local east / north) of the model. */
		float eastMin = Float.MAX_VALUE, eastMax = -Float.MAX_VALUE, northMin = Float.MAX_VALUE, northMax = -Float.MAX_VALUE;
	}

	/** The camera for this frame, captured once. */
	private static final class Camera
	{
		float x, y, z, yawSin, yawCos, pitchSin, pitchCos, midX, midY, offX, offY, scale;
	}

	@Inject
	WeaponSparklesOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	void clear()
	{
		sparks.clear();
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;

		WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return null;
		}
		Camera cam = camera();
		Map<Actor, Projected> wielders = new IdentityHashMap<>();
		Map<Actor, List<Projected>> occluders = new IdentityHashMap<>();
		for (Player player : view.players())
		{
			if (player == null)
			{
				continue;
			}
			boolean bow = wieldsCelestialBow(player), cape = wearsChampionCape(player), wings = wearsCosmicWings(player);
			if (bow || cape || wings)
			{
				dWielders++;
				wingsMode = wings;
				track(cam, view, wielders, occluders, player, player.getFootprintSize(), bow, cape || wings);
				wingsMode = false;
			}
		}
		for (NPC npc : view.npcs())
		{
			boolean bow = npc != null && holdsCelestialBow(npc), cape = npc != null && npc.getId() == OVERSEER_NPC;
			if (bow || cape)
			{
				NPCComposition comp = npc.getTransformedComposition();
				track(cam, view, wielders, occluders, npc, comp == null ? 1 : comp.getFootprintSize(), bow, cape);
			}
		}

		Iterator<Spark> it = sparks.iterator();
		while (it.hasNext())
		{
			Spark s = it.next();
			s.age += dt;
			Projected p = wielders.get(s.owner);
			if (s.age >= s.life || p == null || s.face >= p.faceCount)
			{
				if (p == null)
				{
					dGone++;
				}
				it.remove();
				continue;
			}
			int a = p.i1[s.face], b = p.i2[s.face], c = p.i3[s.face];
			int n = p.depth.length;
			if (a < 0 || b < 0 || c < 0 || a >= n || b >= n || c >= n)
			{
				it.remove();
				continue;
			}
			if (p.depth[a] < NEAR || p.depth[b] < NEAR || p.depth[c] < NEAR)
			{
				dBehind++;
				continue; // behind the camera this frame
			}
			float t = s.age / s.life;
			float x = p.x[a] + s.u * (p.x[b] - p.x[a]) + s.v * (p.x[c] - p.x[a]);
			float y = p.y[a] + s.u * (p.y[b] - p.y[a]) + s.v * (p.y[c] - p.y[a]);
			float depth = p.depth[a] + s.u * (p.depth[b] - p.depth[a]) + s.v * (p.depth[c] - p.depth[a]);
			// the depth test uses the anchor point; the drift is only a few pixels of shimmer
			if (safelyCovered(occluders.get(s.owner), x, y, depth - DEPTH_TOLERANCE))
			{
				dOccluded++;
				continue;
			}
			dDrawn++;
			// fade in quickly, then out
			float alpha = t < 0.2f ? t / 0.2f : 1f - (t - 0.2f) / 0.8f;
			draw(g, x + s.driftX * t, y + s.driftY * t, s, alpha);
		}
		if (now - dLast > 5_000_000_000L)
		{
			if (dWielders > 0 || !sparks.isEmpty())
			{
				log.debug("sparkles 5s: wielderFrames={} bowFaces={} spawned={} ownerGone={} behindCamera={} occluded={} drawn={} occluderModels={} gpu={} cam=({},{},{}) pitch={} yaw={}",
					dWielders, dBowFaces, dSpawned, dGone, dBehind, dOccluded, dDrawn, dOccluders, client.isGpu(),
					client.getCameraX(), client.getCameraY(), client.getCameraZ(), client.getCameraPitch(), client.getCameraYaw());
			}
			dWielders = dBowFaces = dSpawned = dGone = dBehind = dOccluded = dDrawn = dOccluders = 0;
			dLast = now;
		}
		return null;
	}

	private static boolean wieldsCelestialBow(Player player)
	{
		PlayerComposition comp = player.getPlayerComposition();
		return comp != null && isCelestial(comp.getEquipmentId(KitType.WEAPON));
	}

	private static boolean wearsCosmicWings(Player player)
	{
		PlayerComposition comp = player.getPlayerComposition();
		return comp != null && comp.getEquipmentId(KitType.CAPE) == COSMIC_WINGS;
	}

	private static boolean wearsChampionCape(Player player)
	{
		PlayerComposition comp = player.getPlayerComposition();
		return comp != null && comp.getEquipmentId(KitType.CAPE) == CHAMPION_CAPE;
	}

	private static boolean holdsCelestialBow(NPC npc)
	{
		for (int id : BOW_NPCS)
		{
			if (npc.getId() == id)
			{
				return true;
			}
		}
		return false;
	}

	/** Projects the wielder's animated model, gathers the scenery around it and spawns specks on its bow faces. */
	private void track(Camera cam, WorldView view, Map<Actor, Projected> wielders, Map<Actor, List<Projected>> occluders,
		Actor actor, int footprintSize, boolean bow, boolean cape)
	{
		LocalPoint lp = actor.getLocalLocation();
		Model model = actor.getModel();
		if (lp == null || model == null)
		{
			return;
		}
		int height = Perspective.getFootprintTileHeight(client, lp, view.getPlane(), footprintSize);
		Projected p = project(cam, model, lp.getX(), lp.getY(), height, actor.getCurrentOrientation());
		wielders.put(actor, p);

		// bow faces from the projection's own copies: actor.getModel() can hand back the client's shared scratch model,
		// which the scenery models fetched below overwrite (2026-10-15: that hid every speck in GPU / HD mode)
		int[] bowFaces = new int[p.faceCount];
		int found = 0;
		for (int f = 0; f < p.faceCount; f++)
		{
			int hueSat = p.colours1[f] >> 7 & 0x1ff;
			if (p.colours3[f] != -2 && ((bow && hueSat == BOW_HUE_SAT) || (cape && (wingsMode ? hueSat == WINGS_HUE_SAT : (hueSat == CAPE_HUE_SAT || hueSat == CAPE_TRIM_HUE_SAT)))))
			{
				bowFaces[found++] = f;
			}
		}
		if (cape && !bow && !wingsMode && found > 0)
		{
			// the Champion cape emits from its bottom hem / split: keep the trim faces in the lowest 30% of the cape on screen
			float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
			for (int k = 0; k < found; k++)
			{
				int f = bowFaces[k];
				float low = Math.max(p.y[p.i1[f]], Math.max(p.y[p.i2[f]], p.y[p.i3[f]]));
				top = Math.min(top, low);
				bottom = Math.max(bottom, low);
			}
			float cut = top + (bottom - top) * 0.7f;
			int kept = 0;
			for (int k = 0; k < found; k++)
			{
				int f = bowFaces[k];
				if (Math.max(p.y[p.i1[f]], Math.max(p.y[p.i2[f]], p.y[p.i3[f]])) >= cut)
				{
					bowFaces[kept++] = f;
				}
			}
			found = kept;
		}
		dBowFaces += found;
		if (found == 0)
		{
			return;
		}
		try
		{
			List<Projected> occ = scenery(cam, view, lp);
			occluders.put(actor, occ);
			dOccluders += occ.size();
		}
		catch (RuntimeException e)
		{
			occluders.put(actor, new ArrayList<>()); // fail open: sparkles without occlusion
		}
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		// the cape alone is subtler: one speck a frame off its hem (sparse drifting motes)
		int perFrame = bow ? SPARKS_PER_FRAME : 1;
		for (int n = 0; n < perFrame && sparks.size() < MAX_PARTICLES; n++)
		{
			Spark s = new Spark();
			s.owner = actor;
			s.face = bowFaces[rnd.nextInt(found)];
			// random point on the triangle (barycentric), fixed for the speck's life
			float u = rnd.nextFloat(), v = rnd.nextFloat();
			if (u + v > 1f)
			{
				u = 1f - u;
				v = 1f - v;
			}
			s.u = u;
			s.v = v;
			s.driftX = (rnd.nextFloat() - 0.5f) * MAX_DRIFT;
			// bow: a hint of rising, never far from the limb; cape: motes drifting down off the hem
			s.driftY = bow || wingsMode ? -rnd.nextFloat() * MAX_DRIFT : rnd.nextFloat() * MAX_DRIFT; // wings: motes float up
			s.life = 0.25f + rnd.nextFloat() * 0.15f;
			int roll = rnd.nextInt(10);
			s.size = roll < 6 ? 1 : roll < 9 ? 2 : 3;
			s.tint = wingsMode ? STAR_TINTS[rnd.nextInt(STAR_TINTS.length)] : TINTS[rnd.nextInt(TINTS.length)];
			sparks.add(s);
			dSpawned++;
		}
	}

	private Camera camera()
	{
		Camera c = new Camera();
		// the integer camera, which every renderer keeps current (CPU, GPU and 117 HD). The precise "Fp" camera put every
		// speck behind the camera in CPU mode and with HD on (2026-10-15), so it is not used
		int pitch = client.getCameraPitch() & 2047, yaw = client.getCameraYaw() & 2047;
		c.x = client.getCameraX();
		c.y = client.getCameraY();
		c.z = client.getCameraZ();
		c.pitchSin = Perspective.SINE[pitch] / 65536f;
		c.pitchCos = Perspective.COSINE[pitch] / 65536f;
		c.yawSin = Perspective.SINE[yaw] / 65536f;
		c.yawCos = Perspective.COSINE[yaw] / 65536f;
		c.midX = client.getViewportWidth() / 2f;
		c.midY = client.getViewportHeight() / 2f;
		c.offX = client.getViewportXOffset();
		c.offY = client.getViewportYOffset();
		c.scale = client.getScale();
		return c;
	}

	/**
	 * The client's own model projection (as Perspective.modelToCanvas), keeping each vertex's camera depth as well.
	 * x / y are local east / north, height is the model origin's height; rotate is a 0-2047 orientation.
	 */
	private static Projected project(Camera cam, Model model, int x, int y, int height, int rotate)
	{
		float[] vx = model.getVerticesX(), vy = model.getVerticesY(), vz = model.getVerticesZ();
		// the reported counts can exceed the arrays (seen in GPU / HD mode, 2026-10-15): never trust them alone
		int count = Math.min(model.getVerticesCount(), Math.min(vx.length, Math.min(vy.length, vz.length)));
		Projected p = new Projected();
		p.x = new float[count];
		p.y = new float[count];
		p.depth = new float[count];
		// COPIES, never the arrays themselves: animated models live in shared scratch buffers that the next model the
		// client builds overwrites (their arrays are also longer than the face / vertex counts)
		int[] i1 = model.getFaceIndices1(), i2 = model.getFaceIndices2(), i3 = model.getFaceIndices3();
		int[] c1 = model.getFaceColors1(), c3 = model.getFaceColors3();
		int faces = Math.min(model.getFaceCount(), Math.min(i1.length, Math.min(i2.length, i3.length)));
		faces = Math.min(faces, Math.min(c1 == null ? faces : c1.length, c3 == null ? faces : c3.length));
		p.faceCount = faces;
		p.i1 = Arrays.copyOf(i1, faces);
		p.i2 = Arrays.copyOf(i2, faces);
		p.i3 = Arrays.copyOf(i3, faces);
		p.colours1 = c1 == null ? new int[faces] : Arrays.copyOf(c1, faces);
		p.colours3 = c3 == null ? new int[faces] : Arrays.copyOf(c3, faces);
		float rotSin = Perspective.SINE[rotate & 2047] / 65536f, rotCos = Perspective.COSINE[rotate & 2047] / 65536f;
		float cx = x - cam.x, cy = y - cam.y, cz = height - cam.z;
		for (int i = 0; i < count; i++)
		{
			float east = vx[i], north = vz[i], up = vy[i];
			if (rotate != 0)
			{
				float e0 = east;
				east = e0 * rotCos + north * rotSin;
				north = north * rotCos - e0 * rotSin;
			}
			p.eastMin = Math.min(p.eastMin, east + x);
			p.eastMax = Math.max(p.eastMax, east + x);
			p.northMin = Math.min(p.northMin, north + y);
			p.northMax = Math.max(p.northMax, north + y);
			east += cx;
			north += cy;
			up += cz;
			float x1 = east * cam.yawCos + north * cam.yawSin;
			float y1 = north * cam.yawCos - east * cam.yawSin;
			float y2 = up * cam.pitchCos - y1 * cam.pitchSin;
			float z1 = y1 * cam.pitchCos + up * cam.pitchSin;
			p.depth[i] = z1;
			if (z1 < NEAR)
			{
				continue;
			}
			float sx = cam.midX + x1 * cam.scale / z1 + cam.offX;
			float sy = cam.midY + y2 * cam.scale / z1 + cam.offY;
			p.x[i] = sx;
			p.y[i] = sy;
			p.minX = Math.min(p.minX, sx);
			p.maxX = Math.max(p.maxX, sx);
			p.minY = Math.min(p.minY, sy);
			p.maxY = Math.max(p.maxY, sy);
		}
		return p;
	}

	/** Static scenery models (projected) within OCCLUDER_RADIUS tiles of the wielder. */
	private static List<Projected> scenery(Camera cam, WorldView view, LocalPoint actor)
	{
		List<Projected> out = new ArrayList<>();
		Scene scene = view.getScene();
		if (scene == null)
		{
			return out;
		}
		Tile[][] tiles = scene.getTiles()[view.getPlane()];
		int sx = actor.getSceneX(), sy = actor.getSceneY();
		for (int x = Math.max(0, sx - OCCLUDER_RADIUS); x <= Math.min(tiles.length - 1, sx + OCCLUDER_RADIUS); x++)
		{
			for (int y = Math.max(0, sy - OCCLUDER_RADIUS); y <= Math.min(tiles[x].length - 1, sy + OCCLUDER_RADIUS); y++)
			{
				Tile tile = tiles[x][y];
				if (tile == null)
				{
					continue;
				}
				for (GameObject object : tile.getGameObjects())
				{
					// once per object (big objects sit on several tiles)
					if (object != null && object.getSceneMinLocation().equals(tile.getSceneLocation()))
					{
						add(out, cam, actor, object.getRenderable(), object.getX(), object.getY(), object.getZ(),
							object.getModelOrientation());
					}
				}
				WallObject wall = tile.getWallObject();
				if (wall != null)
				{
					add(out, cam, actor, wall.getRenderable1(), wall.getX(), wall.getY(), wall.getZ(), 0);
					add(out, cam, actor, wall.getRenderable2(), wall.getX(), wall.getY(), wall.getZ(), 0);
				}
				DecorativeObject decoration = tile.getDecorativeObject();
				if (decoration != null)
				{
					int dx = decoration.getX() + decoration.getXOffset(), dy = decoration.getY() + decoration.getYOffset();
					add(out, cam, actor, decoration.getRenderable(), dx, dy, decoration.getZ(), 0);
					add(out, cam, actor, decoration.getRenderable2(), decoration.getX(), decoration.getY(), decoration.getZ(), 0);
				}
			}
		}
		return out;
	}

	/** Adds a scenery renderable: only plain / animated object models, never actors, projectiles or graphics. */
	private static void add(List<Projected> out, Camera cam, LocalPoint wielder, Renderable renderable, int x, int y, int z,
		int rotate)
	{
		Model model = renderable instanceof Model ? (Model) renderable
			: renderable instanceof DynamicObject ? renderable.getModel() : null;
		if (model != null && model.getFaceCount() > 0)
		{
			Projected p = project(cam, model, x, y, z, rotate);
			// a model whose footprint contains the wielder (roof, platform, building shell, arch) is one he stands
			// in or under: it isn't drawn between him and the camera the way a statue in front is (2026-10-15: at
			// steep camera angles such models "covered" the bow and hid every speck)
			if (!(wielder.getX() > p.eastMin && wielder.getX() < p.eastMax
				&& wielder.getY() > p.northMin && wielder.getY() < p.northMax))
			{
				out.add(p);
			}
		}
	}

	/** The occlusion test fails open: any problem with a scenery model just leaves the speck visible. */
	private static boolean safelyCovered(List<Projected> scenery, float px, float py, float maxDepth)
	{
		try
		{
			return covered(scenery, px, py, maxDepth);
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	/** True if a scenery triangle covers the pixel at a camera depth below maxDepth. */
	private static boolean covered(List<Projected> scenery, float px, float py, float maxDepth)
	{
		if (scenery == null)
		{
			return false;
		}
		for (Projected m : scenery)
		{
			if (px < m.minX || px > m.maxX || py < m.minY || py > m.maxY)
			{
				continue;
			}
			for (int f = 0; f < m.faceCount; f++)
			{
				if (m.colours3[f] == -2)
				{
					continue;
				}
				int a = m.i1[f], b = m.i2[f], c = m.i3[f];
				int n = m.depth.length;
				if (a < 0 || b < 0 || c < 0 || a >= n || b >= n || c >= n)
				{
					continue;
				}
				float da = m.depth[a], db = m.depth[b], dc = m.depth[c];
				if (da < NEAR || db < NEAR || dc < NEAR || (da >= maxDepth && db >= maxDepth && dc >= maxDepth))
				{
					continue;
				}
				float ax = m.x[a], ay = m.y[a];
				float e1x = m.x[b] - ax, e1y = m.y[b] - ay, e2x = m.x[c] - ax, e2y = m.y[c] - ay;
				float det = e1x * e2y - e2x * e1y;
				if (Math.abs(det) < 1e-3f)
				{
					continue;
				}
				float qx = px - ax, qy = py - ay;
				float u = (qx * e2y - e2x * qy) / det;
				float v = (e1x * qy - qx * e1y) / det;
				if (u < 0 || v < 0 || u + v > 1)
				{
					continue;
				}
				// perspective-correct depth at the pixel: interpolate 1/depth
				float inv = (1 - u - v) / da + u / db + v / dc;
				if (1f / inv < maxDepth)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static void draw(Graphics2D g, float fx, float fy, Spark s, float alpha)
	{
		int x = Math.round(fx), y = Math.round(fy);
		int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
		Color c = s.tint;
		if (s.size >= 3)
		{
			// tiny cross-shaped glint with a soft halo
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), a / 3));
			g.fillRect(x - 1, y - 1, 3, 3);
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), a));
			g.fillRect(x - 1, y, 3, 1);
			g.fillRect(x, y - 1, 1, 3);
		}
		else
		{
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), a));
			g.fillRect(x, y, s.size, s.size);
		}
	}
}
