package dev.ultracraft;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Where the user's own ULTRAKILL files live: %APPDATA%/Ultracraft/ultracraft.properties (ukPath=folder) -- the one
 * file the Minecraft mod and the Play-Ultracraft launcher share, so whichever runs first asks for the folder with a
 * file dialog and the other never asks again. Steam plays no part: the game is started straight from that folder.
 * -Dultracraft.ukConfig=&lt;file&gt; points at another config file (tests).
 */
final class UkPath {
	private UkPath() {}

	/** The shared file both sides read and write. */
	static Path file() {
		String override = System.getProperty("ultracraft.ukConfig");
		if (override != null && !override.isEmpty()) return Path.of(override);
		String appData = System.getenv("APPDATA");
		File dir = appData == null || appData.isEmpty() ? new File(System.getProperty("user.home"), "AppData/Roaming/Ultracraft")
			: new File(appData, "Ultracraft");
		return new File(dir, "ultracraft.properties").toPath();
	}

	/** The saved folder as written, or null when nothing has been picked yet. */
	static String saved() {
		try {
			Path file = file();
			if (!Files.isRegularFile(file)) return null;
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
				String s = line.trim();
				if (!s.startsWith("ukPath")) continue;
				int eq = s.indexOf('=');
				if (eq <= 0) continue;
				String path = s.substring(eq + 1).trim();
				if (path.length() > 1 && path.startsWith("\"") && path.endsWith("\"")) path = path.substring(1, path.length() - 1);
				return path.isEmpty() ? null : path;
			}
			return null;
		} catch (Exception e) {
			System.err.println("[Ultracraft] ukPath: " + e);
			return null;
		}
	}

	/** Remember the folder for every later start. */
	static void save(File folder) {
		try {
			Path file = file();
			if (file.getParent() != null) Files.createDirectories(file.getParent());
			Files.write(file, ("ukPath=" + folder.getAbsolutePath() + "\r\n").getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			System.err.println("[Ultracraft] ukPath: " + e);
		}
	}

	/** ULTRAKILL.exe in the folder (or one level down), else null. */
	static File exeIn(String folder) {
		if (folder == null || folder.isEmpty()) return null;
		File dir = new File(folder);
		File exe = new File(dir, "ULTRAKILL.exe");
		if (exe.isFile()) return exe;
		File[] kids = dir.listFiles(File::isDirectory);
		if (kids != null) {
			for (File kid : kids) {
				exe = new File(kid, "ULTRAKILL.exe");
				if (exe.isFile()) return exe;
			}
		}
		return null;
	}

	/** The saved folder's game exe, or null when unset or the files have moved. */
	static File find() {
		return exeIn(saved());
	}

	/**
	 * Asks for the folder with a file dialog (first run only) and saves it. Returns the game exe, or null when the
	 * dialog is cancelled or cannot be shown (headless). A folder without ULTRAKILL.exe is re-asked, never accepted.
	 * Blocks the calling thread until the dialog closes.
	 */
	static File pickAndSave() {
		if (GraphicsEnvironment.isHeadless()) return null;
		File[] out = new File[1];
		try {
			SwingUtilities.invokeAndWait(() -> {
				try {
					UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
				} catch (Exception ignored) {
				}
				JFileChooser chooser = new JFileChooser();
				chooser.setDialogTitle("Choose your ULTRAKILL folder (the one containing ULTRAKILL.exe)");
				chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				chooser.setAcceptAllFileFilterUsed(false);
				try {
					String saved = saved();
					if (saved != null) chooser.setCurrentDirectory(new File(saved));
				} catch (Exception ignored) {
				}
				while (out[0] == null) {
					if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return; // cancelled: null stays null
					File chosen = chooser.getSelectedFile();
					File exe = exeIn(chosen.getAbsolutePath());
					if (exe != null) {
						save(chosen);
						out[0] = exe;
					} else {
						JOptionPane.showMessageDialog(chooser,
							"No ULTRAKILL.exe in:\n" + chosen + "\n\nPick the folder that contains the game.",
							"Ultracraft", JOptionPane.ERROR_MESSAGE);
					}
				}
			});
		} catch (Exception e) {
			System.err.println("[Ultracraft] folder dialog: " + e);
		}
		return out[0];
	}

	/** The game exe: from the saved folder, else asked for now (first run) and saved. */
	static File findOrAsk() {
		File exe = find();
		return exe != null ? exe : pickAndSave();
	}
}
