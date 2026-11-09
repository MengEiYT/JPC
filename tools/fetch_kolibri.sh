#!/usr/bin/env bash
# Downloads the official KolibriOS floppy image and puts it where the build picks it up
# (src/main/resources/jpc/bundled/kolibri.img). Run it once before `gradle build`;
# the finished jar then copies KolibriOS into the "isos" folder the first time the mod starts.
#
#   bash tools/fetch_kolibri.sh
#
# The download is checked against the checksum list published next to it on builds.kolibrios.org.
# Another build:   KOLIBRI_BUILD=0.7.7.0-9253-g855f02d2 bash tools/fetch_kolibri.sh
# Another mirror:  KOLIBRI_BASE=https://example.org/dir KOLIBRI_NAME=file.img bash tools/fetch_kolibri.sh
# Needs curl or wget, and sha256sum (all in Termux: pkg install curl coreutils).
# Old nightly builds get deleted from time to time. If the download fails, open
# https://www.kolibrios.org/en/download in a browser, pick a newer "en_US ... .img" build and set
# KOLIBRI_BUILD to its version string.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${KOLIBRI_BUILD:-0.7.7.0-9253-g855f02d2}"
BASE="${KOLIBRI_BASE:-https://builds.kolibrios.org/ci/$BUILD/en_US}"
NAME="${KOLIBRI_NAME:-kolibrios-$BUILD-en_US.img}"
DEST="$ROOT/src/main/resources/jpc/bundled/kolibri.img"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() { # url file
	if command -v curl >/dev/null 2>&1; then
		curl -fL --retry 3 -sS -o "$2" "$1"
	elif command -v wget >/dev/null 2>&1; then
		wget -q -O "$2" "$1"
	else
		echo "Need curl or wget (Termux: pkg install curl)." >&2
		return 1
	fi
}

echo "Downloading $NAME ..."
fetch "$BASE/$NAME" "$WORK/image" || { echo "Download failed: $BASE/$NAME" >&2; exit 1; }

# The download page says the files are 7-zip compressed. If this one is, unpack it.
if [ "$(head -c 2 "$WORK/image")" = "7z" ]; then
	SEVENZ="$(command -v 7z || command -v 7zz || command -v 7za || true)"
	if [ -z "$SEVENZ" ]; then
		echo "The file is 7-zip compressed. Install 7z (Termux: pkg install 7zip) and run this again." >&2
		exit 1
	fi
	mkdir "$WORK/x"
	"$SEVENZ" x -y -o"$WORK/x" "$WORK/image" >/dev/null
	UNPACKED="$(find "$WORK/x" -type f -name '*.img' | head -n 1)"
	[ -n "$UNPACKED" ] || { echo "No .img inside the archive." >&2; exit 1; }
	mv "$UNPACKED" "$WORK/image"
fi

HAVE="$(sha256sum "$WORK/image" | cut -d' ' -f1)"
echo "sha256: $HAVE"
if fetch "$BASE/sha256sums.txt" "$WORK/sums" 2>/dev/null; then
	WANT="$(awk -v n="$NAME" '{f=$2; sub(/^\*/, "", f); if (f == n) print $1}' "$WORK/sums" | head -n 1)"
	if [ -z "$WANT" ]; then
		echo "WARNING: $NAME is not in sha256sums.txt, so it could not be checked." >&2
	elif [ "$WANT" != "$HAVE" ]; then
		echo "CHECKSUM MISMATCH: expected $WANT" >&2
		echo "Not installing. Try again, or download the file in a browser and compare." >&2
		exit 1
	else
		echo "Checksum matches the official list."
	fi
else
	echo "WARNING: could not download sha256sums.txt, so the checksum was not verified." >&2
	echo "         Compare the sha256 above with the one on the KolibriOS download page." >&2
fi

SIZE="$(stat -c %s "$WORK/image")"
SIG="$(od -An -tx1 -j510 -N2 "$WORK/image" | tr -d ' \n')"
if [ "$SIZE" -lt 100000 ] || [ "$SIZE" -gt 2949120 ] || [ "$SIG" != "55aa" ]; then
	echo "This does not look like a floppy image (size $SIZE bytes, boot signature '$SIG')." >&2
	echo "Not installing." >&2
	exit 1
fi

mkdir -p "$(dirname "$DEST")"
cp "$WORK/image" "$DEST"
echo "Done: $DEST ($SIZE bytes)"
echo "Now build the mod:  gradle build --no-daemon"
