# Third-party code

## The original JPC (x86 PC emulator in pure Java) - GPL-2.0

The mod is named JPC (Java Personal Computer), and so is the emulator inside it. They are two different things: **the original JPC** is the Oxford University emulator described here; **the JPC mod** (this project, formerly called IsoCraft) is the Minecraft mod that carries it. The built-in computer is the original JPC, copied into `src/main/java/org/jpc/` and changed as described below. In the changed files, a comment starting `JPC mod:` marks a change made for the mod.

- Source: https://github.com/jdstroy/JPC (fork of https://github.com/ianopolous/JPC), commit `fd91712f86536f5144844edf012a7ebbf19acfc5`
- Authors: Oxford University Subdepartment of Particle Physics, Ian Preston and others (see the file headers)
- License: GNU GPL version 2, see `LICENSE`. Because the mod contains the original JPC, **the JPC mod as a whole is GPL-2.0** too, and if you give the mod jar to anyone you must also give them the source.
- BIOS: Bochs BIOS (LGPL) and the Plex86/Bochs VGA BIOS (LGPL), in `src/main/resources/jpc/bios/`.

### What was changed

`tools/vendor/vendor_jpc.py` does the import and applies every change, so the changes are exactly what is in that script:

- Only `org.jpc.emulator`, `org.jpc.support` and two classes of `org.jpc.j2se` (`Option`, `VirtualClock`) are copied. The Swing/AWT user interface and the debugger are not.
- No `java.awt`, `javax.swing`, `javax.sound` or `javax.imageio` anywhere, so it runs on Java runtimes that lack them (MojoLauncher / Android). The emulator needs only `java.base` and `java.logging`.
- `DefaultVGACard` (AWT image) is replaced by `HeadlessVGACard`, which draws into an `int[]`.
- Sound output and MIDI are removed (`AudioLayer`, `Midi` are empty; `PCSpeaker` keeps its port 0x61 behaviour).
- BIOS files are loaded from `/jpc/bios/` inside the jar.
- Console noise removed (HLT and "Decoded N blocks" messages).
- Added `PC.abort()` / `Processor.abortWait()` so the emulator thread can be stopped cleanly while the guest is halted.
- The CPU reports family 6 (Pentium II identity) in CPUID so i686-only kernels start, and now also advertises FXSR, SSE and SSE2 (on top of FPU, PSE, TSC, MSR, CX8, PGE, CMOV and MMX). See `Processor.java`, CPUID leaf 1.
- New package `org.jpc.emulator.execution.sse`: MMX, SSE and SSE2 instructions (including NaN/min/max/compare/rounding rules and MXCSR), FXSAVE/FXRSTOR with the 512-byte layout, MMX/x87 register aliasing, and FNSAVE/FRSTOR/FNSTENV/FLDENV that save the whole x87 register stack. Hooked into `FastDecoder` and `Processor`. MXCSR_MASK is reported as 0xFFBF (no DAZ/FTZ).
- x87: the 80-bit conversions in `FpuState64` were fixed (zero, denormals, Inf/NaN, rounding, a mantissa-shift bug).
- CMPXCHG8B now decodes and writes back correctly (the stock tables only produced it for one prefix form).
- Paging fixes found by booting a current Linux kernel: a page is writable only if both the directory and table entries allow it; supervisor writes to read-only pages and 4 MB pages honour CR0.WP; TLB entries are replaced when a linear page gets a new translation (copy-on-write); non-global TLB entries are really flushed on a CR3 write, and CR4.PGE is stored and honoured.
- Unimplemented opcodes raise #UD (and log) instead of stopping the emulator.
- `VGACard`: in a VBE (Bochs dispi) mode the CRTC "line compare" (split screen) is ignored, as in QEMU and Bochs. KolibriOS sets a VBE mode and leaves other CRTC values behind; without this the picture was cut at line 511 and showed the top of the screen again below it.
- Speed (all in `jpc-mod.patch`; none of them changes what a guest can see, apart from the missing empty CD drive below):
  - `VirtualClock.updateAndProcess` no longer polls the timers after every few instructions: it counts instructions and only does the full update when a timer is due (or the speed check is due). A timer that is added, changed or removed, and pause/resume, force the next check.
  - `FastTLB` was rewritten (plain arrays instead of an object per entry, no boxed `HashSet`, no allocation when an entry is filled). A 4 MB page now caches only the 4 KB piece that was touched instead of 1024 pieces at once. KolibriOS maps its memory with 4 MB pages and used to spend most of its time refilling that cache.
  - `LazyCodeBlockMemory`: 16-bit and 32-bit RAM reads and writes are one `VarHandle` access instead of several byte accesses, the code-block entry points do not read EIP a second time, and the self-modifying-code check now covers the bytes that were really written (it used the wrong offset for 16/32-bit writes).
  - `BackgroundCompiler` hands out the code blocks directly. Its counting wrapper (a `volatile` counter, so a memory fence, on every block that runs) only fed a compiler thread that is never started.
  - `DriveSet`: no empty CD-ROM drive is created when no disc image is given. KolibriOS asks an empty drive "are you ready?" thousands of times a second for about ten seconds, twice, while it starts (on the test machine that was about 20 s of a 38 s start; now it is about 15 s). `IDEChannel`: a new ATAPI PACKET command starts with a clean status and error register.
- Linux guests (found by booting Tiny Core Linux and the mod's own test kernels):
  - `IDEChannel`: the CD-ROM's DMA read returned sector N+1 instead of sector N (and a part of a sector when the guest asked for less than 2048 bytes at a time), so Linux could not read its own CD. It now reads exactly the sectors asked for, and no longer prints a line for every read.
  - `IDEChannel` / `BMDMAIORegion`: READ DMA and WRITE DMA for hard disks were missing (the old callback was switched off and did not work). Linux uses DMA for disks, so it read nothing from an attached hard disk and lost its writes. Both directions now work (checked in a guest against the image file, 8 MB read and 2 MB written). Also fixed the 48-bit LBA address (one byte was shifted too far; Linux does not use it here because the drive does not advertise 48-bit addressing).
  - 32-bit `DIV` (`div_Ed`, six files): the dividend was divided as a signed number, so any value of 2^63 or more gave a false "divide error" (#DE). The Linux kernel's 64-by-32 division (`do_div`) hit it a little after start-up and crashed. It now divides as unsigned.
  - `LinearAddressSpace.PageFaultWrapper`: the stack trace of the page-fault exception is no longer rebuilt on every page fault (a Linux guest takes millions of them, and nothing reads the trace).
- `tools/vendor/jpc-mod.patch` holds everything beyond `vendor_jpc.py`'s edits and is applied by that script.

## KolibriOS (optional, bundled floppy image) - GPL-2.0

If you ran `tools/fetch_kolibri.sh` before building, the jar contains `jpc/bundled/kolibri.img`, the official KolibriOS floppy image, unchanged. The mod copies it to the `isos` folder on first start (`BundledImages`).

- Project: https://kolibrios.org, source code: https://git.kolibrios.org
- The script fetches build `0.7.7.0-9253-g855f02d2` (English floppy image) from https://builds.kolibrios.org/ci/ by default; the part after the `g` in that name is the source commit. A different build can be chosen with `KOLIBRI_BUILD`.
- License: the KolibriOS project says it is distributed under the GNU GPL version 2. If you give out a jar that contains the image, you must also make its source available: the link above (for the build named in the file you bundled) does that, or give the source along with the jar.
- The image is a separate program that the emulator runs; the JPC mod's code does not link to it.

## Credits for the JPC mod itself

By MengEi, written together with Claude (an AI assistant made by Anthropic). Listed in `fabric.mod.json` as author and contributors, together with the original JPC emulator's authors. The mod was called IsoCraft until update 8.
