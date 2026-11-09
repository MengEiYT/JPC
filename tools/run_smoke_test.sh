#!/usr/bin/env bash
# Tests the VM code without Minecraft:
#   0. boot menu logic (image folder scan, pages, settings) and bundled-image install
#   1. QEMU/VNC link against two mock VNC servers (needs python3)
#   2. the built-in emulator booting a floppy image, if FLOPPY_IMG=/path/to/floppy.img is set
#      (and a bootable CD image too, if BOOT_ISO=/path/to/some.iso is set)
# Needs JDK 21+. No QEMU and no Minecraft needed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
PORT="${1:-5999}"
PORT2=$((PORT + 1))
trap 'kill ${PID1:-0} ${PID2:-0} 2>/dev/null || true; rm -rf "$WORK"' EXIT

echo "compiling..."
find "$ROOT/src/main/java/org/jpc" "$ROOT/src/main/java/com/jpc/vm" -name '*.java' > "$WORK/files.txt"
echo "$ROOT/tools/VncSmokeTest.java" >> "$WORK/files.txt"
echo "$ROOT/tools/JpcSmokeTest.java" >> "$WORK/files.txt"
echo "$ROOT/tools/MenuLogicTest.java" >> "$WORK/files.txt"
echo "$ROOT/tools/BundledImagesTest.java" >> "$WORK/files.txt"
echo "$ROOT/tools/InputTest.java" >> "$WORK/files.txt"
javac -J-Xmx4g -nowarn -proc:none -encoding ISO-8859-1 -d "$WORK/classes" @"$WORK/files.txt" 2>&1 | grep -v "^Note:\|Picked up" || true

python3 "$ROOT/tools/mock_rfb_server.py" "$PORT" "$WORK/server1.log" &
PID1=$!
python3 "$ROOT/tools/mock_rfb_server.py" "$PORT2" "$WORK/server2.log" &
PID2=$!
sleep 1

STATUS=0
java -cp "$WORK/classes" VncSmokeTest "$PORT" "$PORT2" || STATUS=$?
echo "--- boot menu logic (image folder scan, pages, RAM/CPU/disk settings)"
java -cp "$WORK/classes" MenuLogicTest | grep -v "^  ok" || STATUS=$?
echo "--- bundled images (KolibriOS copied into the images folder once)"
java -cp "$WORK/classes" BundledImagesTest | grep -v "^  ok" || STATUS=$?
echo "--- keyboard handling (typed characters, key presses, on-screen keys)"
java -cp "$WORK/classes" InputTest | grep -v "^  ok" || STATUS=$?

if [ -n "${FLOPPY_IMG:-}" ]; then
	echo "--- built-in emulator ($FLOPPY_IMG)"
	OUT="${SCREENSHOT_PREFIX:-$WORK/screen}"
	java -Xmx1g -cp "$WORK/classes:$ROOT/src/main/resources" JpcSmokeTest "$FLOPPY_IMG" "$OUT" ${BOOT_ISO:+"$BOOT_ISO"} || STATUS=$?
	echo "screenshots: $OUT-boot.png $OUT-typed.png ${BOOT_ISO:+$OUT-iso.png}"
else
	echo "--- built-in emulator test skipped (set FLOPPY_IMG=/path/to/bootable-floppy.img)"
fi
exit $STATUS
