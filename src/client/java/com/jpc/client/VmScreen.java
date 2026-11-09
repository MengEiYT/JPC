package com.jpc.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import com.jpc.JpcMod;
import com.jpc.vm.InputMap;
import com.jpc.vm.KeyInput;
import com.jpc.vm.VmDisplay;
import com.jpc.vm.VmFrame;
import com.jpc.vm.VmSession;

/**
 * Full-screen view of the computer. Every key and mouse action goes to the guest,
 * except the exit key (Right Ctrl) and the on-screen Exit button.
 *
 * The bottom row has buttons for keys that are awkward on a touch screen
 * (Esc, Tab, Enter, arrows, Ctrl+Alt+Del), and a Keyboard button that opens a small on-screen keyboard
 * drawn by this mod, for phones whose own keyboard does not reach the game.
 */
public class VmScreen extends Screen {
	// GLFW key codes
	private static final int KEY_RIGHT_CONTROL = 345; // exit key, never sent to the VM
	private static final int KEY_ESCAPE = 256;
	private static final int KEY_ENTER = 257;
	private static final int KEY_TAB = 258;
	private static final int KEY_DELETE = 261;
	private static final int KEY_RIGHT = 262;
	private static final int KEY_LEFT = 263;
	private static final int KEY_DOWN = 264;
	private static final int KEY_UP = 265;
	private static final int KEY_LEFT_CONTROL = 341;
	private static final int KEY_LEFT_ALT = 342;

	private static final int TOP_BAR = 24;
	private static final int BOTTOM_BAR = 24;

	// On-screen keyboard: the id of each key, row by row (Shift, Space, Bksp and Hint are special).
	private static final String[][] PAD_ROWS = {
			{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "="},
			{"q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "[", "]"},
			{"a", "s", "d", "f", "g", "h", "j", "k", "l", ";", "'", "\\"},
			{"Shift", "z", "x", "c", "v", "b", "n", "m", ",", ".", "/"},
			{"Space", "Bksp", "`", "Hint"}
	};
	private static final int PAD_COLUMNS = 12;
	private static final int PAD_ROW_HEIGHT = 16;
	/** If less than this much picture height would be left above the keyboard, the keyboard covers the picture instead. */
	private static final int MIN_PICTURE_ROOM = 70;
	private static final long KEY_FLASH_NS = 160_000_000L;
	private static final int PAD_BACKDROP = 0xE0101418;
	private static final int PAD_BORDER = 0xFF6B7078;
	private static final int KEY_NORMAL = 0xFF2A2E34;
	private static final int KEY_HOVER = 0xFF4A4F57;
	private static final int KEY_PRESSED = 0xFF2F7DE1;
	private static final int KEY_STICKY = 0xFFB07A00;
	private static final long NOTE_SHOW_NS = 4_000_000_000L;
	private static final int NOTES_LOGGED = 60;

	// One texture for the whole client, shared every time the screen opens.
	private static final VmTexture TEXTURE = new VmTexture();

	/** One key of the on-screen keyboard, in screen coordinates. */
	private record PadKey(String id, int x, int y, int w, int h) {
		boolean contains(double px, double py) {
			return px >= x && px < x + w && py >= y && py < y + h;
		}
	}

	private final KeyInput keys = new KeyInput(VmScreen::currentDisplay, System::nanoTime);
	private final List<PadKey> padKeys = new ArrayList<>();
	/** Stays open between screens for the rest of the game session, so a phone does not have to open it every time. */
	private static boolean padOpen;
	private int padTop;
	private int padHeight;
	/** The mouse press that is going on started on the keyboard, so it must not reach the computer. */
	private boolean padGesture;
	private String flashId;
	private long flashAt;
	private boolean showHints;
	private String inputNote;
	private long inputNoteAt;
	private static int notesLogged;
	private int buttonMask = 0;
	private int lastPointerX;
	private int lastPointerY;

	// Where the picture is drawn this frame (updated in extractRenderState).
	private int drawX;
	private int drawY;
	private int drawW;
	private int drawH;
	private int vmW;
	private int vmH;

	public VmScreen() {
		super(Component.literal("Computer"));
		this.showHints = ClientConfig.load().showKeyHints;
	}

	@Override
	protected void init() {
		VmManager.ensureStarted();
		VmManager.screenClosed(false);

		this.addRenderableWidget(Button.builder(Component.literal("Power off"), button -> powerOff())
				.bounds(this.width - 226, 2, 80, 20)
				.build());
		this.addRenderableWidget(Button.builder(Component.literal("Restart"), button -> VmManager.restart())
				.bounds(this.width - 140, 2, 80, 20)
				.build());
		this.addRenderableWidget(Button.builder(Component.literal("Exit"), button -> this.onClose())
				.bounds(this.width - 56, 2, 52, 20)
				.build());

		// Key row along the bottom.
		String[] labels = {"Keyboard", "Esc", "Tab", "Enter", "<", "^", "v", ">", "Ctrl+Alt+Del"};
		int[] widths = {56, 32, 32, 42, 22, 22, 22, 22, 90};
		int total = 0;
		for (int w : widths) {
			total += w + 2;
		}
		// On a narrow screen make every button a little narrower so the whole row fits.
		if (total > this.width - 4) {
			double shrink = (this.width - 4.0) / total;
			for (int i = 0; i < widths.length; i++) {
				widths[i] = Math.max(16, (int) ((widths[i] + 2) * shrink) - 2);
			}
			total = 0;
			for (int w : widths) {
				total += w + 2;
			}
		}
		int x = Math.max(2, (this.width - total) / 2);
		int y = this.height - BOTTOM_BAR + 2;
		for (int i = 0; i < labels.length; i++) {
			final int index = i;
			this.addRenderableWidget(Button.builder(Component.literal(labels[i]), button -> tapButton(index))
					.bounds(x, y, widths[i], 20)
					.build());
			x += widths[i] + 2;
		}

		buildPad();
	}

	/**
	 * Works out where each key of the on-screen keyboard goes. The keyboard is drawn by this screen itself, after the
	 * picture (see {@link #drawPad}), because widgets are drawn before everything else and the picture would cover them.
	 */
	private void buildPad() {
		padKeys.clear();
		int keyW = Math.max(14, Math.min(30, (this.width - 8) / PAD_COLUMNS));
		padHeight = PAD_ROWS.length * (PAD_ROW_HEIGHT + 1) + 3;
		padTop = this.height - BOTTOM_BAR - padHeight;
		for (int r = 0; r < PAD_ROWS.length; r++) {
			String[] row = PAD_ROWS[r];
			int spans = 0;
			for (String id : row) {
				spans += padSpan(id);
			}
			int x = (this.width - spans * keyW) / 2;
			int y = padTop + 2 + r * (PAD_ROW_HEIGHT + 1);
			for (String id : row) {
				int span = padSpan(id);
				padKeys.add(new PadKey(id, x, y, span * keyW - 1, PAD_ROW_HEIGHT));
				x += span * keyW;
			}
		}
	}

	/** How many key widths a key covers. */
	private static int padSpan(String id) {
		return switch (id) {
			case "Shift" -> 2;
			case "Space" -> 5;
			case "Bksp", "Hint" -> 3;
			default -> 1;
		};
	}

	private void togglePad() {
		padOpen = !padOpen;
	}

	private PadKey padKeyAt(double x, double y) {
		for (PadKey k : padKeys) {
			if (k.contains(x, y)) {
				return k;
			}
		}
		return null;
	}

	/** True if the point is on the keyboard panel (keys or the gaps between them). */
	private boolean inPad(double x, double y) {
		return padOpen && y >= padTop && y < this.height - BOTTOM_BAR;
	}

	/** Height taken from the picture by the open keyboard (0 if closed, or if the screen is too small to share). */
	private int padSpace() {
		if (!padOpen) {
			return 0;
		}
		int room = this.height - TOP_BAR - BOTTOM_BAR - padHeight;
		return room >= MIN_PICTURE_ROOM ? padHeight : 0;
	}

	private void padPressed(String id) {
		flashId = id;
		flashAt = System.nanoTime();
		if (id.equals("Hint")) {
			toggleHints();
			return;
		}
		switch (id) {
			case "Shift" -> keys.toggleShift();
			case "Space" -> keys.tap(32);
			case "Bksp" -> keys.tap(259);
			default -> {
				InputMap.Typed t = InputMap.fromChar(id.charAt(0));
				if (t != null) {
					keys.tap(t.key());
				}
			}
		}
		note("on-screen key " + id);
	}

	/** Turns the yellow "what did that key do" line on or off, and remembers the choice in the config file. */
	private void toggleHints() {
		showHints = !showHints;
		ClientConfig cfg = ClientConfig.load();
		cfg.showKeyHints = showHints;
		cfg.save();
		if (showHints) {
			note("key hints on");
		} else {
			inputNote = null;
		}
	}

	/** What a key shows on its face: the shifted character while Shift is on, and the state of the hint switch. */
	private String padText(String id) {
		if (id.equals("Hint")) {
			return showHints ? "Hint on" : "Hint off";
		}
		if (id.length() == 1 && keys.stickyShift()) {
			return String.valueOf(InputMap.shifted(id.charAt(0)));
		}
		return id;
	}

	/** Draws the keyboard over the bottom of the picture area. */
	private void drawPad(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(0, padTop, this.width, this.height - BOTTOM_BAR, PAD_BACKDROP);
		long now = System.nanoTime();
		for (PadKey k : padKeys) {
			int face = KEY_NORMAL;
			if (k.id().equals(flashId) && now - flashAt < KEY_FLASH_NS) {
				face = KEY_PRESSED;
			} else if (k.id().equals("Shift") && keys.stickyShift()) {
				face = KEY_STICKY;
			} else if (k.contains(mouseX, mouseY)) {
				face = KEY_HOVER;
			}
			graphics.fill(k.x(), k.y(), k.x() + k.w(), k.y() + k.h(), PAD_BORDER);
			graphics.fill(k.x() + 1, k.y() + 1, k.x() + k.w() - 1, k.y() + k.h() - 1, face);
			String text = padText(k.id());
			graphics.text(this.font, text, k.x() + (k.w() - this.font.width(text)) / 2, k.y() + (k.h() - 8) / 2, 0xFFFFFFFF, false);
		}
	}

	/** Switches the computer off and goes back to the boot menu (or just closes the screen if the menu is off). */
	private void powerOff() {
		releaseInput();
		VmManager.stop();
		this.minecraft.gui.setScreen(VmManager.shouldShowBootMenu() ? new BootMenuScreen() : null);
	}

	private void tapButton(int index) {
		if (index == 0) {
			togglePad();   // the Keyboard button
			return;
		}
		VmDisplay d = display();
		if (d == null) {
			return;
		}
		switch (index) {
			case 1 -> d.tap(KEY_ESCAPE);
			case 2 -> d.tap(KEY_TAB);
			case 3 -> d.tap(KEY_ENTER);
			case 4 -> d.tap(KEY_LEFT);
			case 5 -> d.tap(KEY_UP);
			case 6 -> d.tap(KEY_DOWN);
			case 7 -> d.tap(KEY_RIGHT);
			case 8 -> d.tap(KEY_LEFT_CONTROL, KEY_LEFT_ALT, KEY_DELETE);
			default -> { }
		}
	}

	// ----------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		VmManager.windowFocused(GameApi.windowActive(this.minecraft));
		VmSession session = VmManager.current();
		if (session != null) {
			session.tick();
		}

		// Black area for the picture, between the two button bars. Everything below is drawn after the buttons
		// (which the base class has already drawn), so the order matters: picture first, keyboard on top of it.
		int areaBottom = this.height - BOTTOM_BAR;
		graphics.fill(0, TOP_BAR, this.width, areaBottom, 0xFF000000);

		VmDisplay d = session == null ? null : session.display();
		VmFrame frame = d == null ? null : d.frame();

		String status;
		if (frame != null && TEXTURE.update(frame, d.frameVersion())) {
			layout(frame.width, frame.height, areaBottom - padSpace());
			// pipeline, texture, x, y, u, v, drawWidth, drawHeight, regionWidth, regionHeight, textureWidth, textureHeight
			graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE.id(), drawX, drawY, 0, 0,
					drawW, drawH, frame.width, frame.height, frame.width, frame.height);
			status = session.describe();
		} else {
			drawW = 0;
			status = describeProblem(session);
			drawCentered(graphics, status);
		}

		if (padOpen) {
			drawPad(graphics, mouseX, mouseY);
		}

		// The status text stays to the left of the Power off button (which starts at width - 226).
		int room = (this.width - 226 - 12) / 6;
		if (room >= 8) {
			String exitHint = "Right Ctrl: exit  |  ";
			String line = room >= exitHint.length() + 24 ? exitHint + shorten(status, room - exitHint.length()) : shorten(status, room);
			graphics.text(this.font, line, 6, 8, 0xFFFFFFFF, true);
		}

		// What the last key did, for a few seconds: shows whether the keyboard reaches the computer.
		if (inputNote != null && System.nanoTime() - inputNoteAt < NOTE_SHOW_NS) {
			graphics.text(this.font, shorten(inputNote, Math.max(12, this.width / 6)), 6, TOP_BAR + 4, 0xFFFFFF55, true);
		}
	}

	private void note(String text) {
		if (text == null || !showHints) {
			return;
		}
		inputNote = text;
		inputNoteAt = System.nanoTime();
		if (notesLogged < NOTES_LOGGED) {
			notesLogged++;
			JpcMod.LOGGER.info("Keyboard: {}", text);
		}
	}

	private static String describeProblem(VmSession session) {
		if (session == null) {
			String err = VmManager.configError();
			return err == null ? "No computer configured" : err;
		}
		return switch (session.state()) {
			case STARTING -> "Starting the computer...";
			case FAILED -> "Computer stopped: " + session.error() + "   (use Restart)";
			case RUNNING -> "Waiting for the first picture... (booting)";
			case STOPPED -> "Computer is off (use Restart)";
		};
	}

	private void drawCentered(GuiGraphicsExtractor graphics, String message) {
		int maxChars = Math.max(10, (this.width - 20) / 6);
		int y = TOP_BAR + (this.height - TOP_BAR - BOTTOM_BAR) / 2 - 20;
		for (int start = 0; start < message.length(); start += maxChars) {
			String line = message.substring(start, Math.min(message.length(), start + maxChars));
			graphics.text(this.font, line, (this.width - this.font.width(line)) / 2, y, 0xFFFFFFFF, true);
			y += 12;
		}
	}

	private static String shorten(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 3) + "...";
	}

	/** Fit the picture into the area between the top bar and {@code bottom}, keeping its shape. */
	private void layout(int frameW, int frameH, int bottom) {
		int availW = this.width;
		int availH = Math.max(1, bottom - TOP_BAR);
		double scale = Math.min((double) availW / frameW, (double) availH / frameH);
		drawW = Math.max(1, (int) (frameW * scale));
		drawH = Math.max(1, (int) (frameH * scale));
		drawX = (availW - drawW) / 2;
		drawY = TOP_BAR + (availH - drawH) / 2;
		vmW = frameW;
		vmH = frameH;
	}

	// ------------------------------------------------------------------- input

	private static VmDisplay currentDisplay() {
		VmSession s = VmManager.current();
		return s == null ? null : s.display();
	}

	private VmDisplay display() {
		return currentDisplay();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == KEY_RIGHT_CONTROL) {
			this.onClose();
			return true;
		}
		if (display() == null) {
			// No computer to type into: let Escape leave the screen.
			if (key == KEY_ESCAPE) {
				this.onClose();
			}
			return true;
		}
		note(keys.keyDown(key, 0));
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (event.key() != KEY_RIGHT_CONTROL) {
			keys.keyUp(event.key(), 0);
		}
		return true;
	}

	/** Typed characters: what an Android on-screen keyboard sends. Sent to the computer unless a key press already covered it. */
	@Override
	public boolean charTyped(CharacterEvent event) {
		note(keys.character(event.codepoint()));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// The on-screen keyboard sits on top of the picture, so it gets the click first.
		if (inPad(event.x(), event.y())) {
			padGesture = true;
			PadKey k = padKeyAt(event.x(), event.y());
			if (k != null) {
				padPressed(k.id());
			}
			return true;
		}
		// Buttons next.
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.y() < TOP_BAR || event.y() >= this.height - BOTTOM_BAR) {
			return true;
		}
		buttonMask |= maskFor(event.button());
		sendPointer(event.x(), event.y());
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (padGesture) {
			padGesture = false;
			return true;
		}
		super.mouseReleased(event);
		buttonMask &= ~maskFor(event.button());
		sendPointer(event.x(), event.y());
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (padGesture) {
			return true;
		}
		sendPointer(event.x(), event.y());
		return true;
	}

	@Override
	public void mouseMoved(double x, double y) {
		if (inPad(x, y)) {
			return;
		}
		sendPointer(x, y);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		VmDisplay d = display();
		if (d != null && scrollY != 0 && !inPad(x, y)) {
			sendPointer(x, y);
			d.sendWheel(scrollY > 0, buttonMask, lastPointerX, lastPointerY);
		}
		return true;
	}

	private void sendPointer(double mouseX, double mouseY) {
		VmDisplay d = display();
		if (d == null || drawW <= 0 || drawH <= 0) {
			return;
		}
		int x = clamp((int) ((mouseX - drawX) * vmW / drawW), 0, vmW - 1);
		int y = clamp((int) ((mouseY - drawY) * vmH / drawH), 0, vmH - 1);
		lastPointerX = x;
		lastPointerY = y;
		d.sendPointer(buttonMask, x, y);
	}

	/** Minecraft buttons: 0 left, 1 right, 2 middle. */
	private static int maskFor(int button) {
		return switch (button) {
			case 0 -> VmDisplay.BUTTON_LEFT;
			case 1 -> VmDisplay.BUTTON_RIGHT;
			case 2 -> VmDisplay.BUTTON_MIDDLE;
			default -> 0;
		};
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	// ----------------------------------------------------------------- closing

	@Override
	public void onClose() {
		releaseInput();
		this.minecraft.gui.setScreen(null);
	}

	@Override
	public void removed() {
		releaseInput();
		VmManager.screenClosed(true);
		super.removed();
	}

	/** Lets go of every key and mouse button so nothing stays stuck in the guest. */
	private void releaseInput() {
		keys.releaseAll();
		VmDisplay d = display();
		if (d != null && buttonMask != 0) {
			d.sendPointer(0, lastPointerX, lastPointerY);
		}
		buttonMask = 0;
		padGesture = false;
	}
}
