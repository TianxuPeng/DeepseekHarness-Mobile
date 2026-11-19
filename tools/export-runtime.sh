#!/data/data/com.termux/files/usr/bin/bash
# Run inside Termux on an arm64 Android device.
# Collects the Node runtime and its shared libraries into ./runtime-export,
# renaming them to the names the APK's jniLibs must use.
set -eu
PREFIX=/data/data/com.termux/files/usr
OUT="${1:-$HOME/runtime-export}"
mkdir -p "$OUT"

cp -L "$PREFIX/bin/node" "$OUT/libnode.so"
cp -L "$PREFIX/bin/bash" "$OUT/libbash.so"
cp -L "$PREFIX/lib/libz.so.1" "$OUT/libz.so"
cp -L "$PREFIX/lib/libcares.so" "$OUT/libcares.so"
cp -L "$PREFIX/lib/libsqlite3.so" "$OUT/libsqlite3.so"
cp -L "$PREFIX/lib/libcrypto.so.3" "$OUT/libcrypto.so"
cp -L "$PREFIX/lib/libssl.so.3" "$OUT/libssl.so"
cp -L "$PREFIX/lib/libicui18n.so.79" "$OUT/libicui18n.so"
cp -L "$PREFIX/lib/libicuuc.so.79" "$OUT/libicuuc.so"
cp -L "$PREFIX/lib/libicudata.so.79" "$OUT/libicudata.so"
cp -L "$PREFIX/lib/libc++_shared.so" "$OUT/libc++_shared.so"
cp -L "$PREFIX/lib/libandroid-support.so" "$OUT/libandroid-support.so"
cp -L "$PREFIX/lib/libreadline.so.8" "$OUT/libreadline.so"
cp -L "$PREFIX/lib/libncursesw.so.6" "$OUT/libncursesw.so"
cp -L "$PREFIX/lib/libiconv.so" "$OUT/libiconv.so"

echo "runtime exported to $OUT"
echo "pull it with: adb pull $OUT ./runtime"
