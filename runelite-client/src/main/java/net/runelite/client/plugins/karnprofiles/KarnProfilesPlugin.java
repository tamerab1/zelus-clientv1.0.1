package net.runelite.client.plugins.karnprofiles;

import com.google.gson.Gson;
import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.LoginScreenPainter;

/**
 * Karn character picker (2026-11-11). While playing, the client keeps a profile per account on this PC
 * (~/.karn/profiles): name, combat and total level, pet, last played and the character's 3D shape in its current gear
 * with one idle loop (and the pet's, if one follows). On the login screen the saved characters stand on the floor;
 * clicking one opens its panel (Play / Delete / Back), "+ New profile" opens the normal login box. Play logs in with the
 * saved password when the player ticked "Remember password" (encrypted per Windows user), otherwise it fills the
 * username and puts the cursor in the password box. The server is not involved.
 */
@Slf4j
@PluginDescriptor(
	name = "Karn Profiles",
	description = "Saved characters on the login screen",
	tags = {"karn", "login", "profiles", "characters"},
	enabledByDefault = true,
	hidden = true
)
public class KarnProfilesPlugin extends Plugin
{
	enum Mode
	{
		PICKER, DETAIL, FORM
	}

	/**
	 * The Login class (ZelusLoginScreenPlugin's LOGIN_STATE_CLASS). Rev 241 keeps the password in a String[] behind an
	 * invokedynamic constant (no plain field); {@code ar.fz()} returns it (client.setPassword -> ar.zm writes it).
	 * Checked at startup with a marker round trip, so a client update that moves it just turns "Remember" off.
	 */
	private static final String LOGIN_CLASS = "ar", PASSWORD_GETTER = "fz";
	/** client ticks (20 ms) between kept frames, and the cap on an idle loop */
	private static final int FRAME_EVERY = 2, MAX_FRAMES = 30, MAX_CAPTURE_TICKS = 160;
	/** a capture cut short by logging out is still kept when it has at least this many frames */
	private static final int MIN_FRAMES = 4;
	/** game ticks between profile.json updates while playing */
	private static final int STATS_EVERY = 25;
	/** game ticks after login before the first capture (the gear and pet appear a moment later), and between re-captures */
	private static final int CAPTURE_DELAY = 1, RECAPTURE_EVERY = 200;

	@Inject
	Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private MouseManager mouseManager;
	@Inject
	private KeyManager keyManager;
	@Inject
	private Gson gson;

	ProfileStore store;
	volatile List<Profile> profiles = new ArrayList<>();
	volatile Mode mode = Mode.FORM;
	volatile Profile selected;
	volatile boolean rememberTicked;
	/** Delete asks twice: the profile whose Delete was clicked once */
	volatile Profile deleteArmed;
	final ExecutorService renderer = Executors.newSingleThreadExecutor(r -> new Thread(r, "karn-profile-render"));
	private PickerPainter painter;
	private java.util.function.Consumer<java.awt.Graphics2D> paintHook;

	// ---- the Karn login form (replaces the OSRS login box) ----
	final StringBuilder formUser = new StringBuilder(), formPass = new StringBuilder();
	/** 0 = username, 1 = password */
	volatile int formFocus;
	/** our own message (empty fields); the server's answers come from the client's login lines */
	volatile String formNote;
	private static final int MAX_USER = 64, MAX_PASS = 64;
	/** the client's three login message lines (ZelusLoginScreenPlugin.LOGIN_MESSAGE_FIELDS) */
	private static final String[] LOGIN_LINES = {"cg", "ct", "co"};
	private java.lang.reflect.Field[] loginLines;
	/** the last login attempt came back to the login screen (wrong password, ...); cleared by typing or a new attempt */
	volatile boolean attemptFailed;

	// ---- login bookkeeping ----
	private java.lang.reflect.Method passwordGetter;
	private boolean passwordFieldSearched;
	private String pendingUser, pendingPassword;
	/** TRUE / FALSE from the login box's tick box, null = logged in through Play (keep what's saved) */
	private Boolean pendingRemember;
	private GameState lastState;
	private Profile current;
	private int statsTimer, ticksIn, ticksSinceCapture, stillTicks;
	private net.runelite.api.coords.WorldPoint lastSpot, capSpot;

	// ---- capture ----
	private ProfileMesh capPlayer, capPet;
	private boolean capPetDone;
	private int capTicks, capStartFrame = Integer.MIN_VALUE, capLastFrame = Integer.MIN_VALUE, capSignature;
	private boolean capWrapped;

	@Override
	protected void startUp()
	{
		store = new ProfileStore(new File(RuneLite.RUNELITE_DIR.getParentFile(), "profiles"), gson);
		painter = new PickerPainter(this);
		reload();
		paintHook = painter::paint;
		LoginScreenPainter.set(paintHook);
		mouseManager.registerMouseListener(mouse);
		keyManager.registerKeyListener(keys);
	}

	@Override
	protected void shutDown()
	{
		LoginScreenPainter.clear(paintHook);
		mouseManager.unregisterMouseListener(mouse);
		keyManager.unregisterKeyListener(keys);
	}

	/** Profiles from disk; back to the picker (or the plain login box when there are none). */
	void reload()
	{
		List<Profile> all = store.loadAll();
		profiles = all;
		selected = null;
		deleteArmed = null;
		mode = all.isEmpty() ? Mode.FORM : Mode.PICKER;
		painter.invalidate();
	}

	// =========================================================== game state

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState s = e.getGameState();
		if (s == GameState.LOGIN_SCREEN && lastState == GameState.LOGGING_IN)
		{
			attemptFailed = true;
			pendingUser = null;
			pendingPassword = null;
			clientThread.invokeLater(() -> log.info("Karn profiles: login failed, login index {}, message \"{}\"",
				client.getLoginIndex(), loginMessage()));
		}
		if (s == GameState.LOGIN_SCREEN)
		{
			if (lastState == GameState.LOGGED_IN || lastState == GameState.LOADING || lastState == GameState.HOPPING)
			{
				if (current != null)
				{
					current.lastPlayed = System.currentTimeMillis();
					store.save(current);
				}
				if (capPlayer != null && capPlayer.frames.size() >= MIN_FRAMES && current != null)
				{
					finishCapture(current);
				}
				current = null;
				stopCapture();
				renderer.submit(this::reload); // after any save still queued there (a capture finished just now)
			}
			findPasswordField();
		}
		else if (s == GameState.LOGGING_IN && lastState == GameState.LOGIN_SCREEN && (pendingUser == null || pendingUser.isEmpty()))
		{
			pendingUser = client.getUsername();
			pendingPassword = readPassword();
			pendingRemember = mode == Mode.FORM ? rememberTicked : null;
		}
		else if (s == GameState.LOGGED_IN && current == null)
		{
			if (pendingUser == null || pendingUser.isEmpty())
			{
				// no LOGGING_IN seen from the login screen: the login box still holds what was used
				pendingUser = client.getUsername();
				pendingPassword = readPassword();
				pendingRemember = mode == Mode.FORM ? rememberTicked : null;
			}
			if (pendingUser != null && !pendingUser.isEmpty())
			{
				loggedIn();
			}
			else
			{
				log.warn("Karn profiles: logged in but the username is empty, no profile kept");
			}
		}
		lastState = s;
	}

	private void loggedIn()
	{
		String key = ProfileStore.key(pendingUser);
		Profile p = store.load(key, false);
		if (p == null)
		{
			p = new Profile();
			p.key = key;
			p.username = pendingUser.trim();
		}
		if (Boolean.TRUE.equals(pendingRemember))
		{
			String enc = ProfileStore.encrypt(pendingPassword);
			if (enc != null)
			{
				p.password = enc;
			}
		}
		else if (Boolean.FALSE.equals(pendingRemember))
		{
			p.password = null;
		}
		log.info("Karn profiles: playing as profile {} (remember password: {}, saved: {})", key, pendingRemember, p.password != null);
		pendingUser = null;
		pendingPassword = null;
		pendingRemember = null;
		rememberTicked = false;
		current = p;
		statsTimer = STATS_EVERY - CAPTURE_DELAY; // first update a few ticks in, once the player has loaded
		ticksIn = 0;
		ticksSinceCapture = RECAPTURE_EVERY;
		capSignature = 0;
		mode = Mode.FORM;
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (current == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		// the stats are kept up to date every tick (a short visit still records them; logging out saves them) and written
		// to disk now and then
		if (me.getName() != null && me.getCombatLevel() > 0)
		{
			current.displayName = Profile.clean(me.getName());
			current.combatLevel = me.getCombatLevel();
			current.totalLevel = client.getTotalLevel();
			NPC pet = pet(me);
			current.petName = pet == null ? null : net.runelite.client.util.Text.removeTags(pet.getName());
			current.lastPlayed = System.currentTimeMillis();
			if (++statsTimer >= STATS_EVERY)
			{
				statsTimer = 0;
				store.save(current);
			}
		}
		ticksIn++;
		ticksSinceCapture++;
		net.runelite.api.coords.WorldPoint spot = me.getWorldLocation();
		stillTicks = spot != null && spot.equals(lastSpot) && me.getAnimation() == -1 ? stillTicks + 1 : 0;
		lastSpot = spot;
		// capture once the player has loaded, again whenever the gear or pet changes, and every couple of minutes
		// anyway (so the login screen always shows the latest look), only while standing still
		if (capPlayer == null && ticksIn >= CAPTURE_DELAY && (stillTicks >= 1 || current.signature == 0)
			&& (signature(me) != current.signature || ticksSinceCapture >= RECAPTURE_EVERY))
		{
			startCapture(me);
		}
	}

	// =========================================================== capture

	/**
	 * Standing still: no animation and on the same tile as when the capture began. Not "pose == idle pose": our server
	 * gives weapons their own stand animations, and then the two never matched, so nothing in gear was ever captured.
	 */
	private boolean still(Player me)
	{
		return me.getAnimation() == -1 && capSpot != null && capSpot.equals(me.getWorldLocation());
	}

	/**
	 * The player's pet: the client's follower when the server reports one, else the NPC next to the player that follows
	 * them and can be picked up (our server spawns pets without the follower varp).
	 */
	NPC pet(Player me)
	{
		NPC f = client.getFollower();
		if (f != null)
		{
			return f;
		}
		for (NPC n : client.getNpcs())
		{
			if (n == null || n.getInteracting() != me)
			{
				continue;
			}
			net.runelite.api.NPCComposition c = n.getTransformedComposition();
			if (c == null)
			{
				c = n.getComposition();
			}
			if (c == null || c.getActions() == null)
			{
				continue;
			}
			for (String a : c.getActions())
			{
				if (a != null && a.equalsIgnoreCase("Pick-up"))
				{
					return n;
				}
			}
		}
		return null;
	}

	private int signature(Player me)
	{
		PlayerComposition c = me.getPlayerComposition();
		NPC pet = pet(me);
		int h = c == null ? 0 : Arrays.hashCode(c.getEquipmentIds()) * 31 + Arrays.hashCode(c.getColors());
		return h * 31 + (pet == null ? -1 : pet.getId()) * 7 + 1;
	}

	private void startCapture(Player me)
	{
		Model m = me.getModel();
		if (m == null)
		{
			return;
		}
		capPlayer = ProfileMesh.faces(m, client.getTextureProvider());
		capPlayer.addFrame(m);
		ticksSinceCapture = 0;
		NPC pet = pet(me);
		Model pm = pet == null ? null : pet.getModel();
		capPet = pm == null ? null : ProfileMesh.faces(pm, client.getTextureProvider());
		if (capPet != null)
		{
			capPet.addFrame(pm);
		}
		capPetDone = capPet == null;
		capTicks = 0;
		capStartFrame = me.getPoseAnimationFrame();
		capLastFrame = capStartFrame;
		capWrapped = false;
		capSignature = signature(me);
		capSpot = me.getWorldLocation();
	}

	private void stopCapture()
	{
		capPlayer = capPet = null;
	}

	@Subscribe
	public void onClientTick(ClientTick e)
	{
		if (capPlayer == null)
		{
			return;
		}
		Player me = client.getLocalPlayer();
		if (current == null || me == null || client.getGameState() != GameState.LOGGED_IN || !still(me) || signature(me) != capSignature)
		{
			log.debug("Karn profiles: capture interrupted (moved, animated or changed gear)");
			stopCapture(); // try again when standing still
			return;
		}
		capTicks++;
		int frame = me.getPoseAnimationFrame();
		if (frame < capLastFrame)
		{
			capWrapped = true;
		}
		capLastFrame = frame;
		boolean loopDone = capWrapped && frame >= capStartFrame;
		if (capTicks % FRAME_EVERY == 0 && !loopDone)
		{
			Model m = me.getModel();
			if (m == null || !capPlayer.addFrame(m))
			{
				stopCapture();
				return;
			}
			NPC pet = pet(me);
			if (!capPetDone)
			{
				Model pm = pet == null ? null : pet.getModel();
				if (pm == null || !capPet.addFrame(pm) || capPet.frames.size() >= MAX_FRAMES)
				{
					capPetDone = true;
				}
			}
		}
		if (loopDone || capPlayer.frames.size() >= MAX_FRAMES || capTicks >= MAX_CAPTURE_TICKS)
		{
			NPC follower = pet(me);
			current.petName = follower == null ? null : net.runelite.client.util.Text.removeTags(follower.getName());
			finishCapture(current);
		}
	}

	/** Saves the capture in progress to the profile (mesh.bin + profile.json, off the client thread). */
	private void finishCapture(Profile p)
	{
		{
			ProfileMesh player = capPlayer, pet = capPet != null && capPet.frames.size() > 1 ? capPet : null;
			p.signature = capSignature;
			stopCapture();
			log.info("Karn profiles: captured {} frames of {} faces{}", player.frames.size(), player.faceCount,
				pet == null ? "" : " + a pet, " + pet.frames.size() + " frames");
			renderer.submit(() ->
			{
				store.saveMesh(p, player, pet);
				store.save(p);
			});
		}
	}

	// =========================================================== login actions

	/** Finds the client's password getter and checks it: a marker set through the API must come back out of it. */
	private void findPasswordField()
	{
		if (passwordFieldSearched)
		{
			return;
		}
		passwordFieldSearched = true;
		clientThread.invokeLater(() ->
		{
			try
			{
				Class<?> c = Class.forName(LOGIN_CLASS, true, client.getClass().getClassLoader());
				java.lang.reflect.Method m = c.getDeclaredMethod(PASSWORD_GETTER);
				m.setAccessible(true);
				Object before = m.invoke(null);
				String marker = "karn" + System.nanoTime();
				client.setPassword(marker);
				boolean ok = marker.equals(m.invoke(null));
				client.setPassword(before instanceof String ? (String) before : "");
				if (ok)
				{
					passwordGetter = m;
					log.info("Karn profiles: password getter found, \"Remember password\" available");
				}
				else
				{
					log.warn("Karn profiles: {}.{}() isn't the password on this client, \"Remember password\" is off", LOGIN_CLASS, PASSWORD_GETTER);
				}
			}
			catch (ReflectiveOperationException | RuntimeException ex)
			{
				log.warn("Karn profiles: can't read the password, \"Remember password\" is off", ex);
			}
		});
	}

	private String readPassword()
	{
		try
		{
			Object v = passwordGetter == null ? null : passwordGetter.invoke(null);
			return v instanceof String ? (String) v : null;
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			return null;
		}
	}

	boolean canRemember()
	{
		return ProfileStore.canRememberPasswords();
	}

	/** The client's login message, its lines joined (the default "Enter your username..." prompt left out). */
	String loginMessage()
	{
		try
		{
			if (loginLines == null)
			{
				Class<?> c = Class.forName(LOGIN_CLASS, true, client.getClass().getClassLoader());
				java.lang.reflect.Field[] f = new java.lang.reflect.Field[LOGIN_LINES.length];
				for (int i = 0; i < f.length; i++)
				{
					f[i] = c.getDeclaredField(LOGIN_LINES[i]);
					f[i].setAccessible(true);
				}
				loginLines = f;
			}
			StringBuilder b = new StringBuilder();
			for (java.lang.reflect.Field f : loginLines)
			{
				Object v = f.get(null);
				String line = v instanceof String ? ((String) v).trim() : "";
				if (!line.isEmpty() && !line.startsWith("Enter your username"))
				{
					b.append(b.length() == 0 ? "" : " ").append(line);
				}
			}
			return b.toString();
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			return "";
		}
	}

	/**
	 * What to say after a failed attempt: the client's own lines when it wrote any, else by the login screen it moved to
	 * (index 3 is the "invalid username or password" screen, whose text is built into the client, not in the lines).
	 */
	String failureMessage()
	{
		String m = loginMessage();
		if (!m.isEmpty())
		{
			return m;
		}
		int index = client.getLoginIndex();
		if (index == 3)
		{
			return "Incorrect username or password.";
		}
		return "Login failed, please try again.";
	}

	/** Log in with what's in the Karn form. */
	void submit()
	{
		String user = formUser.toString().trim(), pass = formPass.toString();
		if (client.getGameState() != GameState.LOGIN_SCREEN)
		{
			return;
		}
		if (user.isEmpty())
		{
			formNote = "Please enter your username.";
			formFocus = 0;
			return;
		}
		if (pass.isEmpty())
		{
			formNote = "Please enter your password.";
			formFocus = 1;
			return;
		}
		formNote = null;
		attemptFailed = false;
		pendingUser = user;
		pendingPassword = pass;
		pendingRemember = rememberTicked;
		clientThread.invokeLater(() ->
		{
			client.setUsername(user);
			client.setPassword(pass);
			client.setGameState(GameState.LOGGING_IN);
		});
	}

	private void fillForm(String user, int focus)
	{
		formUser.setLength(0);
		formUser.append(user == null ? "" : user);
		formPass.setLength(0);
		formFocus = focus;
		formNote = null;
	}

	void select(Profile p)
	{
		selected = p;
		deleteArmed = null;
		mode = Mode.DETAIL;
	}

	void back()
	{
		selected = null;
		deleteArmed = null;
		mode = profiles.isEmpty() ? Mode.FORM : Mode.PICKER;
	}

	void newProfile()
	{
		selected = null;
		rememberTicked = false;
		fillForm("", 0);
		mode = Mode.FORM;
	}

	void play(Profile p)
	{
		String pw = ProfileStore.decrypt(p.password);
		selected = null;
		fillForm(p.username, 1);
		mode = Mode.FORM;
		if (pw == null)
		{
			rememberTicked = false;
			return; // the form, the username filled in, the cursor in the password box
		}
		rememberTicked = true;
		attemptFailed = false;
		formPass.append(pw);
		pendingUser = p.username;
		pendingPassword = pw;
		pendingRemember = null; // keep what's saved
		clientThread.invokeLater(() ->
		{
			client.setUsername(p.username);
			client.setPassword(pw);
			client.setGameState(GameState.LOGGING_IN);
		});
	}

	void delete(Profile p)
	{
		if (deleteArmed != p)
		{
			deleteArmed = p;
			return;
		}
		store.delete(p);
		reload();
	}

	void forgetPassword(Profile p)
	{
		p.password = null;
		store.save(p);
	}

	// =========================================================== input

	private boolean onLoginScreen()
	{
		GameState g = client.getGameState();
		return g == GameState.LOGIN_SCREEN || g == GameState.LOGGING_IN;
	}

	private final MouseAdapter mouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			if (!onLoginScreen())
			{
				return e;
			}
			if (client.getGameState() == GameState.LOGIN_SCREEN)
			{
				painter.click(e.getX(), e.getY());
			}
			e.consume();
			return e;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent e)
		{
			if (onLoginScreen())
			{
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent e)
		{
			if (onLoginScreen())
			{
				e.consume();
			}
			return e;
		}
	};

	private final KeyListener keys = new KeyListener()
	{
		/** RuneLite key listeners are off on the login screen unless they say so, and this one lives there */
		@Override
		public boolean isEnabledOnLoginScreen()
		{
			return true;
		}

		@Override
		public void keyTyped(KeyEvent e)
		{
			if (!onLoginScreen())
			{
				return;
			}
			e.consume();
			char ch = e.getKeyChar();
			if (mode != Mode.FORM || client.getGameState() != GameState.LOGIN_SCREEN || ch < 32 || ch == 127 || e.isControlDown())
			{
				return;
			}
			StringBuilder b = formFocus == 0 ? formUser : formPass;
			if (b.length() < (formFocus == 0 ? MAX_USER : MAX_PASS))
			{
				b.append(ch);
				formNote = null;
				attemptFailed = false;
			}
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (!onLoginScreen())
			{
				return;
			}
			e.consume();
			if (client.getGameState() != GameState.LOGIN_SCREEN)
			{
				return;
			}
			int k = e.getKeyCode();
			if (k == KeyEvent.VK_ESCAPE)
			{
				if (mode == Mode.DETAIL || (mode == Mode.FORM && !profiles.isEmpty()))
				{
					back();
				}
				return;
			}
			if (mode != Mode.FORM)
			{
				return;
			}
			StringBuilder b = formFocus == 0 ? formUser : formPass;
			if (k == KeyEvent.VK_BACK_SPACE && b.length() > 0)
			{
				b.setLength(b.length() - 1);
			}
			else if (k == KeyEvent.VK_TAB || k == KeyEvent.VK_UP || k == KeyEvent.VK_DOWN)
			{
				formFocus = 1 - formFocus;
			}
			else if (k == KeyEvent.VK_ENTER)
			{
				if (formFocus == 0)
				{
					formFocus = 1;
				}
				else
				{
					submit();
				}
			}
			else if (k == KeyEvent.VK_V && e.isControlDown())
			{
				try
				{
					Object clip = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
					String t = clip == null ? "" : clip.toString().replace("\r", "").replace("\n", "").replace("\t", "");
					int room = (formFocus == 0 ? MAX_USER : MAX_PASS) - b.length();
					b.append(t, 0, Math.max(0, Math.min(room, t.length())));
				}
				catch (Exception ignored)
				{
					// nothing usable on the clipboard
				}
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
			if (onLoginScreen())
			{
				e.consume();
			}
		}
	};
}
