package com.jpc.vm;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Starts and stops a QEMU process for one {@link VmConfig}.
 *
 * QEMU is run without its own window; the screen is exposed over VNC on
 * localhost only, and {@link VncClient} reads it from there.
 */
public final class QemuProcess implements AutoCloseable {
	private static final System.Logger LOG = System.getLogger("jpc.vm");
	private static final boolean WINDOWS = osName().contains("win");

	private final VmConfig config;
	private final Path logFile;

	private Process process;
	private Thread shutdownHook;
	private int vncPort = -1;

	public QemuProcess(VmConfig config, Path logFile) {
		this.config = config;
		this.logFile = logFile;
	}

	public void start() throws VmException {
		if (process != null) {
			throw new VmException("VM already started");
		}

		Path qemu = findExecutable(config.arch().binary, config.qemuDir())
				.orElseThrow(() -> new VmException(
						"QEMU not found (" + config.arch().binary + "). Install QEMU and make sure it is on PATH."));

		if (config.iso() != null && !Files.isRegularFile(config.iso())) {
			throw new VmException("ISO file not found: " + config.iso());
		}

		ensureDisk(qemu);

		int display = pickFreeVncDisplay();
		vncPort = 5900 + display;

		List<String> cmd = buildCommand(qemu.toString(), config, display, defaultAccel());
		LOG.log(System.Logger.Level.INFO, "Starting VM: " + String.join(" ", cmd));

		try {
			if (logFile.getParent() != null) {
				Files.createDirectories(logFile.getParent());
			}
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			pb.redirectOutput(logFile.toFile());
			process = pb.start();
		} catch (IOException e) {
			throw new VmException("Could not start QEMU: " + e.getMessage(), e);
		}

		// Never leave a VM running if the game is killed.
		shutdownHook = new Thread(this::destroyNow, "jpc-qemu-shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
	}

	/** Port QEMU's VNC server listens on (127.0.0.1). Valid after {@link #start()}. */
	public int vncPort() {
		return vncPort;
	}

	public boolean isAlive() {
		return process != null && process.isAlive();
	}

	/** Exit code, or -1 if the process has not exited or never started. */
	public int exitCode() {
		if (process == null || process.isAlive()) {
			return -1;
		}
		return process.exitValue();
	}

	public Path logFile() {
		return logFile;
	}

	@Override
	public void close() {
		destroyNow();
		if (shutdownHook != null) {
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
				// JVM is already shutting down.
			}
			shutdownHook = null;
		}
	}

	private void destroyNow() {
		Process p = process;
		if (p == null || !p.isAlive()) {
			return;
		}
		p.destroy();
		try {
			if (!p.waitFor(3, TimeUnit.SECONDS)) {
				p.destroyForcibly();
			}
		} catch (InterruptedException e) {
			p.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	// ---------------------------------------------------------------- command

	/** Builds the QEMU command line. Public so it can be unit tested without QEMU. */
	public static List<String> buildCommand(String qemuBinary, VmConfig c, int vncDisplay, String accel) {
		List<String> cmd = new ArrayList<>();
		cmd.add(qemuBinary);

		// Hardware acceleration when the host allows it, software emulation otherwise.
		cmd.add("-machine");
		cmd.add("pc,accel=" + accel);
		// "max" works with both hardware acceleration and software emulation.
		cmd.add("-cpu");
		cmd.add("max");
		cmd.add("-m");
		cmd.add(Integer.toString(c.ramMb()));
		cmd.add("-smp");
		cmd.add(Integer.toString(c.cpus()));

		// No QEMU window. Screen goes to a local-only VNC server.
		cmd.add("-display");
		cmd.add("none");
		cmd.add("-vnc");
		cmd.add("127.0.0.1:" + vncDisplay);
		cmd.add("-vga");
		cmd.add("std");

		// Absolute pointer so the mouse lines up with the VNC pointer.
		cmd.add("-usb");
		cmd.add("-device");
		cmd.add("usb-tablet");

		cmd.add("-nic");
		cmd.add("user");

		if (c.disk() != null) {
			cmd.add("-drive");
			cmd.add("file=" + c.disk() + ",format=qcow2,if=ide,index=0,media=disk");
		}
		if (c.iso() != null) {
			cmd.add("-drive");
			cmd.add("file=" + c.iso() + ",format=raw,if=ide,index=2,media=cdrom,readonly=on");
		}
		cmd.add("-boot");
		cmd.add(c.iso() != null ? "order=dc" : "order=c");

		return cmd;
	}

	/** Accelerator list for the current OS; falls back to software (tcg). */
	public static String defaultAccel() {
		String os = osName();
		if (os.contains("win")) {
			return "whpx:tcg";
		}
		if (os.contains("mac")) {
			return "hvf:tcg";
		}
		return "kvm:tcg";
	}

	// ------------------------------------------------------------------ disk

	private void ensureDisk(Path qemu) throws VmException {
		Path disk = config.disk();
		if (disk == null || Files.exists(disk)) {
			return;
		}
		Path img = findExecutable("qemu-img", qemu.getParent())
				.orElseThrow(() -> new VmException("qemu-img not found, cannot create the virtual disk."));
		try {
			if (disk.getParent() != null) {
				Files.createDirectories(disk.getParent());
			}
			Process p = new ProcessBuilder(
					img.toString(), "create", "-f", "qcow2", disk.toString(), config.diskSizeGb() + "G")
					.redirectErrorStream(true)
					.start();
			byte[] out = p.getInputStream().readAllBytes();
			if (p.waitFor() != 0) {
				throw new VmException("qemu-img failed: " + new String(out).trim());
			}
		} catch (IOException e) {
			throw new VmException("Could not create disk: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new VmException("Interrupted while creating disk", e);
		}
	}

	// -------------------------------------------------------------- helpers

	private static int pickFreeVncDisplay() throws VmException {
		for (int display = 10; display < 100; display++) {
			try (ServerSocket s = new ServerSocket(5900 + display, 1, InetAddress.getLoopbackAddress())) {
				return display;
			} catch (IOException ignored) {
				// port busy, try the next one
			}
		}
		throw new VmException("No free VNC port found (5910-5999).");
	}

	/** Looks for an executable in hintDir, then PATH, then common install folders. */
	public static java.util.Optional<Path> findExecutable(String name, Path hintDir) {
		List<Path> dirs = new ArrayList<>();
		if (hintDir != null) {
			dirs.add(hintDir);
		}
		String pathEnv = System.getenv("PATH");
		if (pathEnv != null) {
			for (String part : pathEnv.split(File.pathSeparator)) {
				if (!part.isBlank()) {
					dirs.add(Path.of(part));
				}
			}
		}
		if (WINDOWS) {
			dirs.add(Path.of("C:\\Program Files\\qemu"));
		} else {
			dirs.add(Path.of("/usr/bin"));
			dirs.add(Path.of("/usr/local/bin"));
			dirs.add(Path.of("/opt/homebrew/bin"));
		}

		String fileName = WINDOWS ? name + ".exe" : name;
		for (Path dir : dirs) {
			Path candidate = dir.resolve(fileName);
			if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
				return java.util.Optional.of(candidate);
			}
		}
		return java.util.Optional.empty();
	}

	private static String osName() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
	}
}
