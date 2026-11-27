#!/usr/bin/env bash
set -euo pipefail

QEMU_VERSION="11.1.3"
QEMU_SHA256="5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22"
QEMU_TARGETS="x86_64-softmmu,i386-softmmu"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT="$PROJECT_ROOT/.build/qemu-$QEMU_VERSION"
SRC="$ROOT/src"
PREFIX="$ROOT/prefix"

mkdir -p "$ROOT"
cd "$ROOT"

for tool in curl sha256sum tar ninja pkg-config dpkg-deb; do
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

bash "$SCRIPT_DIR/build-phoenixvm-native-cache-android-arm64.sh"

if [[ ! -f "qemu-$QEMU_VERSION.tar.xz" ]]; then
  curl -fL "https://download.qemu.org/qemu-$QEMU_VERSION.tar.xz" -o "qemu-$QEMU_VERSION.tar.xz"
fi

echo "$QEMU_SHA256  qemu-$QEMU_VERSION.tar.xz" | sha256sum -c -

rm -rf "$SRC"
tar -xf "qemu-$QEMU_VERSION.tar.xz"
mv "qemu-$QEMU_VERSION" "$SRC"

bash "$SCRIPT_DIR/../patches/qemu/11.1.3/apply.sh" "$SRC"

cd "$SRC"
mkdir -p build
cd build

../configure \
  --prefix="$PREFIX" \
  --target-list="$QEMU_TARGETS" \
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

ninja -j"$(getconf _NPROCESSORS_ONLN)"
ninja install

RUNTIME_PACK="$PREFIX/runtime-pack"
mkdir -p "$RUNTIME_PACK/usr/bin" "$RUNTIME_PACK/usr/lib" "$RUNTIME_PACK/usr/share/qemu"

cp "$PREFIX/bin/qemu-system-x86_64" "$RUNTIME_PACK/usr/bin/"
if [[ -f "$PREFIX/bin/qemu-system-i386" ]]; then
  cp "$PREFIX/bin/qemu-system-i386" "$RUNTIME_PACK/usr/bin/"
fi
cp "$PREFIX/bin/qemu-img" "$RUNTIME_PACK/usr/bin/"
mkdir -p "$RUNTIME_PACK/usr/share/licenses/qemu"
for license in COPYING COPYING.LIB LICENSE; do
  if [[ -f "$SRC/$license" ]]; then cp "$SRC/$license" "$RUNTIME_PACK/usr/share/licenses/qemu/"; fi
done
if [[ -d "$PREFIX/lib" ]]; then cp -a "$PREFIX/lib/." "$RUNTIME_PACK/usr/lib/"; fi
if [[ -d "$PREFIX/share/qemu" ]]; then cp -a "$PREFIX/share/qemu/." "$RUNTIME_PACK/usr/share/qemu/"; fi

CACHE_LIBRARY="$PROJECT_ROOT/native/phoenixvm-core/target/aarch64-linux-android/release/libphoenixvm_core.so"
test -s "$CACHE_LIBRARY"
install -m 0755 "$CACHE_LIBRARY" "$RUNTIME_PACK/usr/lib/libphoenixvm_core.so"
printf 'format=1\\nqemu=%s\\nabi=%s\\n' "$QEMU_VERSION" "$QEMU_TARGETS" \
  > "$RUNTIME_PACK/usr/share/qemu/phoenixvm-tcg-cache-v1"

"$PROJECT_ROOT/scripts/provision-x86-ovmf.sh" "$RUNTIME_PACK"

(
  cd "$RUNTIME_PACK"
  find usr -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
  tar -cJf "$ROOT/phoenixvm-x86-runtime.tar.xz" SHA256SUMS usr
  sha256sum "$ROOT/phoenixvm-x86-runtime.tar.xz" > "$ROOT/phoenixvm-x86-runtime.tar.xz.sha256"
)

echo "Runtime ready: $RUNTIME_PACK"
echo "Optional add-on archive: $ROOT/phoenixvm-x86-runtime.tar.xz"
