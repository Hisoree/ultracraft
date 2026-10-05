package dev.ultracraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ultracraft's settings: which enemies come (Minecraft's monsters, ULTRAKILL's, its bosses), what breaks blocks, how
 * long impact frames last, and ULTRAKILL's own settings (sensitivity, field of view, screen shake...) as Ultracraft
 * plays it. Opened with the "Ultracraft" button on the pause menu, the title screen and Minecraft's Options.
 */
public final class UcSettingsScreen extends OptionsSubScreen {
	/** One of ULTRAKILL's settings: its pref name, its type (f float, i int, b bool) and its default. */
	record UkPref(String key, char type, String caption, String def, int min, int max, String tooltip) {}

	static final List<UkPref> UK_PREFS = List.of(
		new UkPref("mouseSensitivity", 'f', "Mouse Sensitivity", "50", 1, 100, "ULTRAKILL's mouse sensitivity (its default is 50)."),
		new UkPref("fieldOfView", 'f', "Field of View", "105", 60, 160, "ULTRAKILL's field of view; Minecraft's world is drawn to match."),
		new UkPref("screenShake", 'f', "Screen Shake", "1", 0, 100, "How hard explosions and hits shake the camera."),
		new UkPref("cameraTilt", 'b', "Camera Tilt", "true", 0, 1, "The camera leans into strafes and slides."),
		new UkPref("parryFlash", 'b', "Parry Flash", "true", 0, 1, "The white flash on a parry."),
		new UkPref("weaponHoldPosition", 'i', "Weapon Position", "0", 0, 2, "Which side V1 holds the guns on."),
		new UkPref("mouseReverseY", 'b', "Invert Mouse Y", "false", 0, 1, "Mouse up looks down."),
		new UkPref("bloodEnabled", 'b', "Blood", "true", 0, 1, "ULTRAKILL's blood (it still heals V1 with this off)."),
		new UkPref("allVolume", 'f', "ULTRAKILL Volume", "1", 0, 100, "The volume of everything ULTRAKILL plays."),
		new UkPref("musicVolume", 'f', "ULTRAKILL Music", "0.6", 0, 100, "ULTRAKILL's music (boss fights, the Cyber Grind)."));

	/** What ULTRAKILL said its settings are (UKPREFS), for the ones Ultracraft hasn't set itself. */
	static final Map<String, String> reported = new HashMap<>();

	private static final Logger LOGGER = LoggerFactory.getLogger("Ultracraft");

	public UcSettingsScreen(Screen last) {
		super(last, Minecraft.getInstance().options, Component.literal("Ultracraft Settings"));
		LOGGER.info("Settings screen opened");
	}

	/** The "Ultracraft" button on the pause menu, the title screen and Minecraft's Options. */
	static void register() {
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (screen instanceof PauseScreen || screen instanceof OptionsScreen || screen instanceof TitleScreen) {
				// next frame + catch: opening must never fail silently (a broken screen used to look like a dead button)
				Screens.getButtons(screen).add(Button.builder(Component.literal("Ultracraft..."), b -> mc.execute(() -> {
					try {
						mc.setScreen(new UcSettingsScreen(screen));
					} catch (Throwable t) {
						LOGGER.error("Could not open the Ultracraft settings screen", t);
					}
				})).bounds(4, 4, 90, 20).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Ultracraft and ULTRAKILL settings"))).build());
			}
		});
	}

	/** UKPREFS key=type:value;...: ULTRAKILL's own settings as it has them. */
	static void reported(String data) {
		for (String rec : data.split(";")) {
			int eq = rec.indexOf('=');
			if (eq > 0) reported.put(rec.substring(0, eq), rec.substring(eq + 1));
		}
	}

	@Override
	protected void addOptions() {
		list.addSmall(
			bool("Minecraft Mobs", "Minecraft's monsters spawn. Bosses (the Ender Dragon, the Wither, Elder Guardians, the Warden) always do.",
				UltracraftConfig.mcMobs, v -> UltracraftConfig.mcMobs = v),
			bool("ULTRAKILL Enemies", "ULTRAKILL's enemies spawn in the dark, like Minecraft's monsters. Its bosses and the Cyber Grind still come.",
				UltracraftConfig.ukSpawns, v -> {
					UltracraftConfig.ukSpawns = v;
					CyberGrind.sendState();
				}),
			bool("ULTRAKILL Bosses", "ULTRAKILL's bosses come for V1 now and then, with a warning first.", UltracraftConfig.bosses, v -> UltracraftConfig.bosses = v),
			bool("Become V1 Automatically", "Become V1 as soon as ULTRAKILL is ready (otherwise F8).", UltracraftConfig.autoV1, v -> UltracraftConfig.autoV1 = v),
			bool("V1 Breaks Blocks", "V1's guns, punches, slams and blasts break blocks.", UltracraftConfig.playerBlockDamage, v -> UltracraftConfig.playerBlockDamage = v),
			bool("Enemies Break Blocks", "ULTRAKILL's enemies' shots, beams, blasts and fire break blocks.", UltracraftConfig.enemyBlockDamage,
				v -> UltracraftConfig.enemyBlockDamage = v),
			slider("Impact Frames", "How long ULTRAKILL's impact frames (the freeze on big hits) last: 0.1x to 3x.", 1, 30, Math.round(UltracraftConfig.impactFrames * 10f),
				v -> String.format(Locale.ROOT, "%.1fx", v / 10f), v -> UltracraftConfig.impactFrames = v / 10f),
			slider("ULTRAKILL Resolution", "How tall ULTRAKILL draws its picture (scaled up to fill the window). Lower = much faster; this is the biggest frame rate setting.",
				0, 6, resIndex(), v -> RES_NAMES[v], v -> UltracraftConfig.v1Height = RES[v]),
			slider("ULTRAKILL FPS Cap", "How many frames a second ULTRAKILL draws. It shares the graphics card with Minecraft: a lower cap leaves Minecraft more.",
				3, 24, UltracraftConfig.ukFps / 10, v -> v * 10 + " FPS", v -> UltracraftConfig.ukFps = v * 10),
			bool("OP Shop", "The shop's Upgrades page goes much further: every weapon's and arm's Power up to 1500%, and blast sizes (Payload, Shockwave, the mini nuke) up to 1500%.",
				UltracraftConfig.opShop, v -> {
					UltracraftConfig.opShop = v;
					resendUpgrades();
				}),
			bool("Sharp Shop Screen", "Using a shop, ULTRAKILL draws at full resolution so the text is sharp (costs frames while you use it).",
				UltracraftConfig.sharpShop, v -> UltracraftConfig.sharpShop = v),
			bool("Start ULTRAKILL", "Starting Minecraft starts ULTRAKILL too (straight from the folder you chose), and closing Minecraft closes it.", UltracraftConfig.launchUltrakill,
				v -> UltracraftConfig.launchUltrakill = v));
		List<OptionInstance<?>> uk = new ArrayList<>();
		for (UkPref p : UK_PREFS) uk.add(ukOption(p));
		list.addSmall(uk.toArray(new OptionInstance[0]));
	}

	/** The folder button, held so the dialog can disable it while it is open. */
	private Button folderButton;

	@Override
	protected void init() {
		super.init();
		// the folder ULTRAKILL runs from: click to pick another (no Steam involved). Sits on the footer row, left of
		// Minecraft's Done button (200 wide, centered), so neither widget eats the other's clicks
		int doneLeft = (width - 200) / 2;
		int bw = Math.max(90, Math.min(250, doneLeft - 8));
		folderButton = addRenderableWidget(Button.builder(Component.literal(folderLabel()), b -> pickFolder())
			.bounds(4, height - 24, bw, 20)
			.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(folderTip()))).build());
	}

	/** The saved ULTRAKILL folder, shortened to fit the button (the tooltip always shows the full path). */
	private static String folderLabel() {
		String path = UkPath.saved();
		if (path == null) return "ULTRAKILL folder...";
		int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		String name = cut >= 0 && cut + 1 < path.length() ? path.substring(cut + 1) : path;
		return name.length() > 22 ? "ULTRAKILL folder..." : "..." + name;
	}

	private static String folderTip() {
		String path = UkPath.saved();
		return path == null ? "No ULTRAKILL folder chosen yet: click to pick the one containing ULTRAKILL.exe."
			: "ULTRAKILL runs from: " + path + " (click to pick another folder).";
	}

	/**
	 * Opens the folder dialog off the game's thread: Minecraft keeps rendering and the dialog (always on top) shows
	 * above the fullscreen game. The button says so while it waits, and the screen refreshes once a folder is chosen.
	 */
	private void pickFolder() {
		if (folderButton == null || !folderButton.active) return;
		folderButton.active = false;
		folderButton.setMessage(Component.literal("Choosing folder..."));
		LOGGER.info("Folder dialog opened");
		Minecraft mc = Minecraft.getInstance();
		Screen parent = this.lastScreen;
		UkPath.pickAsync(exe -> mc.execute(() -> {
			if (mc.screen != this) return; // the player closed the screen meanwhile; the path is saved either way
			if (exe != null) {
				LOGGER.info("Folder chosen: {}", exe.getParentFile());
				mc.setScreen(new UcSettingsScreen(parent));
			} else {
				folderButton.active = true;
				folderButton.setMessage(Component.literal(folderLabel()));
			}
		}));
	}

	@Override
	public void removed() {
		super.removed();
		UltracraftConfig.save();
	}

	private static final int[] RES = {360, 480, 540, 720, 900, 1080, 0};
	private static final String[] RES_NAMES = {"360p", "480p", "540p", "720p", "900p", "1080p", "Full"};

	private static int resIndex() {
		for (int i = 0; i < RES.length; i++) if (RES[i] == UltracraftConfig.v1Height) return i;
		return 3;
	}

	/** The OP Shop changed: every player's Upgrades page hears its new levels. */
	private static void resendUpgrades() {
		var server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) return;
		server.execute(() -> {
			for (var sp : server.getPlayerList().getPlayers()) UkProgress.get(sp).sendUpgrades();
		});
	}

	private static OptionInstance<Boolean> bool(String caption, String tooltip, boolean value, Consumer<Boolean> set) {
		return OptionInstance.createBoolean(caption, OptionInstance.cachedConstantTooltip(Component.literal(tooltip)), value, v -> {
			set.accept(v);
			UltracraftConfig.sendOpts();
		});
	}

	private static OptionInstance<Integer> slider(String caption, String tooltip, int min, int max, int value, java.util.function.IntFunction<String> label,
			Consumer<Integer> set) {
		return new OptionInstance<>(caption, OptionInstance.cachedConstantTooltip(Component.literal(tooltip)),
			(c, v) -> Options.genericValueLabel(c, Component.literal(label.apply(v))), new OptionInstance.IntRange(min, max), Math.max(min, Math.min(max, value)), v -> {
				set.accept(v);
				UltracraftConfig.sendOpts();
			});
	}

	/** ULTRAKILL's setting as Ultracraft has it, else as ULTRAKILL reported it, else its default ("f:50"). */
	static String current(UkPref p) {
		String v = UltracraftConfig.ukPrefs.get(p.key);
		if (v == null) v = reported.get(p.key);
		if (v == null) v = p.type + ":" + p.def;
		int c = v.indexOf(':');
		return c >= 0 ? v.substring(c + 1) : v;
	}

	private static void setUk(UkPref p, String value) {
		String v = p.type + ":" + value;
		UltracraftConfig.ukPrefs.put(p.key, v);
		UkLink.send("UKPREF " + p.key + " " + v);
	}

	private static OptionInstance<?> ukOption(UkPref p) {
		String now = current(p);
		if (p.type == 'b') {
			return OptionInstance.createBoolean(p.caption, OptionInstance.cachedConstantTooltip(Component.literal(p.tooltip + " (ULTRAKILL's own settings stay as they are.)")),
				Boolean.parseBoolean(now), v -> setUk(p, Boolean.toString(v)));
		}
		// fractions (screen shake, volumes) show as percentages
		boolean percent = p.max == 100 && p.min == 0;
		float f;
		try {
			f = Float.parseFloat(now);
		} catch (NumberFormatException e) {
			f = Float.parseFloat(p.def);
		}
		int value = Math.round(percent ? f * 100f : f);
		java.util.function.IntFunction<String> label = p.key.equals("weaponHoldPosition") ? v -> v == 0 ? "Right" : v == 1 ? "Middle" : "Left"
			: percent ? v -> v + "%" : Integer::toString;
		return slider(p.caption, p.tooltip + " (ULTRAKILL's own settings stay as they are.)", p.min, p.max, value, label,
			v -> setUk(p, p.type == 'i' ? Integer.toString(v) : percent ? String.format(Locale.ROOT, "%.2f", v / 100f) : String.format(Locale.ROOT, "%.1f", (float) v)));
	}
}
