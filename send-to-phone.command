#!/bin/bash
# Copies music onto the Light Phone for the Music tool.
# Usage: double-click, then drag a folder (Artist or Album) into this window and press Enter.
# Needs: phone plugged in, USB debugging on, adb installed (brew install android-platform-tools).
PKG=com.matthewvlach.music
read -r -p "Drag a music folder here, then press Enter: " SRC
SRC="${SRC%\'}"; SRC="${SRC#\'}"; SRC="$(eval echo "$SRC")"
[ -d "$SRC" ] || { echo "That isn't a folder."; read -r; exit 1; }
NAME="$(basename "$SRC")"
echo "Copying \"$NAME\" to the phone..."
adb shell rm -rf "/data/local/tmp/lp-music" >/dev/null
adb push "$SRC" "/data/local/tmp/lp-music/$NAME" || { echo "Copy failed. Is the phone plugged in and unlocked?"; read -r; exit 1; }
adb shell "run-as $PKG mkdir -p files/shared/music && run-as $PKG cp -r '/data/local/tmp/lp-music/$NAME' files/shared/music/" \
  || { echo "Couldn't hand the files to Music. Is the Music tool installed?"; read -r; exit 1; }
adb shell rm -rf "/data/local/tmp/lp-music" >/dev/null
echo "Done. Open Music on the phone and it will show up."
read -r -p "Press Enter to close."
