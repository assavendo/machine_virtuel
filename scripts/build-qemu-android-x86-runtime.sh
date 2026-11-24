#!/usr/bin/env bash
set -euo pipefail

QEMU_VERSION="11.1.3"
QEMU_SHA256="5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22"
QEMU_TARGETS="x86_64-softmmu,i386-softmmu"

PROJECT_ROOT="$PWD"
ROOT="$PROJECT_ROOT/.build/qemu-$QEMU_VERSION"
SRC="$ROOT/src"
PREFIX="$ROOT/prefix"

mkdir -p "$ROOT"
cd "$ROOT"

if [[ ! -f "qemu-$QEMU_VERSION.tar.xz" ]]; then
  curl -fL "https://download.qemu.org/qemu-$QEMU_VERSION.tar.xz" -o "qemu-$QEMU_VERSION.tar.xz"
fi

echo "$QEMU_SHA256  qemu-$QEMU_VERSION.tar.xz" | sha256sum -c -

rm -rf "$SRC"
tar -xf "qemu-$QEMU_VERSION.tar.xz"
mv "qemu-$QEMU_VERSION" "$SRC"

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
  --enable-opengl \
  --enable-virglrenderer \
  --audio-drv-list=none \
  --disable-spice \
  --disable-libnfs

ninja -j"$(getconf _NPROCESSORS_ONLN)"
ninja install

mkdir -p "$PREFIX/runtime-pack/usr/bin"
cp "$PREFIX/bin/qemu-system-x86_64" "$PREFIX/runtime-pack/usr/bin/"
if [[ -f "$PREFIX/bin/qemu-system-i386" ]]; then
  cp "$PREFIX/bin/qemu-system-i386" "$PREFIX/runtime-pack/usr/bin/"
fi
cp "$PREFIX/bin/qemu-img" "$PREFIX/runtime-pack/usr/bin/"

"$PROJECT_ROOT/scripts/provision-x86-ovmf.sh" "$PREFIX/runtime-pack"

(
  cd "$PREFIX/runtime-pack"
  find . -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
  tar -cJf "$ROOT/phoenixvm-x86-runtime.tar.xz" .
  sha256sum "$ROOT/phoenixvm-x86-runtime.tar.xz" > "$ROOT/phoenixvm-x86-runtime.tar.xz.sha256"
)

echo "Runtime ready: $PREFIX/runtime-pack"
echo "Optional add-on archive: $ROOT/phoenixvm-x86-runtime.tar.xz"
