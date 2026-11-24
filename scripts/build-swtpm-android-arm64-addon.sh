#!/usr/bin/env bash
set -euo pipefail

SWTPM_VERSION="0.10.2"
SWTPM_COMMIT="32b5a0575991a73398de9749d63cafd847008af2"
LIBTPMS_VERSION="0.10.2"
LIBTPMS_COMMIT="03ff2481e133540be3b3ffe3daa1483d2a73d967"
SWTPM_REPO="https://github.com/stefanberger/swtpm.git"
LIBTPMS_REPO="https://github.com/stefanberger/libtpms.git"
PREFIX="${ANDROID_DEPS_PREFIX:-}"
ROOT="${PWD}/.build/swtpm-${SWTPM_VERSION}"
LIBTPMS_SRC="${ROOT}/libtpms-src"
SWTPM_SRC="${ROOT}/swtpm-src"
SYSROOT="${ROOT}/sysroot"
STAGE="${ROOT}/runtime-pack"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ANDROID_NDK_HOME must point to an Android NDK." >&2
  exit 1
fi
for tool in git autoconf automake libtoolize pkg-config make tar sha256sum find; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Required tool missing: $tool" >&2; exit 1; }
done
if [[ -z "$PREFIX" || ! -d "$PREFIX/lib/pkgconfig" ]]; then
  echo "ANDROID_DEPS_PREFIX must contain an Android-arm64 dependency sysroot." >&2
  exit 1
fi

mkdir -p "$ROOT" "$SYSROOT/lib" "$SYSROOT/include"

if [[ ! -d "$LIBTPMS_SRC/.git" ]]; then git clone "$LIBTPMS_REPO" "$LIBTPMS_SRC"; fi
if [[ ! -d "$SWTPM_SRC/.git" ]]; then git clone "$SWTPM_REPO" "$SWTPM_SRC"; fi
git -C "$LIBTPMS_SRC" fetch --tags --force
git -C "$SWTPM_SRC" fetch --tags --force
git -C "$LIBTPMS_SRC" checkout --detach "$LIBTPMS_COMMIT"
git -C "$SWTPM_SRC" checkout --detach "$SWTPM_COMMIT"

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC="$TOOLCHAIN/aarch64-linux-android29-clang"
export CXX="$TOOLCHAIN/aarch64-linux-android29-clang++"
export AR="$TOOLCHAIN/llvm-ar"
export RANLIB="$TOOLCHAIN/llvm-ranlib"
export STRIP="$TOOLCHAIN/llvm-strip"
export CPPFLAGS="--sysroot=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot -I$PREFIX/include ${CPPFLAGS:-}"
export CFLAGS="-O2 -fPIC ${CFLAGS:-}"
export LDFLAGS="-L$PREFIX/lib ${LDFLAGS:-}"
export PKG_CONFIG_ALLOW_CROSS=1
export PKG_CONFIG_LIBDIR="$SYSROOT/lib/pkgconfig:$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"
export PKG_CONFIG_PATH="$PKG_CONFIG_LIBDIR"

if ! pkg-config --exists openssl; then
  echo "Android OpenSSL development files are required in ANDROID_DEPS_PREFIX." >&2
  exit 1
fi

cd "$LIBTPMS_SRC"
./autogen.sh --with-openssl --with-tpm2 --disable-static --prefix=/usr --libdir=/usr/lib
make -j"$(getconf _NPROCESSORS_ONLN)"
make DESTDIR="$SYSROOT" install

cat > "$SYSROOT/lib/pkgconfig/libtpms.pc" <<EOF
prefix=/usr
exec_prefix=\${prefix}
libdir=\${prefix}/lib
includedir=\${prefix}/include
Name: libtpms
Description: libtpms for PhoenixVM Android ARM64
Version: ${LIBTPMS_VERSION}
Libs: -L\${libdir} -ltpms
Cflags: -I\${includedir}
EOF

cd "$SWTPM_SRC"
./autogen.sh --with-openssl --without-seccomp --without-cuse --without-vtpm-proxy --disable-tests --prefix=/usr
make -j"$(getconf _NPROCESSORS_ONLN)"
make DESTDIR="$SYSROOT" install

rm -rf "$STAGE"
mkdir -p "$STAGE/usr/bin" "$STAGE/usr/lib" "$STAGE/usr/share/licenses/swtpm" "$STAGE/usr/share/licenses/libtpms"
cp "$SYSROOT/usr/bin/swtpm" "$STAGE/usr/bin/"
find "$SYSROOT/usr/lib" -maxdepth 1 -type f -name 'libswtpm_libtpms.so*' -exec cp -a {} "$STAGE/usr/lib/" \;
find "$SYSROOT/usr/lib" -maxdepth 1 -type f -name 'libtpms.so*' -exec cp -a {} "$STAGE/usr/lib/" \;
for lib in glib json-glib gnutls tasn1; do
  find "$PREFIX/lib" -maxdepth 1 -type f -name "lib$lib.so*" -exec cp -a {} "$STAGE/usr/lib/" \; || true
done
cp "$SWTPM_SRC/LICENSE" "$STAGE/usr/share/licenses/swtpm/" 2>/dev/null || true
cp "$LIBTPMS_SRC/LICENSE" "$STAGE/usr/share/licenses/libtpms/" 2>/dev/null || true

(
  cd "$STAGE"
  find usr -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
  tar -cJf "$ROOT/phoenixvm-swtpm-android-arm64.tar.xz" SHA256SUMS usr
  sha256sum "$ROOT/phoenixvm-swtpm-android-arm64.tar.xz" > "$ROOT/phoenixvm-swtpm-android-arm64.tar.xz.sha256"
)

echo "swtpm add-on ready: $ROOT/phoenixvm-swtpm-android-arm64.tar.xz"
