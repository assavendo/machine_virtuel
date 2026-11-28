#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MANIFEST="$PROJECT_ROOT/native/phoenixvm-core/Cargo.toml"
TARGET="aarch64-linux-android"
API_LEVEL="${ANDROID_API_LEVEL:-26}"
BUILD_DIR="$PROJECT_ROOT/native/phoenixvm-core/target/$TARGET/release"
WRAPPER_DIR="$PROJECT_ROOT/.build/android-rust-linker"
WRAPPER="$WRAPPER_DIR/$TARGET-clang"

[[ "$API_LEVEL" =~ ^[0-9]+$ ]] || {
  echo "ANDROID_API_LEVEL must be a numeric Android API level." >&2
  exit 1
}

for tool in cargo rustup clang; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "Required Android Rust build tool not found: $tool" >&2
    exit 1
  }
done

mkdir -p "$WRAPPER_DIR"

NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -n "$NDK_ROOT" ]]; then
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) HOST_TAG="linux-x86_64" ;;
    Linux-aarch64|Linux-arm64) HOST_TAG="linux-aarch64" ;;
    Darwin-x86_64) HOST_TAG="darwin-x86_64" ;;
    Darwin-arm64) HOST_TAG="darwin-arm64" ;;
    *)
      echo "Unsupported NDK host: $(uname -s)-$(uname -m)" >&2
      exit 1
      ;;
  esac
  TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin"
  LINKER="$TOOLCHAIN/aarch64-linux-android$API_LEVEL-clang"
  AR="$TOOLCHAIN/llvm-ar"
  RANLIB="$TOOLCHAIN/llvm-ranlib"
  [[ -x "$LINKER" ]] || {
    echo "Android NDK linker not found: $LINKER" >&2
    exit 1
  }
else
  CLANG="$(command -v clang)"
  cat > "$WRAPPER" <<EOF
#!/usr/bin/env sh
exec "$CLANG" --target=aarch64-linux-android$API_LEVEL -fuse-ld=lld "\$@"
EOF
  chmod 0755 "$WRAPPER"
  LINKER="$WRAPPER"
  AR="$(command -v llvm-ar || command -v ar)"
  RANLIB="$(command -v llvm-ranlib || command -v ranlib)"
fi

echo "Installing Rust target $TARGET"
rustup target add "$TARGET"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LINKER"
export CC_aarch64_linux_android="$LINKER"
export AR_aarch64_linux_android="$AR"
export RANLIB_aarch64_linux_android="$RANLIB"
export CARGO_INCREMENTAL=0

cargo build --manifest-path "$MANIFEST" --release --target "$TARGET"

LIBRARY="$BUILD_DIR/libphoenixvm_core.so"
[[ -s "$LIBRARY" ]] || {
  echo "Android native cache library was not produced: $LIBRARY" >&2
  exit 1
}

if command -v llvm-readelf >/dev/null 2>&1; then
  llvm-readelf -h "$LIBRARY" | grep -q 'AArch64'
elif command -v readelf >/dev/null 2>&1; then
  readelf -h "$LIBRARY" | grep -q 'AArch64'
fi

echo "Android ARM64 cache bridge ready: $LIBRARY"
