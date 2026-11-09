package com.jpc.vm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jpc.emulator.PC;
import org.jpc.emulator.pci.peripheral.HeadlessVGACard;
import org.jpc.emulator.peripheral.Keyboard;
import org.jpc.j2se.VirtualClock;

/**
 * The built-in computer: a 32-bit x86 PC emulator (JPC) that runs on its own thread
 * inside the game. No QEMU, no other app, no native code, so it works anywhere Java runs,
 * including MojoLauncher on Android.
 *
 * Limits: 32-bit x86 only (no 64-bit guests), no sound, no network, and it is a
 * plain interpreter, so it is far slower than a real PC (a modern phone is slower still).
 */
public final class JpcMachine implements VmDisplay {
	private static final System.Logger LOG = System.getLogger("jpc.vm");

	public enum Boot { CDROM, HARD_DISK, FLOPPY }

	/**
	 * @param iso     CD-ROM image, or null
	 * @param floppy  floppy image, or null
	 * @param hdImage raw hard disk image, or null. The guest writes into this file.
	 * @param boot    which drive to boot from
	 * @param ramMb   guest RAM in megabytes
	 * @param cpuPercent most of one CPU core the emulator thread may use, 10-100. Lower it if the game stutters:
	 *                the thread then sleeps after every slice of work, which also leaves the core cooler.
	 */
	public record Config(Path iso, Path floppy, Path hdImage, Boot boot, int ramMb, int cpuPercent) {
		public Config {
			if (iso == null && floppy == null && hdImage == null) {
				throw new IllegalArgumentException("need an iso, a floppy image or a hard disk image");
			}
			if (boot == null) {
				throw new IllegalArgumentException("boot is required");
			}
			cpuPercent = Math.max(10, Math.min(100, cpuPercent));
		}

		/** Full speed: no cap on the share of a CPU core the emulator thread may use. */
		public Config(Path iso, Path floppy, Path hdImage, Boot boot, int ramMb) {
			this(iso, floppy, hdImage, boot, ramMb, 100);
		}

		/** Boot from the first thing that is present: CD, then hard disk, then floppy. */
		public static Config auto(Path iso, Path floppy, Path hdImage, int ramMb) {
			return auto(iso, floppy, hdImage, ramMb, 100);
		}

		public static Config auto(Path iso, Path floppy, Path hdImage, int ramMb, int cpuPercent) {
			Boot boot = iso != null ? Boot.CDROM : hdImage != null ? Boot.HARD_DISK : Boot.FLOPPY;
			return new Config(iso, floppy, hdImage, boot, ramMb, cpuPercent);
		}
	}

	private static final long PUBLISH_INTERVAL_NS = 33_000_000L; // at most ~30 screen updates per second
	/** Drawing the screen may use at most about 1/(this+1) of the emulator thread's time. */
	private static final long PUBLISH_COST_FACTOR = 7;
	/** The emulator runs in slices of this length, then lets the screen update, the stats tick and the OS schedule. */
	private static final long SLICE_NS = 4_000_000L;
	private static final long STATS_INTERVAL_NS = 1_000_000_000L;
	private static final long LOG_EVERY_STATS = 10;

	private final PC pc;
	private final HeadlessVGACard vga;
	private final Keyboard keyboard;
	private final Thread thread;
	private final int cpuPercent;
	private final AtomicLong version = new AtomicLong();

	private volatile boolean running = true;
	private volatile boolean paused;
	private volatile String error;
	private volatile VmFrame front;
	private VmFrame back;

	// Speed readout (written by the emulator thread, read by the game).
	private volatile double mips;
	private volatile long publishCostNs;

	// Pointer: the guest mouse is relative (PS/2), so remember where it was.
	private int lastX = -1;
	private int lastY = -1;

	private JpcMachine(PC pc, HeadlessVGACard vga, Keyboard keyboard, int cpuPercent) {
		this.pc = pc;
		this.cpuPercent = cpuPercent;
		this.vga = vga;
		this.keyboard = keyboard;
		this.thread = new Thread(this::run, "jpc-x86");
		this.thread.setDaemon(true);
		// Behind the game's render thread, so the emulator cannot starve it.
		this.thread.setPriority(Thread.NORM_PRIORITY - 1);
	}

	/** Builds the machine and starts it. Reads the BIOS and opens the images, so call it off the game thread. */
	public static JpcMachine start(Config config) throws VmException {
		// JPC logs BIOS chatter at INFO; keep the game log clean.
		Logger.getLogger("org.jpc").setLevel(Level.WARNING);

		int ramMb = Math.max(8, Math.min(1024, config.ramMb()));
		List<String> args = new ArrayList<>();
		args.add("-boot");
		args.add(switch (config.boot()) {
			case CDROM -> "cdrom";
			case HARD_DISK -> "hda";
			case FLOPPY -> "fda";
		});
		addDrive(args, "-fda", config.floppy(), "floppy image");
		addDrive(args, "-hda", config.hdImage(), "hard disk image");
		addDrive(args, "-cdrom", config.iso(), "ISO");

		try {
			PC pc = new PC(new VirtualClock(), args.toArray(new String[0]), ramMb * 1024 * 1024);
			HeadlessVGACard vga = (HeadlessVGACard) pc.getComponent(HeadlessVGACard.class);
			Keyboard keyboard = (Keyboard) pc.getComponent(Keyboard.class);
			if (vga == null || keyboard == null) {
				throw new VmException("Emulator started without a screen or keyboard");
			}
			JpcMachine m = new JpcMachine(pc, vga, keyboard, config.cpuPercent());
			m.thread.start();
			return m;
		} catch (IOException | RuntimeException e) {
			throw new VmException("Could not start the built-in emulator: " + e, e);
		}
	}

	private static void addDrive(List<String> args, String flag, Path file, String what) throws VmException {
		if (file == null) {
			return;
		}
		if (!Files.isRegularFile(file)) {
			throw new VmException("The " + what + " was not found: " + file);
		}
		String spec = file.toAbsolutePath().toString();
		// JPC reads "xxx:" at the start of a drive path as a device type. A Windows drive letter is fine.
		if (spec.indexOf(':') >= 0 && !spec.matches("^[A-Za-z]:\\\\.*")) {
			throw new VmException("The " + what + " path must not contain ':' (" + spec + ")");
		}
		args.add(flag);
		args.add(spec);
	}

	// ------------------------------------------------------------------ thread

	/**
	 * The emulator thread. It never touches the game: it only runs the guest, draws the guest's screen into a
	 * {@link VmFrame} that the game picks up, and counts speed.
	 *
	 * <p>One pass of the loop is one slice (about {@value #SLICE_NS} ns) of guest execution. Between slices it
	 * <ul>
	 * <li>freezes completely while paused (screen closed, game window without focus): guest clock stopped, thread asleep;</li>
	 * <li>updates the picture, at most 30 times a second and less often when drawing is costly;</li>
	 * <li>gives the OS a chance to run the game: a plain yield, or a sleep that caps the thread at
	 *     {@code cpuPercent} of one core.</li>
	 * </ul>
	 * A guest that is idle (HLT) sleeps inside the emulator until its next timer, so it costs almost nothing.
	 */
	private void run() {
		try {
			pc.start();
			boolean frozen = false;
			long nextPublish = 0;
			long instr = 0;
			long statsStart = System.nanoTime();
			long statsInstr = 0;
			long statsCount = 0;
			// A halted guest executes almost nothing, so show the best of the last 10 seconds, not the latest second.
			double[] recent = new double[10];
			while (running) {
				if (paused) {
					if (!frozen) {
						pc.stop();   // freezes the guest clock, so no burst of timer ticks on resume
						frozen = true;
					}
					Thread.sleep(40);
					statsStart = System.nanoTime();
					statsInstr = instr;
					continue;
				}
				if (frozen) {
					pc.start();
					frozen = false;
				}

				// ---- one slice of guest execution
				long sliceStart = System.nanoTime();
				long now;
				do {
					instr += pc.execute();
					now = System.nanoTime();
				} while (now - sliceStart < SLICE_NS && running && !paused);

				// ---- screen
				if (now >= nextPublish) {
					publish();
					long end = System.nanoTime();
					publishCostNs = end - now;
					// Big screens are expensive to convert; on a slow phone that could eat most of the CPU time.
					nextPublish = end + Math.max(PUBLISH_INTERVAL_NS, publishCostNs * PUBLISH_COST_FACTOR);
					now = end;
				}

				// ---- speed readout
				if (now - statsStart >= STATS_INTERVAL_NS) {
					double sample = (instr - statsInstr) / ((now - statsStart) / 1e3);
					recent[(int) (statsCount % recent.length)] = sample;
					double best = 0;
					for (double r : recent) {
						best = Math.max(best, r);
					}
					mips = best;
					statsStart = now;
					statsInstr = instr;
					if (++statsCount % LOG_EVERY_STATS == 1) {
						LOG.log(System.Logger.Level.INFO, String.format(
								"VM speed: best of last 10 s %.2f MIPS, screen %dx%d, screen update %.1f ms, %d instructions so far",
								mips, vga.getDisplayWidth(), vga.getDisplayHeight(), publishCostNs / 1e6, instr));
					}
				}

				// ---- leave room for the game
				if (cpuPercent >= 100) {
					Thread.yield();
				} else {
					long worked = System.nanoTime() - sliceStart;
					LockSupport.parkNanos(worked * (100 - cpuPercent) / cpuPercent);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable t) {
			error = "Emulator stopped: " + t;
			LOG.log(System.Logger.Level.WARNING, error, t);
		} finally {
			running = false;
			try {
				pc.stop();
			} catch (Throwable ignored) {
				// already stopping
			}
		}
	}

	/** Draws the guest screen and copies it into a fresh frame for the game to read. */
	private void publish() {
		VmFrame f = composeFrame(vga, front, back);
		if (f == null) {
			return;         // nothing was redrawn: no new picture
		}
		back = front;   // reuse the previous picture's buffer next time
		front = f;
		version.incrementAndGet();
	}

	/**
	 * Lets the VGA card draw, then builds the next picture from {@code prev}, converting only the rows the card
	 * redrew. {@code spare} is an old picture whose buffer may be reused.
	 *
	 * @return the new picture, or null if nothing changed
	 */
	static VmFrame composeFrame(HeadlessVGACard vga, VmFrame prev, VmFrame spare) {
		vga.updateDisplay();
		int w = vga.getDisplayWidth();
		int h = vga.getDisplayHeight();
		int[] src = vga.getDisplayBuffer();
		if (w <= 0 || h <= 0 || src.length < w * h) {
			return null;
		}
		int first = vga.dirtyFirstRow();
		int last = vga.dirtyLastRow();
		vga.clearDirty();
		boolean sameSize = prev != null && prev.width == w && prev.height == h;
		if (sameSize && last < first) {
			return null;        // nothing was redrawn: the previous picture is still right
		}
		boolean reuse = sameSize && prev.texels != null;
		if (!reuse) {
			first = 0;          // new size (or a picture without texels): convert everything
			last = h - 1;
		}
		VmFrame f = spare;
		if (f == null || f.width != w || f.height != h) {
			f = new VmFrame(w, h);
		}
		if (f.texels == null || f.texels.length != w * h) {
			f.texels = new int[w * h];
		}
		int[] dst = f.pixels;
		int[] tex = f.texels;
		if (reuse) {
			// the spare buffer holds an older picture: start from the latest one, then redraw the changed rows
			System.arraycopy(prev.pixels, 0, dst, 0, w * h);
			System.arraycopy(prev.texels, 0, tex, 0, w * h);
		}
		for (int i = first * w, n = (last + 1) * w; i < n; i++) {
			int argb = 0xFF000000 | src[i];
			dst[i] = argb;
			tex[i] = VmFrame.swapRedBlue(argb);
		}
		return f;
	}

	// ------------------------------------------------------------ VmDisplay

	/** The latest picture. Read it right away: its buffer is reused two pictures later. */
	@Override
	public VmFrame frame() {
		return front;
	}

	@Override
	public long frameVersion() {
		return version.get();
	}

	@Override
	public boolean isConnected() {
		return running;
	}

	@Override
	public String error() {
		return error;
	}

	@Override
	public String serverName() {
		return "built-in x86 (JPC)";
	}

	@Override
	public String statusLine() {
		double m = mips;
		int w = vga.getDisplayWidth();
		int h = vga.getDisplayHeight();
		String speed = paused ? "paused" : m <= 0 ? "starting" : m < 10 ? String.format("%.1f MIPS", m) : String.format("%.0f MIPS", m);
		return w > 0 && h > 0 ? speed + ", " + w + "x" + h : speed;
	}

	@Override
	public void setPaused(boolean paused) {
		this.paused = paused;
	}

	@Override
	public void sendKey(int glfwKey, boolean down) {
		byte code = Ps2Keys.scancode(glfwKey);
		if (code == 0 || !running) {
			return;
		}
		if (down) {
			keyboard.keyPressed(code);
		} else {
			keyboard.keyReleased(code);
		}
	}

	@Override
	public synchronized void sendPointer(int buttons, int x, int y) {
		if (!running) {
			return;
		}
		int dx = lastX < 0 ? 0 : x - lastX;
		int dy = lastX < 0 ? 0 : y - lastY;
		lastX = x;
		lastY = y;
		sendMouse(dx, dy, 0, buttons);
	}

	@Override
	public synchronized void sendWheel(boolean up, int buttons, int x, int y) {
		if (!running) {
			return;
		}
		sendMouse(0, 0, up ? -1 : 1, buttons);
	}

	private void sendMouse(int dx, int dy, int dz, int buttons) {
		// VmDisplay bits are left=1, middle=2, right=4. PS/2 wants left=1, right=2, middle=4.
		int ps2 = ((buttons & BUTTON_LEFT) != 0 ? 1 : 0)
				| ((buttons & BUTTON_RIGHT) != 0 ? 2 : 0)
				| ((buttons & BUTTON_MIDDLE) != 0 ? 4 : 0);
		keyboard.putMouseEvent(dx, dy, dz, ps2);
	}

	@Override
	public void close() {
		running = false;
		pc.abort();   // end a guest HLT wait right away
		try {
			thread.join(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
