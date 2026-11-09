#!/usr/bin/env python3
"""Documents exactly how JPC was imported. Run only to re-import or upgrade JPC.

Copy JPC's emulator core into the mod and strip everything that needs AWT, Swing or javax.sound.

Every patch asserts that it matched, so a source change cannot slip by silently.
Usage: vendor_jpc.py JPC_CHECKOUT MOD_DIR [--no-patch]

--no-patch stops before tools/vendor/jpc-mod.patch is applied. Use it to get the plain stripped tree that the
patch is made against: after changing JPC code in the mod, run
    vendor_jpc.py JPC_CHECKOUT EMPTY_DIR --no-patch
    (cd EMPTY_DIR/src/main/java && mkdir ../../../a && cp -r org ../../../a/)   # the stripped tree is "a"
    (cd MOD_DIR/src/main/java && mkdir ../../../../b && cp -r org ../../../../b/)  # the mod's tree is "b"
    diff -ruN a/org b/org > tools/vendor/jpc-mod.patch
EMPTY_DIR needs src/main/java/org/jpc/emulator/pci/peripheral/HeadlessVGACard.java copied from the mod first.
"""
import re
import shutil
import subprocess
import sys
from pathlib import Path

jpc = Path(sys.argv[1])
mod = Path(sys.argv[2])
src = jpc / "src" / "org" / "jpc"
dst = mod / "src" / "main" / "java" / "org" / "jpc"
res = mod / "src" / "main" / "resources" / "jpc" / "bios"

vga_file = dst / "emulator" / "pci" / "peripheral" / "HeadlessVGACard.java"
vga_keep = vga_file.read_bytes() if vga_file.exists() else None
if dst.exists():
    shutil.rmtree(dst)
(dst / "j2se").mkdir(parents=True)
shutil.copytree(src / "emulator", dst / "emulator")
shutil.copytree(src / "support", dst / "support")
for name in ("Option.java", "VirtualClock.java"):
    shutil.copy(src / "j2se" / name, dst / "j2se" / name)

res.mkdir(parents=True, exist_ok=True)
for name in ("bios.bin", "vgabios.bin"):
    shutil.copy(jpc / "resources" / "bios" / name, res / name)


def patch(path, edits):
    text = path.read_text(encoding="latin-1")
    for old, new in edits:
        if old not in text:
            raise SystemExit(f"patch failed in {path.name}: {old[:70]!r}")
        text = text.replace(old, new)
    path.write_text(text, encoding="latin-1")


def cut(path, start_marker, end_marker):
    """Remove text from start_marker up to (not including) end_marker."""
    text = path.read_text(encoding="latin-1")
    a = text.index(start_marker)
    b = text.index(end_marker, a)
    path.write_text(text[:a] + text[b:], encoding="latin-1")


# ---- PC.java: no Swing monitor, no AWT key mapping, no debugger helper
pc = dst / "emulator" / "PC.java"
patch(pc, [
    ("import org.jpc.debugger.LinearMemoryViewer;\n", ""),
    ("import org.jpc.j2se.KeyMapping;\n", ""),
    ("import org.jpc.j2se.PCMonitor;\n", ""),
    ("import javax.swing.*;\n", ""),
    ("parts.add(new DefaultVGACard());", "parts.add(new HeadlessVGACard());"),
    ('Option.bios.value("/resources/bios/bios.bin")', 'Option.bios.value("/jpc/bios/bios.bin")'),
    ('new VGABIOS("/resources/bios/vgabios.bin")', 'new VGABIOS("/jpc/bios/vgabios.bin")'),
])
cut(pc, "    public void sendKeysDown(String text)", "    public void sendMouse(Integer dx")
cut(pc, "    public JPanel getNewMonitor()", "    public void triggerSpuriousInterrupt()")

# ---- VGACard.java: AWT-free display API
vga = dst / "emulator" / "pci" / "peripheral" / "VGACard.java"
patch(vga, [
    ("import java.awt.*;\n", ""),
    ("    public abstract Dimension getDisplaySize();\n",
     "    public abstract int getDisplayWidth();\n\n    public abstract int getDisplayHeight();\n"),
    ("        Dimension size = getDisplaySize();\n        dirtyDisplayRegion(0, 0, size.width, size.height);\n",
     "        dirtyDisplayRegion(0, 0, getDisplayWidth(), getDisplayHeight());\n"),
    ("    public abstract void setMonitor(PCMonitor mon);\n", ""),
])
text = vga.read_text(encoding="latin-1")
text = re.sub(r"import org\.jpc\.j2se\.PCMonitor;\n", "", text)
vga.write_text(text, encoding="latin-1")

# ---- DefaultVGACard (AWT BufferedImage) is replaced by HeadlessVGACard
(dst / "emulator" / "pci" / "peripheral" / "DefaultVGACard.java").unlink()

# ---- sound: javax.sound is not available everywhere (Android launchers), so stub it out
(dst / "emulator" / "peripheral" / "AudioLayer.java").write_text('''package org.jpc.emulator.peripheral;

/** Sound output is not supported in the JPC mod: this keeps the emulator code compiling without javax.sound. */
public class AudioLayer {
    public static boolean open(int bufferSize, int freq) {
        return false;
    }

    public static void stop() {
    }
}
''')

midi = dst / "emulator" / "peripheral" / "Midi.java"
midi.write_text('''package org.jpc.emulator.peripheral;

/** MIDI is not supported in the JPC mod (no javax.sound.midi). Same entry points as the original, all no-ops. */
public class Midi {
    public static class Sysex {
    }

    public Sysex sysex = new Sysex();

    public static void MIDI_RawOutByte(int data) {
    }

    public static boolean MIDI_Available() {
        return false;
    }

    public Midi() {
    }

    public static void MIDI_Destroy() {
    }

    public static void MIDI_Init() {
    }
}
''')

# ---- PCSpeaker: keep the port 0x61 logic (guests poll it for timing), drop the MIDI synthesizer
spk = dst / "emulator" / "peripheral" / "PCSpeaker.java"
patch(spk, [("import javax.sound.midi.*;\n", "")])
cut(spk, "    private Synthesizer synthesizer;", "    public int mode;")
cut(spk, "    private void configure()", "    private int getNote()")
patch(spk, [("    private int getNote()", """    private void configure()
    {
        // no sound output in the JPC mod
        enabled = false;
    }

    private int getNote()""")])
cut(spk, "    private void playNote(int note)", "    public synchronized void play()")
patch(spk, [("    public synchronized void play()", """    private void playNote(int note)
    {
    }

    private void stopNote(int note)
    {
    }

    public synchronized void play()""")])
cut(spk, "    private void programChange(int program) {", "    public void saveState")

# ---- quiet: JPC prints to stdout on every guest HLT and every 1000 decoded blocks
patch(dst / "emulator" / "processor" / "Processor.java", [
    ('        System.out.printf("*****START HALT ticks=%016x\\n", vmClock.getTicks());\n', ""),
    ('        System.out.printf("END HALT ticks=%016x, interrupts=%d\\n", vmClock.getTicks(), ints);\n', ""),
])
for name in ("FastDecoder.java", "Disassembler.java"):
    f = dst / "emulator" / "execution" / "decoder" / name
    text = f.read_text(encoding="latin-1")
    text, n = re.subn(r'\n[ \t]*if \(decodeCount % 1000 == 0\)\n[ \t]*System\.out\.println\("Decoded "\+decodeCount \+ " blocks\.\.\."\);', "", text)
    if n != 1:
        raise SystemExit(f"could not silence decoder message in {name}")
    f.write_text(text, encoding="latin-1")

# ---- CPUID: report family 6 (Pentium II, same identity JPC uses for cpulevel 6) instead of family 5.
# Linux i686 kernels refuse to start on "an i586 CPU"; the feature bits stay those of the level-5 CPU.
patch(dst / "emulator" / "processor" / "Processor.java", [
    ("                    r_eax.set32(0x00000513);\n                    r_ebx.set32(0);",
     "                    r_eax.set32(0x00000634); // JPC mod: family 6 (Pentium II) so i686 kernels start; feature bits below are unchanged\n                    r_ebx.set32(0);"),
])

# ---- clean shutdown: let the owner break a guest HLT wait (otherwise only the next timer tick ends it)
patch(dst / "emulator" / "processor" / "Processor.java", [
    ("    public void waitForInterrupt()\n    {\n        int ints = 0;\n        while ((interruptFlags & IFLAGS_HARDWARE_INTERRUPT) == 0) {",
     "    private volatile boolean abortWait;\n\n    /** JPC mod: makes a pending HLT wait return so the emulator thread can stop. */\n    public void abortWait()\n    {\n        abortWait = true;\n    }\n\n    public void waitForInterrupt()\n    {\n        int ints = 0;\n        while ((interruptFlags & IFLAGS_HARDWARE_INTERRUPT) == 0 && !abortWait) {"),
])
patch(pc, [
    ("    public void triggerSpuriousInterrupt()", "    /** JPC mod: asks the CPU to leave any HLT wait, so a thread running execute() can stop. */\n    public void abort()\n    {\n        processor.abortWait();\n    }\n\n    public void triggerSpuriousInterrupt()"),
])

# ---- the AWT-free VGA card is part of the mod (written by hand), keep it across re-vendoring
if vga_keep is None:
    raise SystemExit("HeadlessVGACard.java is missing from the mod; restore it first")
vga_file.write_bytes(vga_keep)

# ---- JPC mod CPU additions, kept as a patch (tools/vendor/jpc-mod.patch) so they survive a re-import:
#   * MMX / SSE / SSE2 / FXSR (new package org.jpc.emulator.execution.sse, hooked in FastDecoder and Processor),
#     so 32-bit Linux distributions that require an i686 CPU with SSE2 can start
#   * complete FNSAVE / FRSTOR / FNSTENV / FLDENV (the stock ones dropped the register stack)
#   * x87 80-bit conversions fixed (zero, denormals, Inf/NaN, rounding, a mantissa shift bug)
#   * unimplemented opcodes raise #UD (and log) instead of stopping the emulator
if "--no-patch" in sys.argv[3:]:
    print("vendored OK (without jpc-mod.patch)")
    raise SystemExit(0)
subprocess.run(["patch", "-p1", "-s", "-i", str(Path(__file__).with_name("jpc-mod.patch"))],
               cwd=mod / "src" / "main" / "java", check=True)

print("vendored OK")
