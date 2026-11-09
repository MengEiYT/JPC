import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import javax.imageio.ImageIO;

import com.jpc.vm.JpcMachine;
import com.jpc.vm.KeyInput;
import com.jpc.vm.Ps2Keys;
import com.jpc.vm.VmDisplay;
import com.jpc.vm.VmFrame;
import com.jpc.vm.VmSession;

/**
 * Boots the built-in emulator on a floppy image and drives it like the game screen would.
 * (This test tool may use AWT to write a PNG. The mod itself does not.)
 *
 * Args: FLOPPY_IMAGE OUT_PREFIX     writes OUT_PREFIX-boot.png and OUT_PREFIX-typed.png
 */
public class JpcSmokeTest {
	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		Path floppy = Path.of(args[0]);
		String out = args[1];

		keyChecks();

		JpcMachine.Config cfg = new JpcMachine.Config(null, floppy, null, JpcMachine.Boot.FLOPPY, 16);
		VmSession session = VmSession.builtin(cfg);
		check("builtin session flags", session.isBuiltin() && !session.isRemote() && session.config() == null);
		session.startAsync();
		check("session reaches RUNNING", waitFor(() -> session.state() == VmSession.State.RUNNING, 30_000));
		VmDisplay d = session.display();
		check("display exists", d != null);

		check("first picture arrives", waitFor(() -> d.frame() != null, 30_000));
		long t0 = System.currentTimeMillis();
		check("pictures keep coming", waitFor(() -> d.frameVersion() >= 10, 30_000));
		System.out.println("       (10 pictures after " + (System.currentTimeMillis() - t0) + " ms)");

		// Wait for the FreeDOS prompt: the screen settles and has plenty of text on it.
		check("boots to a text screen with content", waitFor(() -> litPixels(d.frame()) > 5000, 90_000));
		Thread.sleep(8_000);
		VmFrame before = copy(d.frame());   // the emulator reuses frame buffers, so keep our own copy
		save(before, out + "-boot.png");
		int litBefore = litPixels(before);
		System.out.println("       screen " + before.width + "x" + before.height + ", lit pixels " + litBefore);
		check("screen is VGA text size", before.width == 720 && before.height == 400);

		// Type "ver" + Enter with GLFW key codes, like the screen does.
		int[] keys = {86, 69, 82, 257}; // V E R Enter
		for (int k : keys) {
			d.sendKey(k, true);
			Thread.sleep(60);
			d.sendKey(k, false);
			Thread.sleep(60);
		}
		Thread.sleep(4_000);
		VmFrame after = copy(d.frame());
		save(after, out + "-typed.png");
		check("screen content changed after typing", differs(before, after));

		// The same through typed characters only (an Android on-screen keyboard sends these, with no key presses):
		// "cls" + Enter must clear the screen.
		KeyInput typing = new KeyInput(() -> d, System::nanoTime);
		VmFrame full = copy(d.frame());
		int litFull = litPixels(full);
		for (char c : "cls\n".toCharArray()) {
			typing.character(c);
			Thread.sleep(80);
		}
		Thread.sleep(4_000);
		VmFrame cleared = copy(d.frame());
		System.out.println("       lit pixels before 'cls' " + litFull + ", after " + litPixels(cleared));
		check("typed characters reach the guest (cls clears the screen)", litPixels(cleared) < litFull / 2);

		// Mouse calls must be accepted (PS/2 mouse is relative: moves become deltas).
		d.sendPointer(0, 100, 100);
		d.sendPointer(VmDisplay.BUTTON_LEFT, 120, 110);
		d.sendPointer(0, 120, 110);
		d.sendWheel(true, 0, 120, 110);
		check("mouse input accepted", d.isConnected());

		// Pause: a key typed while paused must not reach the guest; after resuming it does.
		session.setPaused(true);
		Thread.sleep(500);
		long frozen = d.frameVersion();
		d.sendKey(88, true);   // X
		d.sendKey(88, false);
		Thread.sleep(1_500);
		check("paused: guest does not react", d.frameVersion() == frozen);
		session.setPaused(false);
		check("resumed: guest reacts to the key", waitFor(() -> d.frameVersion() > frozen, 10_000));

		session.close();
		check("close -> STOPPED", session.state() == VmSession.State.STOPPED);
		check("machine thread stopped", !d.isConnected());

		// Bad image path must give a clear failure, not a crash.
		VmSession bad = VmSession.builtin(new JpcMachine.Config(Path.of("/no/such/file.iso"), null, null, JpcMachine.Boot.CDROM, 16));
		bad.startAsync();
		check("missing ISO -> FAILED", waitFor(() -> bad.state() == VmSession.State.FAILED, 10_000));
		check("failure names the file", bad.error() != null && bad.error().contains("file.iso"));
		System.out.println("       (message: " + bad.error() + ")");
		bad.close();

		if (args.length > 2) {
			isoBoot(Path.of(args[2]), out);
		}

		if (failures > 0) {
			System.out.println("FAILED: " + failures + " check(s)");
			System.exit(1);
		}
		System.out.println("ALL OK");
		System.exit(0);
	}

	/** Boots a CD-ROM image (El Torito) and checks that it reaches a text screen. */
	private static void isoBoot(Path iso, String out) throws Exception {
		System.out.println("--- CD-ROM boot: " + iso.getFileName());
		VmSession s = VmSession.builtin(JpcMachine.Config.auto(iso, null, null, 16));
		s.startAsync();
		check("iso: session reaches RUNNING", waitFor(() -> s.state() == VmSession.State.RUNNING, 30_000));
		VmDisplay d = s.display();
		check("iso: boots to a text screen with content", waitFor(() -> litPixels(d.frame()) > 5000, 90_000));
		Thread.sleep(8_000);
		VmFrame f = copy(d.frame());
		save(f, out + "-iso.png");
		System.out.println("       screen " + f.width + "x" + f.height + ", lit pixels " + litPixels(f));
		s.close();
	}

	private static void keyChecks() {
		check("Ps2: A", Ps2Keys.scancode(65) == 0x1E);
		check("Ps2: Enter", Ps2Keys.scancode(257) == 0x1C);
		check("Ps2: Left Ctrl", Ps2Keys.scancode(341) == 0x1D);
		check("Ps2: Right Ctrl is extended", Ps2Keys.scancode(345) == (byte) 0x9D);
		check("Ps2: Up arrow is extended", Ps2Keys.scancode(265) == (byte) 0xC8);
		check("Ps2: Delete is extended", Ps2Keys.scancode(261) == (byte) 0xD3);
		check("Ps2: F12", Ps2Keys.scancode(301) == 0x58);
		check("Ps2: unknown key", Ps2Keys.scancode(999) == 0 && Ps2Keys.scancode(-1) == 0);
	}

	private static VmFrame copy(VmFrame f) {
		VmFrame c = new VmFrame(f.width, f.height);
		System.arraycopy(f.pixels, 0, c.pixels, 0, f.pixels.length);
		return c;
	}

	private static int litPixels(VmFrame f) {
		if (f == null) {
			return 0;
		}
		int n = 0;
		for (int p : f.pixels) {
			if ((p & 0xFFFFFF) != 0) {
				n++;
			}
		}
		return n;
	}

	private static boolean differs(VmFrame a, VmFrame b) {
		if (a.width != b.width || a.height != b.height) {
			return true;
		}
		for (int i = 0; i < a.pixels.length; i++) {
			if (a.pixels[i] != b.pixels[i]) {
				return true;
			}
		}
		return false;
	}

	private static void save(VmFrame f, String file) throws Exception {
		BufferedImage img = new BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_RGB);
		img.setRGB(0, 0, f.width, f.height, f.pixels, 0, f.width);
		ImageIO.write(img, "png", new File(file));
	}

	private static boolean waitFor(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
		long end = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < end) {
			if (cond.getAsBoolean()) {
				return true;
			}
			Thread.sleep(25);
		}
		return false;
	}

	private static void check(String name, boolean ok) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + name);
		if (!ok) {
			failures++;
		}
	}
}
