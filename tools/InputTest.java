import java.util.*;
import com.jpc.vm.*;
import com.jpc.vm.InputMap.Typed;

/** Checks the keyboard handling (typed characters, key presses, on-screen keys) without Minecraft. */
public class InputTest {
    static int fails = 0;
    static void check(String name, boolean ok) { System.out.println((ok ? "  ok   " : "  FAIL ") + name); if (!ok) fails++; }

    /** A display that only writes down what it was sent. */
    static class Rec implements VmDisplay {
        final List<String> log = new ArrayList<>();
        public VmFrame frame() { return null; }
        public long frameVersion() { return 0; }
        public boolean isConnected() { return true; }
        public String error() { return null; }
        public String serverName() { return "rec"; }
        public void sendKey(int k, boolean down) { log.add((down ? "d" : "u") + k); }
        public void sendPointer(int b, int x, int y) { }
        public void sendWheel(boolean up, int b, int x, int y) { }
        public void close() { }
        String take() { String s = String.join(" ", log); log.clear(); return s; }
    }

    static long now = 0;
    static final long MS = 1_000_000L;

    public static void main(String[] a) throws Exception {
        // ---- characters
        check("a -> key A, no shift", InputMap.fromChar('a').equals(new Typed(65, false)));
        check("A -> key A with shift", InputMap.fromChar('A').equals(new Typed(65, true)));
        check("digit 7", InputMap.fromChar('7').equals(new Typed(55, false)));
        check("! is shift+1", InputMap.fromChar('!').equals(new Typed('1', true)));
        check(": is shift+;", InputMap.fromChar(':').equals(new Typed(';', true)));
        check("? is shift+/", InputMap.fromChar('?').equals(new Typed('/', true)));
        check("_ is shift+-", InputMap.fromChar('_').equals(new Typed('-', true)));
        check("Enter from \\n and \\r", InputMap.fromChar('\n').equals(new Typed(257, false)) && InputMap.fromChar('\r').equals(new Typed(257, false)));
        check("Backspace from 8 and 127", InputMap.fromChar(8).equals(new Typed(259, false)) && InputMap.fromChar(127).equals(new Typed(259, false)));
        check("Tab and Esc", InputMap.fromChar(9).equals(new Typed(258, false)) && InputMap.fromChar(27).equals(new Typed(256, false)));
        check("accents and emoji have no key", InputMap.fromChar(0xE9) == null && InputMap.fromChar(0x1F600) == null);
        boolean all = true;
        for (int c = 32; c < 127; c++) {
            Typed t = InputMap.fromChar(c);
            if (t == null || Ps2Keys.scancode(t.key()) == 0) { all = false; System.out.println("    no key for char " + c); }
        }
        check("every printable ASCII character maps to a key the PC has", all);
        // shifted characters must be on the same key as their unshifted partner
        String sh = "!@#$%^&*()_+{}|:\"<>?~", un = "1234567890-=[]\\;',./`";
        boolean pairs = true;
        for (int i = 0; i < sh.length(); i++) {
            Typed t = InputMap.fromChar(sh.charAt(i));
            if (t == null || !t.shift() || t.key() != un.charAt(i)) { pairs = false; System.out.println("    bad pair " + sh.charAt(i)); }
        }
        check("shifted symbols sit on the right keys", pairs);
        // what the on-screen keyboard shows on its keys while Shift is on
        boolean faces = true;
        for (int i = 0; i < sh.length(); i++) {
            if (InputMap.shifted(un.charAt(i)) != sh.charAt(i)) { faces = false; System.out.println("    bad face for " + un.charAt(i)); }
        }
        for (char c = 'a'; c <= 'z'; c++) {
            if (InputMap.shifted(c) != Character.toUpperCase(c)) { faces = false; System.out.println("    bad face for " + c); }
        }
        check("shifted faces: letters become capitals, digits and symbols their shifted partner", faces);
        check("shifted() leaves other characters alone", InputMap.shifted('A') == 'A' && InputMap.shifted(' ') == ' ' && InputMap.shifted('!') == '!');
        // typing the shifted face must press the same key as the plain key plus Shift
        boolean same = true;
        for (char c : (un + "abcdefghijklmnopqrstuvwxyz").toCharArray()) {
            Typed plain = InputMap.fromChar(c);
            Typed face = InputMap.fromChar(InputMap.shifted(c));
            if (plain == null || face == null || plain.key() != face.key() || !face.shift()) { same = false; System.out.println("    key mismatch for " + c); }
        }
        check("a key and its shifted face are the same physical key", same);

        // ---- USB-style scancodes
        check("scancode 4 is A, 29 is Z", InputMap.fromHidScancode(4) == 65 && InputMap.fromHidScancode(29) == 90);
        check("scancodes 30..39 are 1..9,0", InputMap.fromHidScancode(30) == '1' && InputMap.fromHidScancode(38) == '9' && InputMap.fromHidScancode(39) == '0');
        check("Enter, Esc, Backspace, Tab, Space", InputMap.fromHidScancode(40) == 257 && InputMap.fromHidScancode(41) == 256
                && InputMap.fromHidScancode(42) == 259 && InputMap.fromHidScancode(43) == 258 && InputMap.fromHidScancode(44) == 32);
        check("arrows", InputMap.fromHidScancode(79) == 262 && InputMap.fromHidScancode(80) == 263 && InputMap.fromHidScancode(81) == 264 && InputMap.fromHidScancode(82) == 265);
        check("F1, F10, F11, F12", InputMap.fromHidScancode(58) == 290 && InputMap.fromHidScancode(67) == 299 && InputMap.fromHidScancode(68) == 300 && InputMap.fromHidScancode(69) == 301);
        check("unknown scancode is 0", InputMap.fromHidScancode(0) == 0 && InputMap.fromHidScancode(1000) == 0);
        boolean hidOk = true;
        for (int sc = 0; sc < 256; sc++) { int k = InputMap.fromHidScancode(sc); if (k != 0 && sc != 228 && Ps2Keys.scancode(k) == 0) { hidOk = false; System.out.println("    scancode " + sc + " -> " + k + " has no PC key"); } }
        check("every converted scancode ends at a key the PC has", hidOk);

        // ---- KeyInput
        Rec rec = new Rec();
        KeyInput in = new KeyInput(() -> rec, () -> now);

        in.keyDown(65, 30); in.keyUp(65, 30);
        check("physical key press and release pass through", rec.take().equals("d65 u65"));

        now += 1000 * MS;
        in.keyDown(65, 30);
        String note = in.character('a');
        in.keyUp(65, 30);
        check("physical keyboard: the typed character is not sent twice", rec.take().equals("d65 u65") && note.contains("not typed again"));

        now += 1000 * MS;
        in.keyDown(65, 30);                    // physical A key on a French keyboard
        in.character('q');                     // ...which types q
        in.keyUp(65, 30);
        check("other layout: the key press wins, no extra key", rec.take().equals("d65 u65"));

        now += 1000 * MS;
        in.keyDown(340, 42); in.keyDown(49, 2); in.character('!'); in.keyUp(49, 2); in.keyUp(340, 42);
        check("shift+1 on a keyboard sends shift, 1 once", rec.take().equals("d340 d49 u49 u340"));

        now += 1000 * MS;
        in.keyDown(66, 48); in.keyUp(66, 48);
        rec.take();
        note = in.character('a');
        check("a character right after a key press is taken as part of it", rec.take().isEmpty() && note.contains("not typed again"));

        now += 1000 * MS;
        note = in.character('a');
        check("on-screen keyboard: a typed character alone becomes a key tap", rec.take().equals("d65 u65") && note.equals("char 'a' typed"));
        now += 100 * MS;
        in.character('A');
        check("capital letter taps shift too", rec.take().equals("d340 d65 u65 u340"));
        now += 100 * MS;
        in.character('\n');
        check("Enter sent as a character", rec.take().equals("d257 u257"));
        now += 100 * MS;
        in.character(8);
        check("Backspace sent as a character", rec.take().equals("d259 u259"));
        now += 100 * MS;
        in.character(0xE9);
        check("character without a key is ignored", rec.take().isEmpty());

        now += 1000 * MS;
        in.keyDown(257, 40); in.keyUp(257, 40);
        now += 150 * MS;
        in.character('a');
        check("Enter as a key, then a typed letter a moment later: both arrive", rec.take().equals("d257 u257 d65 u65"));

        now += 1000 * MS;
        in.keyDown(340, 42);
        in.character('A');
        in.keyUp(340, 42);
        check("shift held on a physical keyboard, character alone: shift is not pressed again", rec.take().equals("d340 d65 u65 u340"));

        note = in.keyDown(-1, 4);
        in.keyUp(-1, 4);
        check("key with no code but a scancode still works", rec.take().equals("d65 u65") && note.contains("from scan 4"));
        note = in.keyDown(-1, 0);
        check("key with nothing usable is ignored and reported", rec.take().isEmpty() && note.contains("no PC key"));

        now += 1000 * MS;
        check("sticky shift starts off", !in.stickyShift());
        in.toggleShift();
        in.tap('Q');
        check("on-screen key with sticky shift taps shift too, then shift is off", rec.take().equals("d340 d81 u81 u340") && !in.stickyShift());
        in.tap('Q');
        check("on-screen key without shift", rec.take().equals("d81 u81"));
        in.toggleShift(); in.toggleShift();
        check("sticky shift toggles off", !in.stickyShift());

        in.keyDown(65, 30); in.keyDown(341, 29);
        rec.take();
        in.releaseAll();
        String rel = rec.take();
        check("release all lets go of everything held", rel.contains("u65") && rel.contains("u341"));
        in.releaseAll();
        check("nothing is released twice", rec.take().isEmpty());

        KeyInput none = new KeyInput(() -> null, () -> now);
        check("no computer: no crash, says so", none.keyDown(65, 30).contains("no computer") && none.character('a').contains("no computer"));
        none.keyUp(65, 30); none.tap(65); none.releaseAll();
        check("no computer: other calls are harmless", true);

        System.out.println(fails == 0 ? "InputTest: all ok" : "InputTest: " + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
