#!/usr/bin/env bash
set -euo pipefail

VIRTIOFSD_VERSION="1.15.0"
VIRTIOFSD_COMMIT="697f63ce6b43c5c6e19c87a9d5a4dd1a9c3f3d2f"
PREFIX="${ANDROID_DEPS_PREFIX:-}"
ROOT="${PWD}/.build/virtiofsd-${VIRTIOFSD_VERSION}"
SRC="${ROOT}/src"
OUT="${ROOT}/prefix"
STAGE="${ROOT}/runtime-pack"
REPO="https://gitlab.com/virtio-fs/virtiofsd.git"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ANDROID_NDK_HOME must point to an Android NDK." >&2
  exit 1
fi
for tool in git cargo cargo-ndk pkg-config tar sha256sum find; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Required tool missing: $tool" >&2; exit 1; }
done
if [[ -z "$PREFIX" || ! -d "$PREFIX/lib/pkgconfig" ]]; then
  echo "ANDROID_DEPS_PREFIX must contain the Android-arm64 dependency sysroot (libcap-ng/libseccomp)." >&2
  exit 1
fi

mkdir -p "$ROOT"
if [[ ! -d "$SRC/.git" ]]; then
  git clone "$REPO" "$SRC"
fi
cd "$SRC"
git fetch --tags --force
git checkout --detach "$VIRTIOFSD_COMMIT"

export PKG_CONFIG_ALLOW_CROSS=1
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"
export PKG_CONFIG_PATH="$PKG_CONFIG_LIBDIR"
export CFLAGS="--sysroot=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot ${CFLAGS:-}"
export LDFLAGS="-L$PREFIX/lib ${LDFLAGS:-}"
export RUSTFLAGS="-C link-arg=-L$PREFIX/lib ${RUSTFLAGS:-}"

if ! pkg-config --exists libcap-ng; then
  echo "libcap-ng.pc was not found in $ANDROID_DEPS_PREFIX." >&2
  exit 1
fi
if ! pkg-config --exists libseccomp; then
  echo "libseccomp.pc was not found in $ANDROID_DEPS_PREFIX." >&2
  exit 1
fi

rm -rf "$ROOT/cargo-target"
mkdir -p "$ROOT/cargo-target"
CARGO_TARGET_DIR="$ROOT/cargo-target" cargo ndk -t arm64-v8a build --release --locked

mkdir -p "$STAGE/usr/libexec" "$STAGE/usr/lib" "$STAGE/usr/share/licenses/virtiofsd" "$STAGE/usr/share/licenses/libcap-ng" "$STAGE/usr/share/licenses/libseccomp"
BINARY="$(find "$ROOT/cargo-target" -type f -path '*/release/virtiofsd' -perm -111 | head -n 1)"
[[ -n "$BINARY" ]] || { echo "virtiofsd binary was not produced." >&2; exit 1; }
cp "$BINARY" "$STAGE/usr/libexec/virtiofsd"

find "$PREFIX/lib" -maxdepth 1 -type f -name 'libcap-ng.so*' -exec cp -a {} "$STAGE/usr/lib/" \; || true
find "$PREFIX/lib" -maxdepth 1 -type f -name 'libseccomp.so*' -exec cp -a {} "$STAGE/usr/lib/" \; || true
for license in LICENSE LICENSE.BSD LICENSE-APACHE; do
  [[ -f "$SRC/$license" ]] && cp "$SRC/$license" "$STAGE/usr/share/licenses/virtiofsd/"
done
for depdir in libcap-ng libseccomp; do
  [[ -f "$PREFIX/share/doc/$depdir/COPYING" ]] && cp "$PREFIX/share/doc/$depdir/COPYING" "$STAGE/usr/share/licenses/$depdir/"
done

(
  cd "$STAGE"
  find usr -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
  tar -cJf "$ROOT/phoenixvm-virtiofsd-android-arm64.tar.xz" SHA256SUMS usr
  sha256sum "$ROOT/phoenixvm-virtiofsd-android-arm64.tar.xz" > "$ROOT/phoenixvm-virtiofsd-android-arm64.tar.xz.sha256"
)

echo "virtiofsd add-on ready: $ROOT/phoenixvm-virtiofsd-android-arm64.tar.xz"
