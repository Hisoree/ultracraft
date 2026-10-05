package dev.ultracraft;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
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
	 * Asks for the folder without stopping the caller: the dialog is shown on Swing's own thread, always on top (a
	 * fullscreen game cannot hide it), and onDone runs when it closes with the game exe, or null when cancelled or
	 * when there is no display. Use this from the game's thread so Minecraft keeps running while the dialog is open.
	 */
	static void pickAsync(Consumer<File> onDone) {
		Thread ask = new Thread(() -> onDone.accept(pickAndSave()), "Ultracraft folder dialog");
		ask.setDaemon(true);
		ask.start();
	}

	/**
	 * Asks for the folder with a file dialog and saves it. Returns the game exe, or null when the dialog is cancelled
	 * or cannot be shown (headless). A folder without ULTRAKILL.exe is re-asked, never accepted. Blocks the calling
	 * thread until the dialog closes, so call it from a worker thread (pickAsync), not from the game's render thread.
	 */
	static File pickAndSave() {
		if (!onDesktop()) return null;
		File[] out = new File[1];
		Runnable ask = () -> {
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
			// a dialog of our own instead of chooser.showOpenDialog: modal, and always on top so the fullscreen
			// game window cannot cover it (that made the button look dead)
			JDialog dialog = new JDialog((java.awt.Frame) null, "Choose your ULTRAKILL folder", true);
			dialog.setAlwaysOnTop(true);
			dialog.getContentPane().add(chooser);
			dialog.pack();
			dialog.setSize(Math.max(700, dialog.getWidth()), Math.max(480, dialog.getHeight()));
			dialog.setLocationRelativeTo(null);
			dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
			cancelOnEscape(dialog);
			chooser.addActionListener(e -> {
				if (!JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand())) {
					dialog.dispose(); // cancel button
					return;
				}
				File chosen = chooser.getSelectedFile();
				File exe = exeIn(chosen == null ? null : chosen.getAbsolutePath());
				if (exe == null) {
					JDialog error = new JOptionPane("No ULTRAKILL.exe in:\n" + chosen
						+ "\n\nPick the folder that contains the game.", JOptionPane.ERROR_MESSAGE).createDialog(dialog, "Ultracraft");
					error.setAlwaysOnTop(true);
					error.setVisible(true); // stays open: pick again
					return;
				}
				save(chosen);
				out[0] = exe;
				dialog.dispose();
			});
			dialog.setVisible(true); // blocks this thread only (Swing's), never the game
		};
		try {
			// invokeAndWait needs a thread that is not Swing's own; from Swing itself just run it (the modal
			// dialog below keeps pumping events)
			if (SwingUtilities.isEventDispatchThread()) ask.run();
			else SwingUtilities.invokeAndWait(ask);
		} catch (Throwable t) {
			System.err.println("[Ultracraft] folder dialog: " + t);
			t.printStackTrace();
		}
		return out[0];
	}

	/**
	 * Minecraft keeps Java headless (java.awt.headless=true), a mode where no window can be shown at all: the
	 * dialog would never appear and the button would look dead. Switch back to the desktop first, including the
	 * flag Java already worked out before the property changed. Returns false when even that fails.
	 */
	private static boolean onDesktop() {
		System.setProperty("java.awt.headless", "false");
		if (!GraphicsEnvironment.isHeadless()) return true; // first touch of AWT: the property above decided
		try {
			// the flag was already calculated (as headless) before we could change the property: rewrite it
			java.lang.reflect.Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			theUnsafe.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
			java.lang.reflect.Field headless = GraphicsEnvironment.class.getDeclaredField("headless");
			unsafe.putObject(unsafe.staticFieldBase(headless), unsafe.staticFieldOffset(headless), Boolean.FALSE);
			if (!GraphicsEnvironment.isHeadless()) return true;
		} catch (Throwable t) {
			System.err.println("[Ultracraft] folder dialog: cannot leave headless mode: " + t);
		}
		System.err.println("[Ultracraft] folder dialog: Java stays headless, no dialog can be shown");
		return false;
	}

	/** Escape closes the dialog like its Cancel button (the game window keeps the keyboard focus otherwise). */
	private static void cancelOnEscape(JDialog dialog) {
		JRootPane root = dialog.getRootPane();
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "ultracraft.cancel");
		root.getActionMap().put("ultracraft.cancel", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				dialog.dispose();
			}
		});
	}

	/** The game exe: from the saved folder, else asked for now (first run) and saved. */
	static File findOrAsk() {
		File exe = find();
		return exe != null ? exe : pickAndSave();
	}
}
