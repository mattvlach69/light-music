#!/bin/bash
# Adds a podcast feed (like a private Patreon RSS link) to the Music tool on the Light Phone.
# Usage: double-click, paste the feed link, press Enter.
# Needs: phone plugged in, USB debugging on, adb installed (brew install android-platform-tools).
PKG=com.matthewvlach.music
read -r -p "Paste the podcast feed link, then press Enter: " URL
URL="$(echo "$URL" | tr -d '[:space:]')"
case "$URL" in
  http://*|https://*) ;;
  *) echo "That doesn't look like a link. It should start with https://"; read -r; exit 1 ;;
esac
case "$URL" in
  *\'*|*\"*|*\`*) echo "That link has quote marks in it, which this script can't handle."; read -r; exit 1 ;;
esac
adb shell "run-as $PKG sh -c \"mkdir -p files/shared/podcasts && echo '$URL' >> files/shared/podcasts/subscriptions.txt\"" \
  || { echo "Couldn't reach Music. Is the phone plugged in and unlocked, with Music installed?"; read -r; exit 1; }
echo "Added. On the phone, open Music, go to Podcasts and tap the refresh button."
read -r -p "Press Enter to close."
