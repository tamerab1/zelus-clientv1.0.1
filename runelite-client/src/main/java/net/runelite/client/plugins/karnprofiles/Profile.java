package net.runelite.client.plugins.karnprofiles;

/** One saved character (profile.json). Written while playing; shown on the login screen. */
final class Profile
{
	/** what was typed in the login box */
	String username;
	String displayName;
	int combatLevel;
	int totalLevel;
	/** the follower's name when the character was last captured, or null */
	String petName;
	long lastPlayed;
	/** the password, encrypted for this Windows user (DPAPI), base64; null unless "Remember password" was ticked */
	String password;
	/** gear, colours and pet the saved model shows (to know when to capture again) */
	int signature;

	/** not saved: the directory, and the 3D models loaded from mesh.bin */
	transient String key;
	transient ProfileMesh player, pet;

	String name()
	{
		String n = clean(displayName);
		return n != null && !n.isEmpty() ? n : username;
	}

	/**
	 * The plain name: the server sends the player's title and rank icon inside the name
	 * ("&lt;img=97&gt;&lt;col=FF0000&gt;&lt;shad=000000&gt;KARN &lt;/shad&gt;&lt;/col&gt;Mr Boolt33"), so a coloured span (the title, before or
	 * after the name) goes entirely, then any tag left.
	 */
	static String clean(String name)
	{
		if (name == null)
		{
			return null;
		}
		String s = name.replaceAll("(?i)<col=[^>]*>.*?</col>", "").replaceAll("<[^>]*>", "").replace(' ', ' ').trim();
		return s.isEmpty() ? name.replaceAll("<[^>]*>", "").trim() : s;
	}
}
