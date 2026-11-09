package com.jpc.client;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.fabricmc.loader.api.FabricLoader;

import com.jpc.JpcMod;
import com.jpc.vm.BootSetup;
import com.jpc.vm.CpuArch;
import com.jpc.vm.JpcMachine;
import com.jpc.vm.VmConfig;
import com.jpc.vm.VmException;

/**
 * Settings file: config/jpc.json (created on first use).
 *
 * mode "builtin" (default): the computer runs inside the mod. 32-bit x86 only. Works on every
 *                           platform, including MojoLauncher on Android. Needs "iso", "floppy" or "hdImage".
 * mode "qemu":   optional, desktop only. Starts QEMU, so it needs QEMU installed, but allows 64-bit guests.
 * mode "remote": optional. Connects to a VNC server that is already running.
 *
 * Relative file paths are relative to the game folder.
 */
public final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public String mode = "builtin";

	// ---- built-in emulator
	/** CD-ROM image (.iso). */
	public String iso = "";
	/** Floppy image (.img). */
	public String floppy = "";
	/** Raw hard disk image. The computer writes into this file. */
	public String hdImage = "";
	/** "auto" (CD, then hard disk, then floppy), "cdrom", "disk" or "floppy". */
	public String boot = "auto";
	public int ramMb = 64;
	/**
	 * Show the boot menu when the computer block is used while the computer is off. The menu lists the images in
	 * {@link #isoFolder} and sets RAM and CPU limit. false = start straight from "iso" / "floppy" / "hdImage".
	 */
	public boolean showBootMenu = true;
	/** Folder (inside the game folder, or a full path) where you put .iso and other disk images. */
	public String isoFolder = "isos";
	/** Freeze the computer while its screen is closed, so it does not slow the game. */
	public boolean pauseWhenClosed = true;
	/**
	 * Most of one CPU core the emulator thread may use, 10-100. Lower it (try 60-70) if the game stutters while a
	 * busy computer runs; the computer then runs slower but the game gets room to breathe.
	 */
	public int maxCpuPercent = 100;
	/**
	 * Show a yellow line on the computer screen for a few seconds saying what the last key did (and log it). Only for
	 * finding out why a keyboard does not work, so it is off by default. The Keys keyboard has a button for it too.
	 */
	public boolean showKeyHints = false;

	// ---- qemu mode only
	/** "x86" (32-bit) or "x86_64" (64-bit). */
	public String arch = "x86_64";
	/** qcow2 disk, created if missing. */
	public String disk = "";
	public int diskSizeGb = 8;
	public int cpus = 2;
	/** Folder containing the qemu binaries. Empty = search PATH. */
	public String qemuDir = "";

	// ---- remote mode only
	public String remoteHost = "127.0.0.1";
	public int remotePort = 5901;

	/** The settings file used before the mod was renamed from IsoCraft to JPC. Read once, if there is no jpc.json yet. */
	private static final String OLD_FILE_NAME = "isocraft.json";

	public static ClientConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("jpc.json");
		Path oldFile = FabricLoader.getInstance().getConfigDir().resolve(OLD_FILE_NAME);
		// A player coming from IsoCraft keeps their settings: they are read from the old file and saved as jpc.json.
		boolean fromOld = !Files.exists(file) && Files.isRegularFile(oldFile);
		Path source = fromOld ? oldFile : file;
		ClientConfig cfg = null;

		if (Files.isRegularFile(source)) {
			try (Reader reader = Files.newBufferedReader(source)) {
				cfg = GSON.fromJson(reader, ClientConfig.class);
			} catch (IOException | JsonParseException e) {
				JpcMod.LOGGER.warn("Could not read {}: {}", source, e.getMessage());
			}
		}

		boolean create = (cfg == null && !Files.exists(file)) || (fromOld && cfg != null);
		if (cfg == null) {
			cfg = new ClientConfig();
		}
		if (cfg.mode == null || cfg.mode.isBlank()) {
			cfg.mode = "builtin";
		}

		if (create) {
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, GSON.toJson(cfg));
			} catch (IOException e) {
				JpcMod.LOGGER.warn("Could not write {}: {}", file, e.getMessage());
			}
		}
		return cfg;
	}

	/** Writes the settings back to config/jpc.json. Returns false if that failed. */
	public boolean save() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("jpc.json");
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this));
			return true;
		} catch (IOException e) {
			JpcMod.LOGGER.warn("Could not write {}: {}", file, e.getMessage());
			return false;
		}
	}

	/** The folder with the disk images. */
	public Path isoDir() {
		return resolve(blank(isoFolder) ? "isos" : isoFolder);
	}

	/** The image the settings boot from (what the boot menu preselects), or null. */
	public Path pickedImage() {
		String b = boot == null ? "auto" : boot.toLowerCase(Locale.ROOT);
		Path cd = resolve(iso);
		Path fd = resolve(floppy);
		Path hd = resolve(hdImage);
		return switch (b) {
			case "disk", "hdd", "hd", "hda" -> hd != null ? hd : cd != null ? cd : fd;
			case "floppy", "fda" -> fd != null ? fd : cd != null ? cd : hd;
			default -> cd != null ? cd : hd != null ? hd : fd;
		};
	}

	/** The extra hard disk the settings attach next to a CD or floppy, or null. */
	public Path pickedDisk() {
		Path image = pickedImage();
		Path hd = resolve(hdImage);
		return hd != null && !hd.equals(image) ? hd : null;
	}

	/** Copies the boot menu's choice into the settings. */
	public void applyLaunch(BootSetup.Launch l) {
		iso = "";
		floppy = "";
		hdImage = "";
		switch (l.boot().kind()) {
			case CDROM -> {
				iso = stored(l.boot().path());
				boot = "cdrom";
			}
			case FLOPPY -> {
				floppy = stored(l.boot().path());
				boot = "floppy";
			}
			case DISK -> {
				hdImage = stored(l.boot().path());
				boot = "disk";
			}
		}
		if (l.disk() != null) {
			hdImage = stored(l.disk().path());
		}
		ramMb = l.ramMb();
		maxCpuPercent = l.cpuPercent();
	}

	/** Path as written in the settings: relative to the game folder when inside it. */
	private static String stored(Path p) {
		Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		Path abs = p.toAbsolutePath().normalize();
		return abs.startsWith(game) ? game.relativize(abs).toString().replace('\\', '/') : abs.toString();
	}

	public boolean isRemote() {
		return "remote".equalsIgnoreCase(mode);
	}

	public boolean isQemu() {
		return "qemu".equalsIgnoreCase(mode);
	}

	public JpcMachine.Config toBuiltinConfig() throws VmException {
		Path isoPath = resolve(iso);
		Path floppyPath = resolve(floppy);
		Path hdPath = resolve(hdImage);
		if (isoPath == null && floppyPath == null && hdPath == null) {
			throw new VmException("Set \"iso\" (a .iso file) in config/jpc.json, in the game folder's config folder");
		}

		String b = boot == null ? "auto" : boot.toLowerCase(Locale.ROOT);
		JpcMachine.Boot bootDevice = switch (b) {
			case "cdrom", "cd", "iso" -> JpcMachine.Boot.CDROM;
			case "disk", "hdd", "hd", "hda" -> JpcMachine.Boot.HARD_DISK;
			case "floppy", "fda" -> JpcMachine.Boot.FLOPPY;
			default -> null;
		};
		int ram = Math.max(8, ramMb);
		if (bootDevice == null) {
			return JpcMachine.Config.auto(isoPath, floppyPath, hdPath, ram, maxCpuPercent);
		}
		return new JpcMachine.Config(isoPath, floppyPath, hdPath, bootDevice, ram, maxCpuPercent);
	}

	public VmConfig toQemuConfig() throws VmException {
		String a = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
		CpuArch cpuArch = switch (a) {
			case "x86", "i386", "32", "32bit" -> CpuArch.X86;
			default -> CpuArch.X86_64;
		};

		Path isoPath = resolve(iso);
		Path diskPath = resolve(disk);
		if (isoPath == null && diskPath == null) {
			throw new VmException("Set \"iso\" (path to an .iso file) in config/jpc.json");
		}
		Path qemu = blank(qemuDir) ? null : Path.of(qemuDir);

		return new VmConfig(cpuArch, Math.max(16, ramMb), Math.max(1, cpus), isoPath, diskPath, Math.max(1, diskSizeGb), qemu);
	}

	private static Path resolve(String path) {
		return blank(path) ? null : FabricLoader.getInstance().getGameDir().resolve(path);
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}
}
