package com.jpc.vm;

/**
 * Turns the other ways a keyboard can talk to the game into the GLFW key codes the rest of the mod uses:
 * typed characters (what an Android on-screen keyboard usually sends) and USB-style scancodes
 * (for key events that arrive without a usable key code). Plain Java, no game classes.
 */
public final class InputMap {
	public static final int KEY_SHIFT = 340;

	/** The symbol keys of a US keyboard, without Shift... */
	private static final String SYMBOLS_PLAIN = "1234567890-=[]\\;',./`";
	/** ...and with Shift (same order, so the character at the same position is on the same key). */
	private static final String SYMBOLS_SHIFTED = "!@#$%^&*()_+{}|:\"<>?~";

	/** A key to press, and whether Shift must be held with it. */
	public record Typed(int key, boolean shift) {
	}

	private InputMap() {
	}

	/**
	 * The key that types this character on a US keyboard, or null if there is none (accents, emoji, ...).
	 * Control characters map to the keys they come from: Enter, Backspace, Tab, Escape.
	 */
	public static Typed fromChar(int cp) {
		switch (cp) {
			case 8, 127:
				return new Typed(259, false);   // Backspace
			case 9:
				return new Typed(258, false);   // Tab
			case 10, 13:
				return new Typed(257, false);   // Enter
			case 27:
				return new Typed(256, false);   // Escape
			case ' ':
				return new Typed(32, false);
			default:
				break;
		}
		if (cp >= 'a' && cp <= 'z') {
			return new Typed(cp - 'a' + 65, false);
		}
		if (cp >= 'A' && cp <= 'Z') {
			return new Typed(cp - 'A' + 65, true);
		}
		if (cp >= '0' && cp <= '9') {
			return new Typed(cp, false);
		}
		// Shifted digits and symbols: the character, the key it sits on.
		int i = SYMBOLS_SHIFTED.indexOf(cp);
		if (i >= 0) {
			return new Typed(SYMBOLS_PLAIN.charAt(i), true);
		}
		if (SYMBOLS_PLAIN.indexOf(cp) >= 0) {
			return new Typed(cp, false);   // the GLFW code of these keys is the ASCII value
		}
		return null;
	}

	/** What a key types while Shift is held (US keyboard): {@code a} gives {@code A}, {@code 1} gives {@code !}. Other characters stay as they are. */
	public static char shifted(char c) {
		if (c >= 'a' && c <= 'z') {
			return (char) (c - 'a' + 'A');
		}
		int i = SYMBOLS_PLAIN.indexOf(c);
		return i >= 0 ? SYMBOLS_SHIFTED.charAt(i) : c;
	}

	/**
	 * Converts a USB HID usage code (what SDL calls a scancode) to a GLFW key code, or 0 if unknown.
	 * Only used when a key event arrives with no key code of its own.
	 */
	public static int fromHidScancode(int sc) {
		if (sc >= 4 && sc <= 29) {
			return 'A' + (sc - 4);                // A-Z
		}
		if (sc >= 30 && sc <= 38) {
			return '1' + (sc - 30);               // 1-9
		}
		if (sc >= 58 && sc <= 67) {
			return 290 + (sc - 58);               // F1-F10
		}
		return switch (sc) {
			case 39 -> '0';
			case 40, 88 -> 257;                   // Enter, keypad Enter
			case 41 -> 256;                       // Escape
			case 42 -> 259;                       // Backspace
			case 43 -> 258;                       // Tab
			case 44 -> 32;                        // Space
			case 45 -> '-';
			case 46 -> '=';
			case 47 -> '[';
			case 48 -> ']';
			case 49 -> '\\';
			case 51 -> ';';
			case 52 -> '\'';
			case 53 -> '`';
			case 54 -> ',';
			case 55 -> '.';
			case 56 -> '/';
			case 57 -> 280;                       // Caps Lock
			case 68 -> 300;                       // F11
			case 69 -> 301;                       // F12
			case 73 -> 260;                       // Insert
			case 74 -> 268;                       // Home
			case 75 -> 266;                       // Page Up
			case 76 -> 261;                       // Delete
			case 77 -> 269;                       // End
			case 78 -> 267;                       // Page Down
			case 79 -> 262;                       // Right
			case 80 -> 263;                       // Left
			case 81 -> 264;                       // Down
			case 82 -> 265;                       // Up
			case 224 -> 341;                      // Left Ctrl
			case 225 -> 340;                      // Left Shift
			case 226 -> 342;                      // Left Alt
			case 228 -> 345;                      // Right Ctrl (the exit key; handled by the screen first)
			case 229 -> 344;                      // Right Shift
			case 230 -> 346;                      // Right Alt
			default -> 0;
		};
	}

	/** Short readable name for messages, for example {@code 'a'}, {@code Enter}, {@code U+00E9}. */
	public static String describeChar(int cp) {
		return switch (cp) {
			case 8, 127 -> "Backspace";
			case 9 -> "Tab";
			case 10, 13 -> "Enter";
			case 27 -> "Esc";
			case ' ' -> "Space";
			default -> cp > 32 && cp < 127 ? "'" + (char) cp + "'" : String.format("U+%04X", cp);
		};
	}
}
