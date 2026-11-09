package com.jpc.vm;

/**
 * Converts GLFW key codes (what Minecraft gives you) to X11 keysyms (what VNC wants).
 * Plain ints on purpose: no GLFW or Minecraft import needed.
 */
public final class KeyMap {
	private KeyMap() {
	}

	/** @return the X11 keysym, or 0 if the key has no mapping */
	public static int toKeysym(int glfwKey) {
		// Letters: GLFW uses upper-case ASCII, X11 wants lower-case.
		if (glfwKey >= 65 && glfwKey <= 90) {
			return glfwKey + 32;
		}
		// Digits and space share their ASCII value.
		if ((glfwKey >= 48 && glfwKey <= 57) || glfwKey == 32) {
			return glfwKey;
		}
		// Function keys F1..F12 (GLFW 290..301) -> 0xFFBE..0xFFC9
		if (glfwKey >= 290 && glfwKey <= 301) {
			return 0xFFBE + (glfwKey - 290);
		}
		// Keypad digits KP_0..KP_9 (GLFW 320..329) -> 0xFFB0..0xFFB9
		if (glfwKey >= 320 && glfwKey <= 329) {
			return 0xFFB0 + (glfwKey - 320);
		}

		return switch (glfwKey) {
			// Punctuation (ASCII values)
			case 39 -> '\'';
			case 44 -> ',';
			case 45 -> '-';
			case 46 -> '.';
			case 47 -> '/';
			case 59 -> ';';
			case 61 -> '=';
			case 91 -> '[';
			case 92 -> '\\';
			case 93 -> ']';
			case 96 -> '`';

			// Editing and navigation
			case 256 -> 0xFF1B; // Escape
			case 257 -> 0xFF0D; // Enter
			case 258 -> 0xFF09; // Tab
			case 259 -> 0xFF08; // Backspace
			case 260 -> 0xFF63; // Insert
			case 261 -> 0xFFFF; // Delete
			case 262 -> 0xFF53; // Right
			case 263 -> 0xFF51; // Left
			case 264 -> 0xFF54; // Down
			case 265 -> 0xFF52; // Up
			case 266 -> 0xFF55; // Page Up
			case 267 -> 0xFF56; // Page Down
			case 268 -> 0xFF50; // Home
			case 269 -> 0xFF57; // End
			case 280 -> 0xFFE5; // Caps Lock
			case 281 -> 0xFF14; // Scroll Lock
			case 282 -> 0xFF7F; // Num Lock
			case 283 -> 0xFF61; // Print Screen
			case 284 -> 0xFF13; // Pause

			// Keypad operators
			case 330 -> 0xFFAE; // KP .
			case 331 -> 0xFFAF; // KP /
			case 332 -> 0xFFAA; // KP *
			case 333 -> 0xFFAD; // KP -
			case 334 -> 0xFFAB; // KP +
			case 335 -> 0xFF8D; // KP Enter

			// Modifiers
			case 340 -> 0xFFE1; // Left Shift
			case 341 -> 0xFFE3; // Left Ctrl
			case 342 -> 0xFFE9; // Left Alt
			case 343 -> 0xFFEB; // Left Super
			case 344 -> 0xFFE2; // Right Shift
			case 345 -> 0xFFE4; // Right Ctrl
			case 346 -> 0xFFEA; // Right Alt
			case 347 -> 0xFFEC; // Right Super
			case 348 -> 0xFF67; // Menu

			default -> 0;
		};
	}
}
