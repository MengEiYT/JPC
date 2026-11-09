package com.jpc.vm;

/**
 * What the in-game screen needs from a running virtual machine: its pixels,
 * and a way to send keyboard and mouse input. The built-in emulator
 * ({@link JpcMachine}) and the optional QEMU link ({@link VncClient}) both implement it.
 */
public interface VmDisplay extends AutoCloseable {
	/** Pointer button bits for {@link #sendPointer}. */
	int BUTTON_LEFT = 1;
	int BUTTON_MIDDLE = 2;
	int BUTTON_RIGHT = 4;

	/** Current screen, or null before the first picture. */
	VmFrame frame();

	/** Increases every time the screen content changed. */
	long frameVersion();

	/** False once the machine stopped or the link broke. */
	boolean isConnected();

	/** Why the machine stopped, or null. */
	String error();

	/** Short name of the machine, for the status bar. */
	String serverName();

	/** Live numbers for the status bar (speed, screen mode), or an empty string. */
	default String statusLine() {
		return "";
	}

	/** @param glfwKey a GLFW key code (what Minecraft gives you) */
	void sendKey(int glfwKey, boolean down);

	/**
	 * @param buttons bitmask of BUTTON_* currently held
	 * @param x       pixel x on the guest screen
	 * @param y       pixel y on the guest screen
	 */
	void sendPointer(int buttons, int x, int y);

	/** One scroll wheel notch. */
	void sendWheel(boolean up, int buttons, int x, int y);

	/** Pauses or resumes the machine, if it can. Default: ignored. */
	default void setPaused(boolean paused) {
	}

	/** Presses the keys in order, then releases them in reverse. Used by the on-screen buttons. */
	default void tap(int... glfwKeys) {
		for (int key : glfwKeys) {
			sendKey(key, true);
		}
		for (int i = glfwKeys.length - 1; i >= 0; i--) {
			sendKey(glfwKeys[i], false);
		}
	}

	@Override
	void close();
}
