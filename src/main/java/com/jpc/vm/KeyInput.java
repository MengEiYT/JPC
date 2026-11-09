package com.jpc.vm;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Everything the computer screen does with the keyboard, without any game classes so it can be tested.
 *
 * <p>Keys reach the game in different ways. A physical keyboard sends a key press and then a typed character for it.
 * An Android on-screen keyboard often sends only the typed character (or only some keys). This class sends real key
 * presses to the guest as they come, and sends a typed character as a key tap only when no key press came with it, so
 * nothing is typed twice and nothing is lost.
 */
public final class KeyInput {
	private static final int SHIFT_L = 340;
	private static final int SHIFT_R = 344;
	/** A character that arrives this soon after a key press is taken to belong to that key press. */
	private static final long SAME_EVENT_NS = 40_000_000L;

	private final Supplier<VmDisplay> display;
	private final LongSupplier clock;
	private final Set<Integer> held = new HashSet<>();
	private long lastKeyDownAt = Long.MIN_VALUE / 2;
	private boolean stickyShift;

	/**
	 * @param display where keys go (may return null while no computer runs)
	 * @param clock   nanoseconds, for example {@code System::nanoTime}
	 */
	public KeyInput(Supplier<VmDisplay> display, LongSupplier clock) {
		this.display = display;
		this.clock = clock;
	}

	/**
	 * A key press from the game. Returns a short line saying what happened, for the on-screen hint.
	 *
	 * @param scancode a USB-style scancode if the game gives one (0 if not): used only when {@code key} is unknown
	 */
	public String keyDown(int key, int scancode) {
		int k = resolve(key, scancode);
		VmDisplay d = display.get();
		if (d == null) {
			return "no computer running";
		}
		if (Ps2Keys.scancode(k) == 0) {
			return "key " + key + (scancode != 0 ? " (scan " + scancode + ")" : "") + " has no PC key, ignored";
		}
		held.add(k);
		if (!isModifier(k)) {
			lastKeyDownAt = clock.getAsLong();
		}
		d.sendKey(k, true);
		return "key " + k + (k != key ? " (from scan " + scancode + ")" : "") + " sent";
	}

	/** A key release from the game. */
	public void keyUp(int key, int scancode) {
		int k = resolve(key, scancode);
		VmDisplay d = display.get();
		if (d != null && held.remove(k)) {
			d.sendKey(k, false);
		}
	}

	/** A typed character from the game (Unicode code point). Returns a short line for the on-screen hint. */
	public String character(int codePoint) {
		String name = InputMap.describeChar(codePoint);
		InputMap.Typed t = InputMap.fromChar(codePoint);
		if (t == null) {
			return "char " + name + " has no key on a US keyboard, ignored";
		}
		VmDisplay d = display.get();
		if (d == null) {
			return "no computer running";
		}
		if (keyActive()) {
			return "char " + name + " came with a key press, not typed again";
		}
		boolean shiftHeld = held.contains(SHIFT_L) || held.contains(SHIFT_R);
		if (t.shift() && !shiftHeld) {
			d.tap(InputMap.KEY_SHIFT, t.key());
		} else {
			d.tap(t.key());
		}
		return "char " + name + " typed";
	}

	/** A key of the on-screen keyboard. Uses the sticky Shift if it is on. */
	public void tap(int key) {
		VmDisplay d = display.get();
		if (d == null) {
			return;
		}
		if (stickyShift) {
			stickyShift = false;
			d.tap(InputMap.KEY_SHIFT, key);
		} else {
			d.tap(key);
		}
	}

	/** Turns the on-screen Shift on or off. @return the new state */
	public boolean toggleShift() {
		stickyShift = !stickyShift;
		return stickyShift;
	}

	public boolean stickyShift() {
		return stickyShift;
	}

	/** Lets go of every key still held, so nothing stays stuck in the guest. */
	public void releaseAll() {
		VmDisplay d = display.get();
		if (d != null) {
			for (int k : held) {
				d.sendKey(k, false);
			}
		}
		held.clear();
		stickyShift = false;
	}

	private boolean keyActive() {
		if (clock.getAsLong() - lastKeyDownAt < SAME_EVENT_NS) {
			return true;
		}
		for (int k : held) {
			if (!isModifier(k)) {
				return true;
			}
		}
		return false;
	}

	/** The key code to use: the one the game gave, or one worked out from the scancode if it gave none. */
	private static int resolve(int key, int scancode) {
		if (key > 0) {
			return key;
		}
		int fromScan = InputMap.fromHidScancode(scancode);
		return fromScan != 0 ? fromScan : key;
	}

	private static boolean isModifier(int k) {
		return k >= 340 && k <= 347;   // Shift, Ctrl, Alt, Super, left and right
	}
}
