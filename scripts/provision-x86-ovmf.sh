#!/usr/bin/env bash
set -euo pipefail

OVMF_VERSION="2025.02-8+deb13u1"
OVMF_PACKAGE="ovmf_${OVMF_VERSION}_all.deb"
OVMF_URL="https://deb.debian.org/debian/pool/main/e/edk2/${OVMF_PACKAGE}"
OVMF_SHA256="78e0d54df11fc77406cb7a0bc9a39e5bca6d1cbe06556b91d9a73491c52decdf"

DESTINATION="${1:-}"
if [[ -z "$DESTINATION" ]]; then
  echo "Usage: $0 <runtime-pack-root>" >&2
  exit 2
fi

for tool in curl sha256sum dpkg-deb find sort head install cp; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "Required tool not found: $tool" >&2
    exit 1
  }
done

DESTINATION="$(mkdir -p "$DESTINATION" && cd "$DESTINATION" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

curl --fail --location --retry 3 --retry-all-errors "$OVMF_URL" -o "$WORK/$OVMF_PACKAGE"
printf '%s  %s\n' "$OVMF_SHA256" "$WORK/$OVMF_PACKAGE" | sha256sum --check --strict
dpkg-deb --extract "$WORK/$OVMF_PACKAGE" "$WORK/root"

CODE="$(find "$WORK/root/usr/share" -type f \( -name 'OVMF_CODE_4M.fd' -o -name 'OVMF_CODE.fd' \) | sort | head -n1)"
VARS="$(find "$WORK/root/usr/share" -type f \( -name 'OVMF_VARS_4M.fd' -o -name 'OVMF_VARS.fd' \) | sort | head -n1)"
COPYRIGHT="$(find "$WORK/root/usr/share/doc" -type f -name 'copyright' 2>/dev/null | head -n1 || true)"

[[ -n "$CODE" && -s "$CODE" ]] || { echo "OVMF CODE firmware is missing from the pinned package." >&2; exit 1; }
[[ -n "$VARS" && -s "$VARS" ]] || { echo "OVMF VARS firmware is missing from the pinned package." >&2; exit 1; }

OUT="$DESTINATION/usr/share/droidvm"
install -d "$OUT"
install -m 0644 "$CODE" "$OUT/edk2-x86_64-code.fd"
install -m 0644 "$VARS" "$OUT/edk2-x86_64-vars.fd"
if [[ -n "$COPYRIGHT" && -s "$COPYRIGHT" ]]; then
  install -m 0644 "$COPYRIGHT" "$OUT/ovmf-debian-copyright"
fi
printf '%s  %s\n' "$OVMF_SHA256" "$OVMF_PACKAGE" > "$OUT/ovmf-source.sha256"
printf 'Debian ovmf %s\nSource: %s\nPackage SHA-256: %s\n' \
  "$OVMF_VERSION" "$OVMF_URL" "$OVMF_SHA256" > "$OUT/ovmf-provenance.txt"

echo "Verified OVMF x86_64 firmware installed under: $OUT"
