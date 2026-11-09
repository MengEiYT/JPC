#!/usr/bin/env bash
# For people who built the mod before in a folder called "isocraft" (the mod's old name).
# Run it once from the new "jpc" folder, before "gradle build":
#
#   bash tools/from_isocraft.sh
#
# If you had downloaded the KolibriOS image into the old folder (tools/fetch_kolibri.sh), it is copied
# into this folder so the new jar carries KolibriOS again. Nothing in the old folder is changed or deleted.
# This script never fails: with nothing to copy it just says so.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OLD="${1:-$HOME/isocraft}/src/main/resources/isocraft/bundled/kolibri.img"
NEW="$ROOT/src/main/resources/jpc/bundled/kolibri.img"

if [ -f "$NEW" ]; then
	echo "KolibriOS is already in this folder. Nothing to copy."
elif [ -f "$OLD" ]; then
	mkdir -p "$(dirname "$NEW")" && cp "$OLD" "$NEW" && echo "Copied the KolibriOS image from the old isocraft folder."
else
	echo "No KolibriOS image found in the old folder. That is fine: the mod works without it."
	echo "(To bundle KolibriOS, run: bash tools/fetch_kolibri.sh)"
fi
exit 0
