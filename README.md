# JPC (Java Personal Computer)

Fabric mod for Minecraft 26.3 (Fabric Loader 0.19.5, Fabric API 0.161.0+26.3).

**JPC** is short for **J**ava **P**ersonal **C**omputer. It adds a **Computer** block with a PC emulator built into the mod. Right-click it and a full-screen computer opens inside Minecraft and boots a real ISO. Nothing else to install: no QEMU, no Termux, no other app. It is plain Java, so it also runs on MojoLauncher (Android).

JPC used to be called **IsoCraft** (see *Coming from IsoCraft* below). The x86 emulator inside is the open-source JPC emulator from Oxford University (see *Credits*); this mod is a different project that carries it. In this file "JPC" means the mod, and "the original JPC emulator" means the Oxford one.

## Crafting the Computer (survival)

Use a **crafting table** (the 3x3 grid, not the small 2x2 grid in your inventory) and put the items in exactly this shape:

| | | |
|---|---|---|
| stone | stone | stone |
| stone | redstone dust | stone |
| stone | glass pane | stone |

That is 7 stone, 1 redstone dust and 1 glass pane, and it makes 1 Computer.

- **"Stone" can be stone, cobblestone, cobbled deepslate or blackstone.** Mining stone gives *cobblestone*, so plain cobblestone is the easy way. (Before update 9 only real stone worked, and stone has to be smelted from cobblestone, so many people could not craft it.)
- **The recipe unlocks by itself** the moment you join a world. Open the **recipe book** (the green book next to the crafting grid), look in the **Redstone** tab or type "Computer" in its search box, and click the recipe: the grid fills itself if you carry the items.
- Breaking the block gives the Computer back (a pickaxe is the fastest tool).
- In creative mode, take it from the **Redstone Blocks** tab. With cheats on, `/give @s jpc:computer` gives one too.
- If the recipe is still not in the book, run `/recipe give @s jpc:computer` (needs cheats). If it still fails, look in `logs/latest.log` for a line that has `jpc:computer` in it, because the game says there why it could not load the recipe.

## Use

1. Start the game once. It makes an **`isos`** folder in the game folder (next to `mods` and `config`), and puts **KolibriOS** in it (`KolibriOS.img`, see *Included: KolibriOS* below).
2. Put your own `.iso` files (and floppy `.img` files) in that folder too.
3. Craft the Computer (see *Crafting the Computer* above) or find it in the Redstone creative tab, place it, **right-click** it.
4. The **boot menu** opens. Pick an image from the list, like choosing a USB stick to boot from, set RAM and the CPU limit, and press **Start**.

| Boot menu | What it does |
|---|---|
| list of images | `.iso` boots as a CD, a small `.img` as a floppy, a big `.img`/`.hdd`/`.raw` as a hard disk. Use *Prev/Next* when there are many |
| **RAM** | guest memory. It comes out of the game's memory, so the list stops at what fits (raise the game's memory in the launcher for more) |
| **CPU limit** | most of one CPU core the computer may use. Lower it if the game stutters |
| **Hard disk** | attach a disk image next to the CD, so an installer has somewhere to install to |
| **New disk / Create disk** | makes an empty disk (`disk1.hdd`, ...) of the chosen size in the `isos` folder and attaches it |
| **Refresh list** | rescan the folder after you added files |

The menu remembers your choice (it is saved in `config/jpc.json`). A computer that is already running opens straight to its screen.

| Action | Result |
|---|---|
| Right-click the block | boot menu if the computer is off, else the full-screen computer |
| **Right Ctrl** or the **Exit** button | leaves the screen (keys and mouse go to the computer, so Esc is not used for this) |
| **Power off** button | switches the computer off and returns to the boot menu |
| **Restart** button | reboots the computer with the same settings |
| Sneak + right-click | turns the block's screen light on or off |
| Bottom buttons | Esc, Tab, Enter, arrows, Ctrl+Alt+Del (handy on a touch screen) |

`config/jpc.json` (paths are relative to the game folder; the boot menu fills these in for you):

```json
{
  "mode": "builtin",
  "iso": "isos/mydisk.iso",
  "floppy": "",
  "hdImage": "",
  "boot": "auto",
  "ramMb": 64,
  "showBootMenu": true,
  "isoFolder": "isos",
  "pauseWhenClosed": true,
  "maxCpuPercent": 100,
  "showKeyHints": false
}
```

`hdImage` is a raw disk image the computer can write to. `boot` is `auto`, `cdrom`, `disk` or `floppy`. Set `"showBootMenu": false` to skip the menu and always start from the settings above. `isoFolder` is where the menu looks for images (a folder name inside the game folder, or a full path).

The computer runs on its own thread, never on the game's. It sleeps while the guest is idle, freezes while its screen is closed (`pauseWhenClosed`) and freezes while its screen is open but the game window has no focus. `maxCpuPercent` (10-100) caps how much of one CPU core that thread may use: **if the game stutters while a busy guest such as KolibriOS runs, set it to 60-70**; the guest then runs slower but the game gets room to breathe, and the phone runs cooler.

## Coming from IsoCraft

JPC is the same mod under a new name (new id `jpc`, jar `jpc-0.1.0.jar`). What carries over and what does not:

- **Delete the old `isocraft-*.jar` from your `mods` folder**, or both versions load.
- Your settings are kept: if there is no `config/jpc.json` yet, the old `config/isocraft.json` is read and saved under the new name (the old file is left alone).
- Your `isos` folder and everything in it stays. KolibriOS is not copied a second time (the hidden `.isocraft-installed` file is read once and becomes `.jpc-installed`).
- If you build the mod yourself and now use a new folder called `jpc`, run `bash tools/from_isocraft.sh` once inside it. It copies the KolibriOS image you downloaded into the old `isocraft` folder (if there is one) so the new jar carries it too. The old folder can be deleted after the new jar works.
- **A Computer block you already placed in a world is gone** after the update, because the block's id changed from `isocraft:computer` to `jpc:computer`. Craft or take a new one from the Redstone creative tab.

## Included: KolibriOS

[KolibriOS](https://kolibrios.org) is a tiny 32-bit operating system with a graphical desktop that fits on one floppy disk. The mod jar can carry it and copies it to `isos/KolibriOS.img` the first time the game starts, so the boot menu has something to start straight away.

- It is copied **once**. If you delete `KolibriOS.img` it does not come back (the hidden file `isos/.jpc-installed` remembers that), and a file with that name that you put there yourself is never overwritten. To get it back, delete the `.jpc-installed` file.
- The image is **not stored in this source folder** (it is a binary file from another project). `tools/fetch_kolibri.sh` downloads the official image, checks it against KolibriOS's published checksum, and puts it where the build picks it up. Without that step the mod still builds and works, it just has no KolibriOS.
- KolibriOS is GPL-2.0 like this mod. Its source is at https://git.kolibrios.org, see `THIRD_PARTY.md`.
- **Tested** with the KolibriOS 9252 floppy image: it boots to its desktop and its boot menu takes the on-screen keyboard. **The mouse does not work in KolibriOS yet** (the pointer stays where it is). That is the next thing to fix.
- **Choosing the screen size.** KolibriOS starts in 1024x768 after a 5-second countdown ("or wait N seconds to continue automatically"). To change it, wait for the blue menu (keys pressed before it appears are lost), then: press **Esc** (stops the countdown), press **a** (the list of screen modes), move with **^** / **v** (bottom row of buttons), press **Enter** to choose, **Enter** again to continue, and **y** when it asks whether to save. The choice is saved inside the image file, so the next start already uses it. 800x600 or 640x480 is much lighter on a phone. Use the **Keyboard** button for the letters.
- The guest writes into the image, so `isos/KolibriOS.img` changes when you save settings. For a fresh copy, delete it **and** the hidden `isos/.jpc-installed` file, then start the game.

## What it can run (please read)

The built-in computer is the original **JPC 32-bit x86 emulator** (Oxford University), extended by this mod so it looks like a Pentium II-class PC (family 6) with **MMX, SSE, SSE2, FXSAVE/FXRSTOR and CMPXCHG8B**. That means:

- **No 64-bit guests.** A 64-bit-only ISO will not boot. 32-bit ("x86", "i386", "i686") ISOs are the target.
- **32-bit Linux runs.** Kernels that insist on an "i686 CPU" (Alpine x86, and similar) no longer stop with the *"requires an i686 CPU, but only detected an i586"* message. The new instructions were checked against a real CPU (744 SSE/SSE2/MMX cases plus x87 state save/restore, all identical).
- **Tested: Tiny Core Linux (32-bit `TinyCore-current.iso`) boots all the way to its graphical desktop.** Two emulator bugs used to stop every Linux CD: the CD drive handed out the wrong sector when the guest read it with DMA (so Linux could not find its files on the disc), and a 32-bit divide gave a false "divide error" for large numbers (so the kernel crashed a little later). Both are fixed. Hard-disk DMA was missing too (a Linux guest read nothing from, and lost everything written to, an attached hard disk); that is fixed as well.
- **Alpine x86** is covered in *Booting Linux* below.
- Still missing: SSE3 and newer (SSSE3, SSE4, AVX), PAE, 64-bit mode, DAZ/FTZ, and exact rcp/rsqrt approximations. A guest that requires one of these will not run.
- The original JPC's authors say it boots DOS, Windows up to 95 (98 only in safe mode) and some Linux systems, mostly in text mode. **Most current Windows ISOs are 64-bit and will not boot.** Start with small 32-bit ones (FreeDOS, Tiny Core, Alpine x86, old Linux rescue discs).
- For Alpine x86 set `"ramMb": 128` (or 256 if it complains about memory). The guest memory counts against the game's memory, so on a phone give the launcher enough RAM.
- No sound and no network.
- It is an interpreter in Java, so it is **slow**: roughly 10-40 million instructions per second on a desktop PC, and a phone is several times slower. The title bar of the computer screen shows the current speed ("4.2 MIPS") and the guest screen size; the game log gets a "VM speed" line every 10 seconds. A modern Linux kernel needs hundreds of millions of instructions to start, so **expect it to take many minutes on a phone** before anything useful appears. Wait before assuming it hung.
- **Making it faster.** A computer that is only waiting (a desktop with nothing happening, a prompt) now uses almost no CPU. Things that help a slow system most: a **smaller screen size** inside the guest (KolibriOS: 800x600 or 640x480, see above), **fewer colours**, and **less RAM** than you need to ask for. Leave `maxCpuPercent` at 100 unless the game itself stutters. No empty CD drive is attached unless you start from an ISO, because some systems (KolibriOS) keep asking an empty drive "are you ready?" for about 20 seconds while they start. On the test machine KolibriOS now reaches its desktop in about 15 seconds instead of about 38. Phones are slower than that machine, so expect longer there.
- Graphical systems (KolibriOS, Windows 9x) cost more than text ones, because the whole picture has to be converted and uploaded. If the system asks for a screen resolution, **pick the smallest one** (640x480 or less) and the lowest colour depth.
- The mouse is a PS/2 mouse (moves are relative), so the guest pointer can drift away from yours.

## Booting Linux (Tiny Core, Alpine)

**Tested:** Tiny Core Linux 17.1 (the 32-bit `TinyCore-current.iso`, kernel 6.18) boots to its desktop, and a Linux guest can read from and write to an attached hard disk (the boot menu's **Hard disk** choice shows up as `/dev/sda`).

- **It needs patience.** Tiny Core runs about 4.8 billion instructions before its desktop appears. That is about 2 minutes on a desktop PC (around 40 MIPS) and several times longer on a phone: at 8 MIPS it is about 10 minutes. Watch the speed in the title bar: while it shows more than a few MIPS the computer is working, not frozen. The screen shows a few progress lines (*Starting udev daemon...*, *Loading extensions...*) and then looks quiet while the extensions load, then the desktop appears.
- **Boot codes make it lighter.** At the Tiny Core menu (it starts by itself after 60 seconds, so be quick) press **Tab**, type a space and the codes at the end of the line, then press **Enter**. The **Tab** and **Keyboard** buttons at the bottom of the computer screen do this on a phone:
  - ` xvesa=640x480x16` makes the desktop 640x480 with fewer colours (much less picture to draw on a phone). Tested.
  - ` text` stops at a command prompt and never starts the desktop. Tested. (The hard-disk checks were done this way, with `dd` and `cmp`.)
- **RAM:** these tests used 256 MB. (The guest's memory comes out of the game's memory, see the boot menu.)
- **Alpine x86** has not been tried with a real ISO here, because the file is too big to send. It needs the same things that Tiny Core needed, so it has a good chance now. If it still stops, please send a photo of the last lines on the screen. (Alpine's 32-bit kernel does not need PAE.)

## Android / MojoLauncher

Same jar, same config, nothing else needed. Notes: put the ISO inside the game folder MojoLauncher uses and use a relative path. **Typing:** a physical keyboard works, and so does the launcher's own keyboard (the computer screen accepts both key presses and typed characters, and never types a key twice). If nothing you press reaches the computer, press the **Keyboard** button at the bottom of the computer screen: it opens a small on-screen keyboard drawn by the mod (letters, digits, symbols, Shift, Space, Backspace) over the lower part of the screen (the picture moves up to make room when the screen is big enough). It does not depend on the launcher; press the button again to close it, and it stays open when you leave and come back. Click its keys with the pointer; Shift is sticky for one key (it turns orange, and the keys show capitals and symbols). Next to it are Esc / Tab / Enter / arrow / Ctrl+Alt+Del buttons. There is also an optional yellow line at the top of the picture that shows for a few seconds what the last key did (for example `char 'a' typed`), and logs the first 60 as `Keyboard: ...`. It is **off by default**; it is only for finding out why a keyboard does not work. Turn it on with the **Hint** button on the on-screen keyboard, or with `"showKeyHints": true` in the config. The default 64 MB RAM is a good start for small systems, since the game itself needs memory too; 32-bit Linux distributions need `"ramMb": 128` or more.

## Optional: QEMU (desktop, 64-bit guests)

Set `"mode": "qemu"` (plus `"arch": "x86_64"`, `"iso"`, ...) to use an installed QEMU instead. This needs QEMU on your PC and does not work on Android. `"mode": "remote"` connects to a VNC server that is already running. These two modes are not needed for normal use.

## Build

Needs JDK 25.

    bash tools/fetch_kolibri.sh              # optional: puts KolibriOS into the jar (needs curl or wget)
    gradle wrapper --gradle-version 9.8.2   # once, creates ./gradlew
    ./gradlew build                          # jar in build/libs/
    ./gradlew runClient                      # test in a dev client

No local setup: push this folder to GitHub, the workflow in `.github/workflows/build.yml` builds the jar.

## Tests (no Minecraft needed)

    FLOPPY_IMG=/path/to/bootable-floppy.img tools/run_smoke_test.sh

Boots the built-in computer headless, types into it, pauses it, and tests the QEMU/VNC link against a mock server. `tools/make_eltorito_iso.py` builds a bootable test ISO from a floppy image (`BOOT_ISO=... ` runs the CD-ROM boot test).

## Credits

- **MengEi**: idea, design and testing; author of the JPC mod (called IsoCraft before update 8).
- **Claude** (an AI assistant made by Anthropic): wrote much of the code together with MengEi: the Computer block, the boot menu, the performance work, and the emulator extensions (i686/SSE/SSE2, paging fixes, the Linux disk and divide fixes).
- **The original JPC emulator** (the x86 PC inside this mod, not the same thing as this mod): Oxford University Subdepartment of Particle Physics, Ian Preston and others (GPL-2.0). The mod is named after it.
- **KolibriOS**: the included operating system, by the KolibriOS team (GPL-2.0).

MengEi, Claude and the original JPC authors are also in the mod's own info (`fabric.mod.json`: *authors* and *contributors*), which mod lists such as Mod Menu show.

## License

GPL-2.0, because it contains JPC. See `LICENSE` and `THIRD_PARTY.md`.
