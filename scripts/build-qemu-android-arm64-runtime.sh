#!/usr/bin/env bash
set -euo pipefail

QEMU_VERSION="11.1.3"
QEMU_SHA256="5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22"
QEMU_TARGETS="aarch64-softmmu"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HOST_ARCH="$(uname -m)"
case "$HOST_ARCH" in
  aarch64|arm64) ;;
  *)
    echo "This builder must run natively on an Android ARM64/aarch64 host; detected: $HOST_ARCH" >&2
    exit 1
    ;;
esac

ROOT="$PROJECT_ROOT/.build/qemu-$QEMU_VERSION-arm64"
SRC="$ROOT/src"
PREFIX="$ROOT/prefix"
ARCHIVE="$ROOT/qemu-$QEMU_VERSION.tar.xz"
RUNTIME_PACK="$PREFIX/runtime-pack"

mkdir -p "$ROOT"
cd "$ROOT"

for tool in curl sha256sum tar ninja pkg-config getconf; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "Required build tool not found: $tool" >&2
    exit 1
  }
done

pkg-config --exists glib-2.0 pixman-1 || {
  echo "Install GLib and Pixman development packages for the Android/Termux build environment." >&2
  exit 1
}
pkg-config --exists slirp || {
  echo "Install libslirp development files (pkg-config module: slirp)." >&2
  exit 1
}

if [[ ! -f "$ARCHIVE" ]]; then
  curl -fL "https://download.qemu.org/qemu-$QEMU_VERSION.tar.xz" -o "$ARCHIVE"
fi

echo "$QEMU_SHA256  $ARCHIVE" | sha256sum -c -

rm -rf "$SRC" "$PREFIX"
tar -xf "$ARCHIVE"
mv "qemu-$QEMU_VERSION" "$SRC"

mkdir -p "$SRC/build"
cd "$SRC/build"

../configure \
  --prefix="$PREFIX" \
  --target-list="$QEMU_TARGETS" \
  --enable-tcg \
  --disable-guest-agent \
  --disable-werror \
  --disable-sdl \
  --disable-gtk \
  --disable-curses \
  --disable-vnc-sasl \
  --disable-seccomp \
  --disable-docs \
  --enable-vnc \
  --enable-virtfs \
  --enable-slirp \
  --disable-opengl \
  --disable-virglrenderer \
  --audio-drv-list=none \
  --disable-spice \
  --disable-libnfs

JOBS="${NINJA_JOBS:-$(getconf _NPROCESSORS_ONLN)}"
[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || {
  echo "NINJA_JOBS must be a positive integer." >&2
  exit 1
}
ninja -j"$JOBS"
ninja install

mkdir -p "$RUNTIME_PACK/usr/bin" "$RUNTIME_PACK/usr/lib" "$RUNTIME_PACK/usr/share/qemu"
install -m 0755 "$PREFIX/bin/qemu-system-aarch64" "$RUNTIME_PACK/usr/bin/"
install -m 0755 "$PREFIX/bin/qemu-img" "$RUNTIME_PACK/usr/bin/"

if [[ -d "$PREFIX/lib" ]]; then
  cp -a "$PREFIX/lib/." "$RUNTIME_PACK/usr/lib/"
fi
if [[ -d "$PREFIX/share/qemu" ]]; then
  cp -a "$PREFIX/share/qemu/." "$RUNTIME_PACK/usr/share/qemu/"
fi

mkdir -p "$RUNTIME_PACK/usr/share/licenses/qemu"
for license in COPYING COPYING.LIB LICENSE; do
  if [[ -f "$SRC/$license" ]]; then
    cp "$SRC/$license" "$RUNTIME_PACK/usr/share/licenses/qemu/"
  fi
done

(
  cd "$RUNTIME_PACK"
  find usr -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
  tar -cJf "$ROOT/phoenixvm-arm64-runtime.tar.xz" SHA256SUMS usr
  sha256sum "$ROOT/phoenixvm-arm64-runtime.tar.xz" > "$ROOT/phoenixvm-arm64-runtime.tar.xz.sha256"
)

echo "Runtime ready: $RUNTIME_PACK"
echo "Optional ARM64 runtime archive: $ROOT/phoenixvm-arm64-runtime.tar.xz"
echo "Keep the separately verified ARM64 UEFI firmware from the pinned runtime bundle."
