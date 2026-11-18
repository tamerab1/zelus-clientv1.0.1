package net.runelite.client.plugins.karnprofiles;

import com.google.gson.Gson;
import com.sun.jna.platform.win32.Crypt32Util;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * ~/.karn/profiles/&lt;key&gt;/profile.json + mesh.bin (gzip). The key is a hash of the lower-cased username, so an e-mail
 * login never ends up in a folder name. Passwords are only stored when the player ticks "Remember password", and then
 * encrypted with Windows' per-user DPAPI: only this Windows account on this PC can read them back.
 */
@Slf4j
final class ProfileStore
{
	private final File root;
	private final Gson gson;

	ProfileStore(File root, Gson gson)
	{
		this.root = root;
		this.gson = gson;
	}

	static String key(String username)
	{
		try
		{
			byte[] d = MessageDigest.getInstance("SHA-256").digest(username.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
			StringBuilder s = new StringBuilder();
			for (int i = 0; i < 10; i++)
			{
				s.append(String.format("%02x", d[i]));
			}
			return s.toString();
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** Every saved profile with its models, most recently played first. */
	List<Profile> loadAll()
	{
		List<Profile> out = new ArrayList<>();
		File[] dirs = root.listFiles(File::isDirectory);
		if (dirs == null)
		{
			return out;
		}
		for (File d : dirs)
		{
			Profile p = load(d.getName(), true);
			if (p != null)
			{
				out.add(p);
			}
		}
		out.sort(Comparator.comparingLong((Profile p) -> p.lastPlayed).reversed());
		return out;
	}

	Profile load(String key, boolean withMesh)
	{
		File dir = new File(root, key), json = new File(dir, "profile.json");
		if (!json.isFile())
		{
			return null;
		}
		try
		{
			Profile p = gson.fromJson(Files.readString(json.toPath()), Profile.class);
			if (p == null || p.username == null)
			{
				return null;
			}
			p.key = key;
			if (withMesh)
			{
				readMesh(p, new File(dir, "mesh.bin"));
			}
			return p;
		}
		catch (Exception e)
		{
			log.warn("unreadable profile {}", key, e);
			return null;
		}
	}

	void save(Profile p)
	{
		File dir = new File(root, p.key);
		dir.mkdirs();
		write(new File(dir, "profile.json"), gson.toJson(p).getBytes(StandardCharsets.UTF_8));
	}

	void saveMesh(Profile p, ProfileMesh player, ProfileMesh pet)
	{
		File dir = new File(root, p.key);
		dir.mkdirs();
		try
		{
			File tmp = new File(dir, "mesh.bin.tmp");
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp.toPath())))))
			{
				player.write(out);
				out.writeBoolean(pet != null);
				if (pet != null)
				{
					pet.write(out);
				}
			}
			Files.move(tmp.toPath(), new File(dir, "mesh.bin").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException e)
		{
			log.warn("couldn't save the model of profile {}", p.key, e);
		}
	}

	private static void readMesh(Profile p, File f)
	{
		if (!f.isFile())
		{
			return;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f.toPath())))))
		{
			p.player = ProfileMesh.read(in);
			p.pet = in.readBoolean() ? ProfileMesh.read(in) : null;
		}
		catch (IOException e)
		{
			log.warn("unreadable model in profile {}", p.key, e);
			p.player = p.pet = null;
		}
	}

	void delete(Profile p)
	{
		File dir = new File(root, p.key);
		File[] files = dir.listFiles();
		if (files != null)
		{
			for (File f : files)
			{
				f.delete();
			}
		}
		dir.delete();
	}

	private static void write(File f, byte[] data)
	{
		try
		{
			File tmp = new File(f.getPath() + ".tmp");
			Files.write(tmp.toPath(), data);
			Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException e)
		{
			log.warn("couldn't write {}", f, e);
		}
	}

	static boolean canRememberPasswords()
	{
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	}

	/** Encrypted for this Windows user, base64; null when it can't be. */
	static String encrypt(String password)
	{
		if (!canRememberPasswords() || password == null || password.isEmpty())
		{
			return null;
		}
		try
		{
			return Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(password.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Throwable t)
		{
			log.warn("couldn't encrypt the password", t);
			return null;
		}
	}

	static String decrypt(String stored)
	{
		if (stored == null || !canRememberPasswords())
		{
			return null;
		}
		try
		{
			return new String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(stored)), StandardCharsets.UTF_8);
		}
		catch (Throwable t)
		{
			log.warn("couldn't decrypt the saved password", t);
			return null;
		}
	}
}
