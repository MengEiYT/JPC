package com.jpc.vm;

/**
 * GLFW key codes to PC keyboard scancodes (set 1), in the form JPC's Keyboard wants:
 * the low 7 bits are the scancode, and bit 7 set means "extended key" (sent with an 0xE0 prefix).
 */
public final class Ps2Keys {
	private static final byte[] TABLE = new byte[512];

	private Ps2Keys() {
	}

	/** @return the scancode, or 0 if the key has no mapping */
	public static byte scancode(int glfwKey) {
		return glfwKey >= 0 && glfwKey < TABLE.length ? TABLE[glfwKey] : 0;
	}

	private static void set(int glfwKey, int scancode) {
		TABLE[glfwKey] = (byte) scancode;
	}

	private static void ext(int glfwKey, int scancode) {
		TABLE[glfwKey] = (byte) (0x80 | scancode);
	}

	static {
		// Letters A..Z (GLFW 65..90), in QWERTY scancode order.
		int[] letters = {
				0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32,
				0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C
		};
		for (int i = 0; i < 26; i++) {
			set(65 + i, letters[i]);
		}
		// Digits 1..9, 0 (GLFW 49..57, 48)
		for (int i = 1; i <= 9; i++) {
			set(48 + i, 0x01 + i);
		}
		set(48, 0x0B);

		set(39, 0x28);  // '
		set(44, 0x33);  // ,
		set(45, 0x0C);  // -
		set(46, 0x34);  // .
		set(47, 0x35);  // /
		set(59, 0x27);  // ;
		set(61, 0x0D);  // =
		set(91, 0x1A);  // [
		set(92, 0x2B);  // \
		set(93, 0x1B);  // ]
		set(96, 0x29);  // `
		set(32, 0x39);  // space

		set(256, 0x01); // Escape
		set(257, 0x1C); // Enter
		set(258, 0x0F); // Tab
		set(259, 0x0E); // Backspace
		set(280, 0x3A); // Caps Lock
		set(281, 0x46); // Scroll Lock
		set(282, 0x45); // Num Lock

		ext(260, 0x52); // Insert
		ext(261, 0x53); // Delete
		ext(262, 0x4D); // Right
		ext(263, 0x4B); // Left
		ext(264, 0x50); // Down
		ext(265, 0x48); // Up
		ext(266, 0x49); // Page Up
		ext(267, 0x51); // Page Down
		ext(268, 0x47); // Home
		ext(269, 0x4F); // End

		// F1..F10 = 0x3B..0x44, F11 = 0x57, F12 = 0x58
		for (int i = 0; i < 10; i++) {
			set(290 + i, 0x3B + i);
		}
		set(300, 0x57);
		set(301, 0x58);

		// Keypad
		set(320, 0x52); // 0
		set(321, 0x4F); // 1
		set(322, 0x50); // 2
		set(323, 0x51); // 3
		set(324, 0x4B); // 4
		set(325, 0x4C); // 5
		set(326, 0x4D); // 6
		set(327, 0x47); // 7
		set(328, 0x48); // 8
		set(329, 0x49); // 9
		set(330, 0x53); // .
		ext(331, 0x35); // /
		set(332, 0x37); // *
		set(333, 0x4A); // -
		set(334, 0x4E); // +
		ext(335, 0x1C); // Enter

		set(340, 0x2A); // Left Shift
		set(341, 0x1D); // Left Ctrl
		set(342, 0x38); // Left Alt
		set(344, 0x36); // Right Shift
		ext(345, 0x1D); // Right Ctrl
		ext(346, 0x38); // Right Alt
		ext(343, 0x5B); // Left Super
		ext(347, 0x5C); // Right Super
		ext(348, 0x5D); // Menu
	}
}
