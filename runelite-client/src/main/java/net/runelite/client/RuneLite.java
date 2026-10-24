/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.gson.Gson;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.management.ObjectName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.swing.SwingUtilities;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.ValueConversionException;
import joptsimple.ValueConverter;
import joptsimple.util.EnumConverter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.account.SessionManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.discord.DiscordService;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.rs.CacheDownloadProgress;
import net.runelite.client.rs.CacheVersionGuard;
import net.runelite.client.rs.ClientUpdateCheckMode;
import net.runelite.client.rs.CrashLog;
import net.runelite.client.rs.ReasonClientLoader;
import net.runelite.client.rs.auth.Account;
import net.runelite.client.rs.auth.JagexAuthService;
import net.runelite.client.rs.auth.Session;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.FatalErrorDialog;
import net.runelite.client.ui.SplashScreen;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.OSType;
import net.runelite.client.util.ReflectUtil;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.Cache;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.LoggerFactory;

@Singleton
@Slf4j
public class RuneLite
{
	// Multi-instance cache isolation -- MUST run before RUNELITE_DIR (and every path derived
	// from it below) is computed, since it works by overriding the "user.home" system property
	// that this field -- and the injected game client itself, which derives its own jagexcache
	// path off "user.home" the exact same way -- both read fresh at the time they need it.
	//
	// 2026-09-16 incident: two clients on one machine shared the same jagexcache folder and the
	// second hit error_game_js5crc immediately, because both processes read/wrote the same JS5
	// cache files at once. On startup, try to take an exclusive OS-level lock on a marker file;
	// a single instance always gets it and is unaffected. If it's already held, redirect ONLY
	// this process's "user.home" to an isolated per-process directory before anything reads it.
	// The lock is never released by this process -- the OS drops it on exit.
	// Static fields, NOT locals: a FileChannel that gets garbage collected closes its fd and
	// silently releases the lock mid-session (bug fixed 2026-09-26).
	private static FileChannel instanceLockChannel;
	private static FileLock instanceLock;

	static
	{
		isolateIfSecondInstance();
	}

	private static void isolateIfSecondInstance()
	{
		try
		{
			File realHome = new File(System.getProperty("user.home"));
			File lockDir = new File(realHome, ".zelus/.runelite");
			lockDir.mkdirs();
			instanceLockChannel = new FileOutputStream(new File(lockDir, "instance.lock")).getChannel();
			instanceLock = instanceLockChannel.tryLock();
			if (instanceLock == null)
			{
				File altHome = new File(realHome, ".zelus-multiclient-" + ProcessHandle.current().pid());
				altHome.mkdirs();
				System.setProperty("user.home", altHome.getAbsolutePath());
				System.err.println("Another client instance is already running -- using an isolated cache "
					+ "directory for this instance to avoid a shared-cache JS5 crash: " + altHome);
			}
		}
		catch (Exception e)
		{
			// Never block startup over this -- a single running instance is unaffected either way.
			System.err.println("Failed to run multi-instance cache isolation check (non-fatal): " + e);
		}
	}

	public static final File RUNELITE_DIR = new File(System.getProperty("user.home"), ".zelus/.runelite");
	public static final File CACHE_DIR = new File(RUNELITE_DIR, "cache");
	public static final File PLUGINS_DIR = new File(RUNELITE_DIR, "plugins");
	public static final File SCREENSHOT_DIR = new File(RUNELITE_DIR, "screenshots");
	public static final File LOGS_DIR = new File(RUNELITE_DIR, "logs");
	public static final File DEFAULT_SESSION_FILE = new File(RUNELITE_DIR, "session");
	public static final File NOTIFICATIONS_DIR = new File(RuneLite.RUNELITE_DIR, "notifications");
	public static final File FONTS_DIR = new File(RuneLite.RUNELITE_DIR, "fonts");
	public static final Path PLUGIN_DATA = new File(RuneLite.RUNELITE_DIR, "plugin-data").toPath();

	private static final int MAX_OKHTTP_CACHE_SIZE = 20 * 1024 * 1024; // 20mb
	private static final String reason = "https://api.reason.gg";
	public static String USER_AGENT = "RuneLite/" + RuneLiteProperties.getVersion() + "-" + RuneLiteProperties.getCommit() + (RuneLiteProperties.isDirty() ? "+" : "");

	@Getter
	private static Injector injector;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ExternalPluginManager externalPluginManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private ConfigManager configManager;

	@Inject
	private SessionManager sessionManager;

	@Inject
	private DiscordService discordService;

	@Inject
	private ClientSessionManager clientSessionManager;

	@Inject
	private ClientUI clientUI;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private Gson gson;

	@Inject
	private Client client;

	@Inject
	@Nullable
	private RuntimeConfig runtimeConfig;

	@Inject
	@Nullable
	private TelemetryClient telemetryClient;

	@Inject
	private ScheduledExecutorService scheduledExecutorService;

	@Inject
	@Named("character")
	@Nullable
	private String character;

	@Inject
	private JagexAuthService jagexAuthService;

	public static void main(String[] args) throws Exception
	{
		// Java2D's Direct3D pipeline hangs the client forever on dual-GPU laptops when it draws without the GPU
		// plugin (2026-10-15: GPU + HD off froze in D3DScreenUpdateManager.runUpdateNow). The official launcher turns it
		// off; the client is started with plain "java -jar", so turn it off here, before AWT loads. -D flags still win.
		if (System.getProperty("sun.java2d.d3d") == null)
		{
			System.setProperty("sun.java2d.d3d", "false");
		}
		if (System.getProperty("sun.java2d.noddraw") == null)
		{
			System.setProperty("sun.java2d.noddraw", "true");
		}
		Locale.setDefault(Locale.ENGLISH);

		// Must run before ANYTHING else (including the Preloader thread started below) ever
		// touches jagexcache -- see CacheVersionGuard's javadoc for why this exists.
		CacheVersionGuard.runIfNeeded();
		// Separate, always-on safety net for a crash/interrupted-download on a previous launch --
		// see beginSession()'s javadoc. Paired with the markSessionSafe() call in start() below.
		CacheVersionGuard.beginSession();

		final OptionParser parser = new OptionParser(false);
		parser.accepts("developer-mode", "Enable developer tools");
		parser.accepts("debug", "Show extra debugging output");
		parser.accepts("safe-mode", "Disables external plugins and the GPU plugin");
		parser.accepts("insecure-skip-tls-verification", "Disables TLS verification");
		parser.accepts("jav_config", "jav_config url")
			.withRequiredArg()
			.defaultsTo(RuneLiteProperties.getJavConfig());
		parser.accepts("disable-telemetry", "Disable telemetry");
		parser.accepts("profile", "Configuration profile to use").withRequiredArg();
		parser.accepts("character", "Character name to use").withRequiredArg();
		parser.accepts("noupdate", "Skips the launcher update");

		final ArgumentAcceptingOptionSpec<File> sessionfile = parser.accepts("sessionfile", "Use a specified session file")
			.withRequiredArg()
			.withValuesConvertedBy(new ConfigFileConverter())
			.defaultsTo(DEFAULT_SESSION_FILE);

		final ArgumentAcceptingOptionSpec<ClientUpdateCheckMode> updateMode = parser
			.accepts("rs", "Select client type")
			.withRequiredArg()
			.ofType(ClientUpdateCheckMode.class)
			.defaultsTo(ClientUpdateCheckMode.AUTO)
			.withValuesConvertedBy(new EnumConverter<>(ClientUpdateCheckMode.class)
			{
				@Override
				public ClientUpdateCheckMode convert(String v)
				{
					return super.convert(v.toUpperCase());
				}
			});

		final OptionSpec<Void> insecureWriteCredentials = parser.accepts("insecure-write-credentials", "Dump authentication tokens from the Jagex Launcher to a text file to be used for development");

		parser.accepts("help", "Show this text").forHelp();
		OptionSet options = parser.parse(args);

		if (options.has("help"))
		{
			parser.printHelpOn(System.out);
			System.exit(0);
		}

		if (options.has("debug"))
		{
			final Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
			logger.setLevel(Level.DEBUG);
		}

		Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
		{
			log.error("Uncaught exception:", throwable);
			CrashLog.write(thread, throwable);
			if (throwable instanceof AbstractMethodError)
			{
				log.error("Classes are out of date; Build with Gradle again.");
			}
		});

		final OkHttpClient okHttpClient = buildHttpClient(options.has("insecure-skip-tls-verification"));
		RuneLiteAPI.CLIENT = okHttpClient;

		SplashScreen.init();
		SplashScreen.stage(0, "Retrieving client", "");
		boolean startedOk = false;

		try
		{
			final RuntimeConfigLoader runtimeConfigLoader = new RuntimeConfigLoader(okHttpClient);
			final ReasonClientLoader clientLoader = new ReasonClientLoader(okHttpClient, options.valueOf(updateMode), runtimeConfigLoader,
				(String) options.valueOf("jav_config"));

			new Thread(() ->
			{
				clientLoader.get();
				ClassPreloader.preload();
			}, "Preloader").start();

			// Zelus always runs with developer tools available (and without the -ea requirement).
			final boolean developerMode = true;

			log.info("RuneLite {} (launcher version {}) starting up, args: {}",
				RuneLiteProperties.getVersion(), MoreObjects.firstNonNull(RuneLiteProperties.getLauncherVersion(), "unknown"),
				args.length == 0 ? "none" : String.join(" ", args));

			final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
			// This includes arguments from _JAVA_OPTIONS, which are parsed after command line flags and applied to
			// the global VM args
			log.info("Java VM arguments: {}", String.join(" ", runtime.getInputArguments()));

			final long start = System.currentTimeMillis();
			injector = Guice.createInjector(new RuneLiteModule(
				okHttpClient,
				clientLoader,
				runtimeConfigLoader,
				developerMode,
				options.has("safe-mode"),
				options.has("disable-telemetry"),
				options.valueOf(sessionfile),
				(String) options.valueOf("profile"),
				(String) options.valueOf("character"),
				options.has(insecureWriteCredentials),
				options.has("noupdate")
			));

			injector.getInstance(RuneLite.class).start();

			final long end = System.currentTimeMillis();
			final long uptime = runtime.getUptime();
			log.info("Client initialization took {}ms. Uptime: {}ms", end - start, uptime);
			startedOk = true;
		}
		catch (Exception e)
		{
			log.error("Failure during startup", e);
			SwingUtilities.invokeLater(() ->
				new FatalErrorDialog("RuneLite has encountered an unexpected error during startup.")
					.addHelpButtons()
					.open());
		}
		finally
		{
			SplashScreen.stop();
		}
		if (startedOk)
		{
			// the engine may still be downloading its cache behind its own loading screen -- see CacheDownloadProgress
			CacheDownloadProgress.start();
		}
	}

	public static HttpUrl getreasonApiBase()
	{
		return HttpUrl.parse(reason + "/");
	}

	public void start() throws Exception
	{
		// Marks this session "safe" the first time the client reaches the login screen -- proof
		// the risky JS5 cache-sync phase succeeded -- clearing CacheVersionGuard's crash marker.
		// Registered up front, before client.initialize() below kicks off the engine, so the
		// transition can't be missed. Unregisters itself after firing once.
		eventBus.register(new Object()
		{
			@Subscribe
			public void onGameStateChanged(GameStateChanged event)
			{
				if (event.getGameState().getState() >= GameState.LOGIN_SCREEN.getState())
				{
					CacheVersionGuard.markSessionSafe();
					eventBus.unregister(this);
				}
			}
		});

		// Inject members into client
		injector.injectMembers(client);

		setupSystemProps();
		setupCompilerControl();

		copyJagexCache();

		System.setProperty("jagex.disableBouncyCastle", "true");
		System.setProperty("jagex.userhome", RUNELITE_DIR.getAbsolutePath());

		// Start the applet
		client.initialize();

		SplashScreen.stage(.57, null, "Loading configuration");

		// Load the session so that the session profiles can be loaded next
		sessionManager.loadSession();

		// Load user configuration
		configManager.load();

		// Update check requires ConfigManager to be ready before it runs
		Updater updater = injector.getInstance(Updater.class);
		updater.update(); // will exit if an update is in progress

		// Load the plugins, but does not start them yet.
		// This will initialize configuration
		pluginManager.loadCorePlugins();
		pluginManager.loadSideLoadPlugins();
		try
		{
			externalPluginManager.loadExternalPlugins();
		}
		catch (Exception e)
		{
			log.warn("Unable to load external plugins", e);
		}

		SplashScreen.stage(.70, null, "Finalizing configuration");

		// Plugins have provided their config, so set default config
		// to main settings
		pluginManager.loadDefaultPluginConfiguration(null);

		// Start client session
		clientSessionManager.start();
		eventBus.register(clientSessionManager);

		SplashScreen.stage(.75, null, "Starting core interface");

		// Initialize UI
		clientUI.init();

		// Initialize Discord service
		discordService.init();

		// Register event listeners
		eventBus.register(clientUI);
		eventBus.register(pluginManager);
		eventBus.register(externalPluginManager);
		eventBus.register(overlayManager);
		eventBus.register(configManager);
		eventBus.register(discordService);

		// Add core overlays
		overlayManager.init();

		// Start plugins
		pluginManager.startPlugins();

		SplashScreen.stop();

		clientUI.show();

		client.unblockStartup();

		setCharacter();

		if (telemetryClient != null)
		{
			scheduledExecutorService.execute(() ->
			{
				telemetryClient.submitTelemetry();
				// VM error reports stay off on Zelus (they'd go to RuneLite's servers).
			});
		}

		ReflectUtil.queueInjectorAnnotationCacheInvalidation(injector);
		ReflectUtil.invalidateAnnotationCaches();
	}

	@VisibleForTesting
	public static void setInjector(Injector injector)
	{
		RuneLite.injector = injector;
	}

	private static class ConfigFileConverter implements ValueConverter<File>
	{
		@Override
		public File convert(String fileName)
		{
			final File file;

			if (Paths.get(fileName).isAbsolute()
				|| fileName.startsWith("./")
				|| fileName.startsWith(".\\"))
			{
				file = new File(fileName);
			}
			else
			{
				file = new File(RuneLite.RUNELITE_DIR, fileName);
			}

			if (file.exists() && (!file.isFile() || !file.canWrite()))
			{
				throw new ValueConversionException(String.format("File %s is not accessible", file.getAbsolutePath()));
			}

			return file;
		}

		@Override
		public Class<? extends File> valueType()
		{
			return File.class;
		}

		@Override
		public String valuePattern()
		{
			return null;
		}
	}

	@VisibleForTesting
	static OkHttpClient buildHttpClient(boolean insecureSkipTlsVerification)
	{
		OkHttpClient.Builder builder = new OkHttpClient.Builder()
			.pingInterval(30, TimeUnit.SECONDS)
			.addInterceptor(chain ->
			{
				Request request = chain.request();
				var ua = request.header("User-Agent");
				if (ua == null)
				{
					ua = USER_AGENT;
				}
				else if (!ua.startsWith("RuneLite"))
				{
					ua = USER_AGENT + " " + ua;
				}

				Request userAgentRequest = request
					.newBuilder()
					.header("User-Agent", ua)
					.build();
				return chain.proceed(userAgentRequest);
			})
			// Setup cache
			.cache(new Cache(new File(CACHE_DIR, "okhttp"), MAX_OKHTTP_CACHE_SIZE))
			.addNetworkInterceptor(chain ->
			{
				// This has to be a network interceptor so it gets hit before the cache tries to store stuff
				Response res = chain.proceed(chain.request());
				if (res.code() >= 400 && "GET".equals(res.request().method()))
				{
					// if the request 404'd we don't want to cache it because its probably temporary
					res = res.newBuilder()
						.header("Cache-Control", "no-store")
						.build();
				}
				return res;
			});

		try
		{
			if (insecureSkipTlsVerification || RuneLiteProperties.isInsecureSkipTlsVerification())
			{
				setupInsecureTrustManager(builder);
			}
			else
			{
				setupTrustManager(builder);
			}
		}
		catch (KeyStoreException | KeyManagementException | NoSuchAlgorithmException e)
		{
			log.warn("error setting up trust manager", e);
		}

		return builder.build();
	}

	private static void copyJagexCache()
	{
		Path from = Paths.get(System.getProperty("user.home"), "jagexcache");
		Path to = Paths.get(System.getProperty("user.home"), ".zelus/.runelite", "jagexcache");
		if (Files.exists(to) || !Files.exists(from))
		{
			return;
		}

		log.info("Copying jagexcache from {} to {}", from, to);

		// Recursively copy path https://stackoverflow.com/a/50418060
		try (Stream<Path> stream = Files.walk(from))
		{
			stream.forEach(source ->
			{
				try
				{
					Files.copy(source, to.resolve(from.relativize(source)), COPY_ATTRIBUTES);
				}
				catch (IOException e)
				{
					throw new RuntimeException(e);
				}
			});
		}
		catch (Exception e)
		{
			log.warn("unable to copy jagexcache", e);
		}
	}

	private void setupSystemProps()
	{
		if (runtimeConfig == null || runtimeConfig.getSysProps() == null)
		{
			return;
		}

		for (Map.Entry<String, String> entry : runtimeConfig.getSysProps().entrySet())
		{
			String key = entry.getKey(), value = entry.getValue();
			log.debug("Setting property {}={}", key, value);
			System.setProperty(key, value);
		}
	}

	private void setupCompilerControl()
	{
		try
		{
			var file = Files.createTempFile("rl_compilercontrol", "");
			try
			{
				if (runtimeConfig != null && runtimeConfig.getCompilerControl() != null)
				{
					var json = gson.toJson(runtimeConfig.getCompilerControl());
					Files.writeString(file, json, StandardCharsets.UTF_8);
				}
				else
				{
					try (var in = RuneLite.class.getResourceAsStream("/compilercontrol.json"))
					{
						Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
					}
				}

				ManagementFactory.getPlatformMBeanServer().invoke(
					new ObjectName("com.sun.management:type=DiagnosticCommand"),
					"compilerDirectivesAdd",
					new Object[]{new String[]{file.toFile().getAbsolutePath()}},
					new String[]{String[].class.getName()});
			}
			finally
			{
				Files.delete(file);
			}
		}
		catch (Exception e)
		{
			log.info("Failed to set compiler control", e);
		}
	}

	// region trust manager
	private static TrustManager[] loadTrustManagers(String trustStoreType) throws KeyStoreException, NoSuchAlgorithmException
	{
		// javax.net.ssl.trustStoreType controls which keystore implementation the TrustStoreManager uses
		String old;
		if (trustStoreType != null)
		{
			old = System.setProperty("javax.net.ssl.trustStoreType", trustStoreType);
		}
		else
		{
			old = System.clearProperty("javax.net.ssl.trustStoreType");
		}

		TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trustManagerFactory.init((KeyStore) null);

		TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();

		// restore old value
		if (old == null)
		{
			System.clearProperty("javax.net.ssl.trustStoreType");
		}
		else
		{
			System.setProperty("javax.net.ssl.trustStoreType", old);
		}

		return trustManagers;
	}

	private static void setupTrustManager(OkHttpClient.Builder okHttpClientBuilder) throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException
	{
		if (OSType.getOSType() != OSType.Windows)
		{
			return;
		}

		// Use the Windows Trusted Root Certificate Authorities in addition to the bundled cacerts.
		// Corporations, schools, antivirus, and malware commonly install root certificates onto
		// machines for security or other reasons that are not present in the JRE certificate store.
		TrustManager[] jreTms = loadTrustManagers(null);
		TrustManager[] windowsTms = loadTrustManagers("Windows-ROOT");

		TrustManager[] trustManagers = new TrustManager[jreTms.length + windowsTms.length];
		System.arraycopy(jreTms, 0, trustManagers, 0, jreTms.length);
		System.arraycopy(windowsTms, 0, trustManagers, jreTms.length, windowsTms.length);

		// Even though SSLContext.init() accepts TrustManager[], Sun's SSLContextImpl only picks the first
		// X509TrustManager and uses that.
		X509TrustManager combiningTrustManager = new X509TrustManager()
		{
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException
			{
				CertificateException exception = null;
				for (TrustManager trustManager : trustManagers)
				{
					if (trustManager instanceof X509TrustManager)
					{
						try
						{
							((X509TrustManager) trustManager).checkClientTrusted(chain, authType);
							// accept if any of the trust managers accept the certificate
							return;
						}
						catch (CertificateException ex)
						{
							exception = ex;
						}
					}
				}

				if (exception != null)
				{
					throw exception;
				}

				throw new CertificateException("no X509TrustManagers present");
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException
			{
				CertificateException exception = null;
				for (TrustManager trustManager : trustManagers)
				{
					if (trustManager instanceof X509TrustManager)
					{
						try
						{
							((X509TrustManager) trustManager).checkServerTrusted(chain, authType);
							// accept if any of the trust managers accept the certificate
							return;
						}
						catch (CertificateException ex)
						{
							exception = ex;
						}
					}
				}

				if (exception != null)
				{
					throw exception;
				}

				throw new CertificateException("no X509TrustManagers present");
			}

			@Override
			public X509Certificate[] getAcceptedIssuers()
			{
				List<X509Certificate> certificates = new ArrayList<>();
				for (TrustManager trustManager : trustManagers)
				{
					if (trustManager instanceof X509TrustManager)
					{
						certificates.addAll(Arrays.asList(((X509TrustManager) trustManager).getAcceptedIssuers()));
					}
				}
				return certificates.toArray(new X509Certificate[0]);
			}
		};

		SSLContext sc = SSLContext.getInstance("TLS");
		sc.init(null, new TrustManager[]{combiningTrustManager}, new SecureRandom());
		okHttpClientBuilder.sslSocketFactory(sc.getSocketFactory(), combiningTrustManager);
	}

	private static void setupInsecureTrustManager(OkHttpClient.Builder okHttpClientBuilder) throws NoSuchAlgorithmException, KeyManagementException
	{
		// the insecure trust manager trusts everything
		X509TrustManager trustManager = new X509TrustManager()
		{
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType)
			{
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType)
			{
			}

			@Override
			public X509Certificate[] getAcceptedIssuers()
			{
				return new X509Certificate[0];
			}
		};

		SSLContext sc = SSLContext.getInstance("TLS");
		sc.init(null, new TrustManager[]{trustManager}, new SecureRandom());
		okHttpClientBuilder.sslSocketFactory(sc.getSocketFactory(), trustManager);
	}
	// endregion

	private void setCharacter()
	{
		if (character == null)
		{
			return;
		}

		jagexAuthService.loadFromStore()
			.thenRun(() ->
			{
				int numChars = 0;
				for (Session session : jagexAuthService.getSessions())
				{
					Account[] accounts = jagexAuthService.getAccounts(session.getSessionId());
					if (accounts == null)
					{
						continue;
					}

					numChars += accounts.length;
					for (Account account : accounts)
					{
						String displayName = account.getDisplayName();
						if (displayName != null && displayName.equalsIgnoreCase(character))
						{
							log.info("Switching to character {}", displayName);
							jagexAuthService.switchJagexAccount(session.getSessionId(), Long.toString(account.getAccountId()), displayName);
							return;
						}
					}
				}

				log.info("Unable to find character {} ({} sessions and {} characters total)", character, jagexAuthService.getSessions().size(), numChars);
			});
	}
}
