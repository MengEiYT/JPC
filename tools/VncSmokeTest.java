import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.jpc.vm.CpuArch;
import com.jpc.vm.KeyMap;
import com.jpc.vm.QemuProcess;
import com.jpc.vm.VmConfig;
import com.jpc.vm.VmDisplay;
import com.jpc.vm.VmFrame;
import com.jpc.vm.VmSession;
import com.jpc.vm.VncClient;

/**
 * Standalone check for the VM backend (no Minecraft, no QEMU needed).
 * Run it against tools/mock_rfb_server.py, see tools/run_smoke_test.sh.
 */
public class VncSmokeTest {
	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		int port = Integer.parseInt(args[0]);
		int port2 = Integer.parseInt(args[1]);

		commandLineChecks();
		keyMapChecks();
		vncChecks(port);
		sessionChecks(port2);

		if (failures > 0) {
			System.out.println("FAILED: " + failures + " check(s)");
			System.exit(1);
		}
		System.out.println("ALL OK");
	}

	// ------------------------------------------------------------ QEMU command

	private static void commandLineChecks() {
		VmConfig c32 = VmConfig.forIso(CpuArch.X86, Path.of("alpine32.iso"));
		List<String> cmd32 = QemuProcess.buildCommand("qemu-system-i386", c32, 10, "kvm:tcg");
		System.out.println("32-bit: " + String.join(" ", cmd32));
		check("32-bit binary", cmd32.get(0).equals("qemu-system-i386"));
		check("32-bit vnc", cmd32.contains("127.0.0.1:10"));
		check("32-bit no window", cmd32.contains("-display") && cmd32.contains("none"));
		check("32-bit ram 512", cmd32.contains("512"));
		check("32-bit iso drive", cmd32.stream().anyMatch(s -> s.contains("alpine32.iso") && s.contains("media=cdrom")));
		check("32-bit boots cdrom first", cmd32.contains("order=dc"));
		check("32-bit no disk", cmd32.stream().noneMatch(s -> s.contains("qcow2")));

		VmConfig c64 = VmConfig.forIso(CpuArch.X86_64, Path.of("win.iso")).withDisk(Path.of("d.qcow2"), 20).withRam(4096).withCpus(4);
		List<String> cmd64 = QemuProcess.buildCommand("qemu-system-x86_64", c64, 11, "whpx:tcg");
		System.out.println("64-bit: " + String.join(" ", cmd64));
		check("64-bit binary", cmd64.get(0).equals("qemu-system-x86_64"));
		check("64-bit accel", cmd64.contains("pc,accel=whpx:tcg"));
		check("64-bit ram 4096", cmd64.contains("4096"));
		check("64-bit smp 4", cmd64.contains("4"));
		check("64-bit disk", cmd64.stream().anyMatch(s -> s.contains("d.qcow2")));

		check("arch binaries", CpuArch.X86.binary.equals("qemu-system-i386") && CpuArch.X86_64.binary.equals("qemu-system-x86_64"));
	}

	// ----------------------------------------------------------------- keymap

	private static void keyMapChecks() {
		check("A -> 'a'", KeyMap.toKeysym(65) == 0x61);
		check("1 -> '1'", KeyMap.toKeysym(49) == 0x31);
		check("Enter", KeyMap.toKeysym(257) == 0xFF0D);
		check("Backspace", KeyMap.toKeysym(259) == 0xFF08);
		check("F1", KeyMap.toKeysym(290) == 0xFFBE);
		check("F12", KeyMap.toKeysym(301) == 0xFFC9);
		check("Left shift", KeyMap.toKeysym(340) == 0xFFE1);
		check("Up arrow", KeyMap.toKeysym(265) == 0xFF52);
		check("unknown key", KeyMap.toKeysym(999) == 0);
	}

	// -------------------------------------------------------------------- vnc

	private static void vncChecks(int port) throws Exception {
		try (VncClient c = new VncClient("127.0.0.1", port)) {
			c.connect(3000);
			check("server name", "mock".equals(c.serverName()));

			check("first frame arrives", waitFor(() -> c.frameVersion() >= 1));
			VmFrame f = c.frame();
			check("size 64x48", f.width == 64 && f.height == 48);
			// gradient: R = x*4, G = y*5, B = 128
			int px = f.pixels[20 * f.width + 10];
			check("gradient pixel (10,20) = 0xFF286480, got 0x" + Integer.toHexString(px), px == 0xFF286480);

			// key press -> white square at (10,10)
			c.sendKey(65, true);   // GLFW 'A'
			c.sendKey(65, false);
			check("key causes screen update", waitFor(() -> c.frame().pixels[11 * c.frame().width + 11] == 0xFFFFFFFF));

			// left click -> CopyRect of the square to (30,30)
			c.sendPointer(VmDisplay.BUTTON_LEFT, 20, 30);
			c.sendPointer(0, 20, 30);
			check("CopyRect applied", waitFor(() -> c.frame().pixels[31 * c.frame().width + 31] == 0xFFFFFFFF));
			check("CopyRect keeps source", c.frame().pixels[11 * c.frame().width + 11] == 0xFFFFFFFF);

			// right click -> resize to 32x24
			c.sendPointer(VmDisplay.BUTTON_RIGHT, 5, 5);
			check("DesktopSize resize", waitFor(() -> c.frame().width == 32 && c.frame().height == 24));
			check("still connected", c.isConnected());
		}
	}

	// ---------------------------------------------------------------- session

	private static void sessionChecks(int port) throws Exception {
		// Remote mode (Android + Termux): just connect to a VNC server that is already up.
		VmSession remote = VmSession.remote("127.0.0.1", port);
		check("remote session is remote", remote.isRemote() && !remote.isBuiltin() && remote.config() == null);
		remote.startAsync();
		check("remote session reaches RUNNING", waitFor(() -> remote.state() == VmSession.State.RUNNING));
		check("remote session delivers a frame", remote.display() != null && waitFor(() -> remote.display().frameVersion() >= 1));
		check("remote describe", remote.describe().contains("127.0.0.1:" + port));
		remote.close();
		check("close -> STOPPED", remote.state() == VmSession.State.STOPPED);

		// Nothing listening: must end in FAILED with a useful message, not hang.
		VmSession bad = VmSession.remote("127.0.0.1", 1);
		bad.setConnectTimeoutMs(1200);
		bad.startAsync();
		check("unreachable VNC -> FAILED", waitFor(() -> bad.state() == VmSession.State.FAILED));
		check("failure message names the address", bad.error() != null && bad.error().contains("127.0.0.1:1"));
		bad.close();

		// Local mode with a missing ISO / missing QEMU: FAILED either way, never a crash.
		VmConfig cfg = VmConfig.forIso(CpuArch.X86_64, Path.of("/definitely/not/here.iso"))
				.withQemuDir(Path.of("/definitely/not/a/dir"));
		VmSession local = new VmSession(cfg, Path.of("/tmp/jpc-test-vm.log"));
		local.startAsync();
		check("local session with bad setup -> FAILED", waitFor(() -> local.state() == VmSession.State.FAILED));
		check("local failure has a message", local.error() != null && !local.error().isBlank());
		System.out.println("       (message: " + local.error() + ")");
		local.close();
	}

	// ---------------------------------------------------------------- helpers

	private static boolean waitFor(BooleanSupplier cond) throws InterruptedException {
		long end = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < end) {
			if (cond.getAsBoolean()) {
				return true;
			}
			Thread.sleep(20);
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
