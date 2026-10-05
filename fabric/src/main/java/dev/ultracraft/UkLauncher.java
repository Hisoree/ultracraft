package dev.ultracraft;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft started from the normal launcher brings ULTRAKILL along: as soon as Minecraft is up, ULTRAKILL starts
 * straight from the folder the user chose (its window hidden, the Sandbox loading in the background, waiting for a
 * world), and closing Minecraft closes the ULTRAKILL it started. An ULTRAKILL already running is used as it is. The
 * "Start ULTRAKILL" setting (or -Dultracraft.noLaunch, which the test launcher passes) turns this off.
 * Steam plays no part: on the first run a dialog asks for the ULTRAKILL folder once (UkPath), and every later start
 * goes straight to ULTRAKILL.exe in that folder.
 */
final class UkLauncher {
	private static final Logger LOG = LoggerFactory.getLogger("ultracraft");
	private static boolean started;

	private UkLauncher() {}

	static void register() {
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> launch());
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> close());
	}

	static Optional<ProcessHandle> running() {
		return ProcessHandle.allProcesses().filter(p -> p.info().command().map(c -> c.toLowerCase().endsWith("\\ultrakill.exe")).orElse(false)).findFirst();
	}

	private static void launch() {
		if (!UltracraftConfig.launchUltrakill || System.getProperty("ultracraft.noLaunch") != null) return;
		if (running().isPresent() || UkLink.connected) {
			LOG.info("ULTRAKILL is already running");
			return;
		}
		File exe = UkPath.findOrAsk();
		if (exe == null) {
			LOG.warn("no ULTRAKILL folder chosen: start ULTRAKILL yourself (with the UltraBridge plugin)");
			return;
		}
		try {
			new ProcessBuilder(exe.getAbsolutePath(), "-ultracraft", "-screen-fullscreen", "0", "-screen-width", "1280", "-screen-height", "720")
				.directory(exe.getParentFile()).start();
			started = true;
			LOG.info("starting ULTRAKILL at {}", exe);
		} catch (Exception e) {
			LOG.warn("couldn't start ULTRAKILL: {}", e.toString());
		}
	}

	private static void close() {
		if (!started) return;
		// ULTRAKILL quits itself when asked; if it doesn't answer, it's closed
		if (UkLink.connected) UkLink.send("QUIT");
		running().ifPresent(p -> {
			try {
				p.onExit().get(6, TimeUnit.SECONDS);
			} catch (Exception e) {
				LOG.info("closing ULTRAKILL");
				p.destroy();
			}
		});
	}
}
