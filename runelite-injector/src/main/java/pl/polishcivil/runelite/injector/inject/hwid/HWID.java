package pl.polishcivil.runelite.injector.inject.hwid;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.MessageDigest;

public class HWID {
	private static final String osName = System.getProperty("os.name").toLowerCase();
	private static String OS_WINDOWS = "windows";
	private static String OS_MAC = "mac";
	private static String OS_UNIX = "unix";

	public static String hwid() {
		try {
			return getHWID();
		} catch (Exception e) {
			return "unknown: " + e.getMessage();
		}
	}

	public static String getHWID() throws Exception {
		String os = getCurrentOS();

		String hwid = "";
		String error = "";

		if (os.equals(OS_WINDOWS)) {
			try {
				hwid = runCommand("wmic baseboard get serialNumber");
				hwid += " " + runCommand("wmic cpu get ProcessorId");
				hwid += " " + getHDDSerialNumber("C");
			} catch (Exception e) {
				// wmic was removed from current Windows 11 builds, which left every such player with the same
				// "unknown: Cannot run program wmic" id (no HWID bans / alt checks). Fall back to the Windows
				// install's MachineGuid. Machines where wmic still works keep their old id unchanged.
				hwid = "machineguid " + getMachineGuid();
			}
		} else if (os.equals(OS_MAC)) {
			String result = runCommand("system_profiler SPHardwareDataType | awk '/Serial/ {print $4}'");
			hwid = result.substring(result.indexOf("Hardware UUID: ") + 15);
		} else if (os.equals(OS_UNIX)) {
			String cpu = runCommand("lshw -c cpu");
			cpu = cpu.substring(cpu.indexOf("product: ") + 9, cpu.indexOf("vendor: ") - 7);
			String graphics = "";
			try {
				graphics = runCommand("lshw -c display");
				graphics = graphics.substring(graphics.indexOf("product: ") + 9, graphics.indexOf("vendor: ") - 7);
			} catch (Exception e) {
				e.printStackTrace();
				error += e.getMessage();
			}
			hwid = cpu + " " + graphics;
		}

		if (hwid.isEmpty() || hwid.equals(" ")) {
			throw new NullPointerException("os: " + os + " error: " + error);
		} else {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			digest.update(hwid.getBytes());
			byte[] bytes = digest.digest();
			StringBuilder sb = new StringBuilder();
			for (byte b : bytes) {
				sb.append(String.format("%02x", b & 0xff));
			}
			return sb.toString();
		}
	}

	private static String runCommand(String command) throws Exception {
		StringBuilder result = new StringBuilder();
		Process p = Runtime.getRuntime().exec(command);
		BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
		String line;

		while ((line = reader.readLine()) != null) {
			result.append(line);
		}
		reader.close();
		if (result.toString().equalsIgnoreCase(" ") || result.toString().isEmpty()) {
			throw new IllegalStateException();
		} else {
			return result.toString();
		}
	}

	/** HKLM\SOFTWARE\Microsoft\Cryptography MachineGuid, via reg.exe (present on every Windows). No inner classes: this
	 * class is copied into the gamepack on its own by HWIDInject. */
	private static String getMachineGuid() throws Exception {
		String out = runCommand("reg query HKLM\\SOFTWARE\\Microsoft\\Cryptography /v MachineGuid");
		java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matcher(out);
		if (!m.find())
			throw new IllegalStateException("no MachineGuid");
		return m.group().toLowerCase();
	}

	public static String getHDDSerialNumber(String drive) {
		String result = "";
		try {
			// Run the command to get the Serial Number
			Process process = Runtime.getRuntime().exec("wmic diskdrive get serialnumber");
			process.waitFor();
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;

			// Read the output from the command
			while ((line = reader.readLine()) != null) {
				if (!line.trim().isEmpty() && !line.trim().startsWith("SerialNumber")) {
					// Assuming the command returns one serial number
					result = line.trim();
					break;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return result;
	}

	public static String getCurrentOS() throws Exception {
		if (osName.contains("win")) {
			return OS_WINDOWS;
		} else if (osName.contains("mac")) {
			return OS_MAC;
		} else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
			return OS_UNIX;
		} else {
			throw new IllegalStateException();
		}
	}
}
