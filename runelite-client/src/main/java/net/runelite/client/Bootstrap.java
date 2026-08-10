package net.runelite.client;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Real Main-Class of client.jar (see build.gradle). Exists so a bare
 * double-clicked/`java -jar client.jar` launch works with zero required
 * flags -- Guice's cglib bytecode generation needs
 * --add-opens=java.base/java.lang=ALL-UNNAMED or it crashes immediately
 * with InaccessibleObjectException, and there is no manifest attribute
 * that can set that for a plain `java -jar` launch. When the Zelus
 * launcher spawns this jar itself, it already passes that flag on the
 * process directly, so isOpen() below is true and this is a same-process
 * passthrough with no relaunch, no double JVM startup cost.
 */
public final class Bootstrap {

	public static void main(String[] args) throws Exception {
		Module javaBase = Object.class.getModule();
		Module unnamed = Bootstrap.class.getModule();

		if (javaBase.isOpen("java.lang", unnamed)) {
			RuneLite.main(args);
			return;
		}

		String javaBin = ProcessHandle.current().info().command().orElse("java");
		String selfJar = new File(Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI())
				.getAbsolutePath();

		List<String> cmd = new ArrayList<>();
		cmd.add(javaBin);
		cmd.add("-Xmx768m");
		cmd.add("--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED");
		cmd.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
		cmd.add("-cp");
		cmd.add(selfJar);
		cmd.add("net.runelite.client.RuneLite");
		for (String a : args) {
			cmd.add(a);
		}

		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.inheritIO();
		Process p = pb.start();
		System.exit(p.waitFor());
	}
}
