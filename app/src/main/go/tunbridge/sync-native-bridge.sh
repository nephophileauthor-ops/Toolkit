#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${MODULE_DIR}/../../../../.." && pwd)"
SOURCE_ROOT="${MODULE_DIR}/build/android"
JNILIBS_ROOT="${WORKSPACE_ROOT}/app/src/main/jniLibs"
HEADER_TARGET="${WORKSPACE_ROOT}/app/src/main/cpp/include/tunbridge.h"

for abi in arm64-v8a x86_64; do
  mkdir -p "${JNILIBS_ROOT}/${abi}"
  cp "${SOURCE_ROOT}/${abi}/libtunbridge.so" "${JNILIBS_ROOT}/${abi}/libtunbridge.so"
done

cp "${SOURCE_ROOT}/arm64-v8a/libtunbridge.h" "${HEADER_TARGET}"

echo "Synced libtunbridge.so into app/src/main/jniLibs and updated cpp/include/tunbridge.h"
