package com.jpc.client;

import net.fabricmc.loader.api.FabricLoader;

import com.jpc.vm.VmException;
import com.jpc.vm.VmSession;

/**
 * Owns the one VM this client uses. The VM keeps its state while the screen is
 * closed (the built-in emulator is paused then, unless the config says otherwise).
 */
public final class VmManager {
	private static VmSession session;
	private static String configError;
	private static boolean pauseWhenClosed = true;
	private static boolean screenClosed = true;
	private static boolean windowFocused = true;

	private VmManager() {
	}

	/** Returns the running session, or starts a new one if there is none / the last one failed. */
	public static synchronized VmSession ensureStarted() {
		if (session != null) {
			VmSession.State s = session.state();
			if (s == VmSession.State.STARTING || s == VmSession.State.RUNNING) {
				return session;
			}
			session.close(); // failed or stopped: release whatever is left of it
			session = null;
		}
		return startNew();
	}

	/** Drops the current VM and starts again (the built-in computer reboots). */
	public static synchronized VmSession restart() {
		return restart(ClientConfig.load());
	}

	/** Same, with settings that are already in hand (the boot menu passes its choice here). */
	public static synchronized VmSession restart(ClientConfig cfg) {
		if (session != null) {
			session.close();
			session = null;
		}
		return startNew(cfg);
	}

	/**
	 * Whether using the computer block should show the boot menu first: the built-in computer is off (or failed),
	 * and the menu is not switched off in the settings.
	 */
	public static synchronized boolean shouldShowBootMenu() {
		if (session != null) {
			VmSession.State s = session.state();
			if (s == VmSession.State.STARTING || s == VmSession.State.RUNNING) {
				return false;
			}
		}
		ClientConfig cfg = ClientConfig.load();
		return cfg.showBootMenu && !cfg.isQemu() && !cfg.isRemote();
	}

	/** Current session, or null (see {@link #configError()}). */
	public static synchronized VmSession current() {
		return session;
	}

	/** Why no session could be created (bad settings), or null. */
	public static synchronized String configError() {
		return configError;
	}

	/** Called when the screen opens (false) or closes (true). */
	public static synchronized void screenClosed(boolean closed) {
		screenClosed = closed;
		applyPause();
	}

	/**
	 * Called every frame the screen is drawn with whether the game window has focus. An open screen that nobody
	 * can see (window in the background, app switched away on a phone) freezes the computer.
	 */
	public static synchronized void windowFocused(boolean focused) {
		if (focused != windowFocused) {
			windowFocused = focused;
			applyPause();
		}
	}

	private static void applyPause() {
		if (session != null) {
			session.setPaused(screenClosed ? pauseWhenClosed : !windowFocused);
		}
	}

	public static synchronized void stop() {
		if (session != null) {
			session.close();
			session = null;
		}
	}

	private static VmSession startNew() {
		return startNew(ClientConfig.load());
	}

	private static VmSession startNew(ClientConfig cfg) {
		pauseWhenClosed = cfg.pauseWhenClosed;
		VmSession s;
		try {
			if (cfg.isRemote()) {
				s = VmSession.remote(cfg.remoteHost, cfg.remotePort);
			} else if (cfg.isQemu()) {
				s = new VmSession(cfg.toQemuConfig(), FabricLoader.getInstance().getGameDir().resolve("jpc/vm.log"));
			} else {
				s = VmSession.builtin(cfg.toBuiltinConfig());
			}
		} catch (VmException e) {
			configError = e.getMessage();
			session = null;
			return null;
		}
		configError = null;
		session = s;
		s.startAsync();
		return s;
	}
}
