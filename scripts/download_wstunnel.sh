#!/usr/bin/env bash
# ============================================================================
# download_wstunnel.sh — fetch the wstunnel binaries for Android
# ============================================================================
#
# Usage:
#   ./scripts/download_wstunnel.sh [VERSION]
#
# Default version: 10.5.1
#
# The app embeds two wstunnel binaries in app/src/main/assets/:
#   - wstunnel_arm64  (64-bit ARM, arm64-v8a)
#   - wstunnel_armv7  (32-bit ARM, armeabi-v7a)
#
# The official GitHub Release only ships an Android asset for arm64, so:
#   - wstunnel_arm64 is downloaded here from the official release (static
#     linux-arm64 binary, runs on Android without shared libraries).
#   - wstunnel_armv7 has NO official Android asset — it is compiled from
#     source in CI (see .github/workflows/build-wstunnel.yml) and copied
#     into assets/ by .github/workflows/build.yml before the APK build.
#
# To fetch the CI-compiled binaries locally instead, download the
# `wstunnel-android-binaries` artifact from a GitHub Actions run and copy
# both files into app/src/main/assets/.
# ============================================================================

set -euo pipefail

VERSION="${1:-10.5.1}"
ASSET="wstunnel-linux-arm64"
OUTPUT_DIR="app/src/main/assets"
OUTPUT_FILE="${OUTPUT_DIR}/wstunnel_arm64"
DOWNLOAD_URL="https://github.com/erebe/wstunnel/releases/download/v${VERSION}/${ASSET}"

echo "==> Downloading wstunnel v${VERSION} for linux-arm64"
echo "    URL: ${DOWNLOAD_URL}"

# Ensure output directory exists
mkdir -p "${OUTPUT_DIR}"

# Download with curl (preferred) or wget fallback
if command -v curl &>/dev/null; then
    curl -fL --progress-bar -o "${OUTPUT_FILE}" "${DOWNLOAD_URL}"
elif command -v wget &>/dev/null; then
    wget -q --show-progress -O "${OUTPUT_FILE}" "${DOWNLOAD_URL}"
else
    echo "ERROR: Neither curl nor wget found. Install one of them and retry." >&2
    exit 1
fi

# Verify the download produced a real binary
if file "${OUTPUT_FILE}" | grep -q "ELF"; then
    chmod +x "${OUTPUT_FILE}"
    SIZE=$(stat -c%s "${OUTPUT_FILE}" 2>/dev/null || stat -f%z "${OUTPUT_FILE}" 2>/dev/null)
    echo "==> Done. Binary: ${OUTPUT_FILE} ($(numfmt --to=iec ${SIZE} 2>/dev/null || echo ${SIZE} bytes))"
else
    echo "ERROR: Downloaded file does not appear to be an ELF binary." >&2
    echo "       Check the URL and version number." >&2
    rm -f "${OUTPUT_FILE}"
    exit 1
fi
