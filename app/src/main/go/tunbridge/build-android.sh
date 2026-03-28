#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${MODULE_DIR}/../../../../.." && pwd)"
API_LEVEL="${API_LEVEL:-24}"
GO_BIN="${GO_BIN:-go}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-}"

if [[ -z "${ANDROID_NDK_HOME}" ]]; then
  LOCAL_PROPERTIES="${WORKSPACE_ROOT}/local.properties"
  if [[ -f "${LOCAL_PROPERTIES}" ]]; then
    SDK_DIR="$(grep '^sdk.dir=' "${LOCAL_PROPERTIES}" | sed 's/^sdk.dir=//' | sed 's#\\\\#/#g' | sed 's#^\([A-Za-z]\):#\1:#')"
    if [[ -d "${SDK_DIR}/ndk/25.2.9519653" ]]; then
      ANDROID_NDK_HOME="${SDK_DIR}/ndk/25.2.9519653"
    fi
  fi
fi

if [[ -z "${ANDROID_NDK_HOME}" ]]; then
  echo "ANDROID_NDK_HOME is not set and no local NDK was resolved" >&2
  exit 1
fi

HOST_TAG="linux-x86_64"
TOOLCHAIN_DIR="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_TAG}/bin"
OUT_ROOT="${MODULE_DIR}/build/android"

build_one() {
  local abi="$1"
  local goarch="$2"
  local clang="$3"
  local out_dir="${OUT_ROOT}/${abi}"

  mkdir -p "${out_dir}"

  export CGO_ENABLED=1
  export GOOS=android
  export GOARCH="${goarch}"
  export CC="${TOOLCHAIN_DIR}/${clang}"

  if [[ "${goarch}" == "amd64" ]]; then
    export GOAMD64=v1
  else
    unset GOAMD64 || true
  fi

  "${GO_BIN}" build \
    -trimpath \
    -buildmode=c-shared \
    -ldflags="-s -w" \
    -o "${out_dir}/libtunbridge.so" \
    .
}

pushd "${MODULE_DIR}" > /dev/null
build_one "arm64-v8a" "arm64" "aarch64-linux-android${API_LEVEL}-clang"
build_one "x86_64" "amd64" "x86_64-linux-android${API_LEVEL}-clang"
popd > /dev/null
