## Native TUN Bridge Plan

This app now supports three capture paths at the control-plane level:

1. `OBSERVE_ONLY`
   Reads raw packets from the VPN TUN file descriptor in Kotlin and classifies them.
   Use this for packet intel, protocol discovery, and debugging the routing pipeline.

2. `HTTP_PROXY`
   Uses `VpnService.Builder.setHttpProxy(...)` on Android 10+ so proxy-aware apps and
   libraries talk to the local proxy directly.
   This is the closest "ProxyPin/ReQable style" fallback when full transparent transport
   reconstruction is not ready yet.

3. `NATIVE_TUN2SOCKS`
   Hands the TUN file descriptor to a native bridge that reconstructs TCP sessions and
   forwards them to the local proxy at `127.0.0.1:8080`.
   This is the path required for transparent app-wide interception when apps do not honor
   the platform proxy.

## Bridge Layers

`VpnService` -> TUN fd -> JNI shim (`tun2socks_bridge.cpp`) -> Go bridge (`main.go`) ->
native netstack/tun2socks engine -> local proxy (`LocalDebugProxyServer`) -> `RequestEngine`
-> rule engine / mocking / persistence

## App Layout

Use this exact layout for the Android app side:

```text
app/
  src/
    main/
      cpp/
        CMakeLists.txt
        tun2socks_bridge.cpp
        tunbridge_stub.cpp
        include/
          tunbridge.h
      go/
        tunbridge/
          build/
            android/
              arm64-v8a/
                libtunbridge.so
                libtunbridge.h
              x86_64/
                libtunbridge.so
                libtunbridge.h
      jniLibs/
        arm64-v8a/
          libtunbridge.so
        x86_64/
          libtunbridge.so
```

Placement rules:

- Copy the ABI-specific Go output `.so` files into:
  - `app/src/main/jniLibs/arm64-v8a/libtunbridge.so`
  - `app/src/main/jniLibs/x86_64/libtunbridge.so`
- Copy one generated Go header into:
  - `app/src/main/cpp/include/tunbridge.h`
- The generated Go header is ABI-agnostic for this bridge surface, so a single checked-in
  `tunbridge.h` file under `cpp/include` is enough for the JNI wrapper build.
- Keep the original Go build artifacts under `app/src/main/go/tunbridge/build/android/...`
  so you can re-copy fresh binaries after every Go rebuild.
- Use the local sync helper after a Go build:
  - `app/src/main/go/tunbridge/sync-native-bridge.ps1`

## Runtime Loading

The Android side loads native libraries in this order:

1. `tunbridge`
2. `tun2socks_bridge`

That order prevents `UnsatisfiedLinkError` when the JNI wrapper is linked against the real
Go shared library. The loader is intentionally tolerant of the current stub-fallback build:
if `tunbridge` is absent, it still attempts to load `tun2socks_bridge` so debug builds remain
green until the real ABI binaries are dropped into `jniLibs`.

The UI now distinguishes three native states:

- `Missing`: neither real backend nor stub wrapper is available.
- `Stub fallback active`: JNI wrapper is present but real `libtunbridge.so` is not.
- `Real Go backend loaded`: transparent native routing is ready to start.

### JNI responsibilities

- Receive the detached TUN file descriptor from Kotlin.
- Pass runtime config into the Go shared library.
- Register a protect-socket callback so upstream sockets do not re-enter the VPN.
- Own start/stop lifecycle from the Android side.

### Go responsibilities

- Wrap the TUN file descriptor as a file-backed device.
- Plug that device into either:
  - `xjasonlyu/tun2socks` if a SOCKS-oriented path is preferred, or
  - `gVisor/netstack` if deeper TCP dispatcher control is needed.
- Replace direct upstream dialing with a proxy-aware dialer that points to:
  - `127.0.0.1:8080` for HTTP/CONNECT interception, or
  - `127.0.0.1:<socks-port>` if a dedicated SOCKS server is added later.
- Invoke the Android protect callback for every outbound socket before connect.

## Practical Recommendation

Build the transport in phases:

1. Stabilize `HTTP_PROXY` mode for proxy-aware apps.
2. Add `root` redirect for labs where owner-level iptables is acceptable.
3. Finish `NATIVE_TUN2SOCKS` for transparent TCP capture.

That gives a useful reverse-engineering tool before the full native stream bridge lands,
while keeping the final architecture aligned with HTTP Toolkit / HttpCanary-class capture.

## Low-Data Cloud Build

This repo now includes a GitHub Actions workflow at:

- `.github/workflows/build-native-bridge.yml`

It builds the Go bridge on GitHub's runners, not on the local machine.

### Server-side flow

1. Checkout the repo.
2. Install Go.
3. Install Android SDK tooling and `ndk;25.2.9519653`.
4. Run `app/src/main/go/tunbridge/build-android.sh`.
5. Upload one artifact bundle containing:
   - `jniLibs/arm64-v8a/libtunbridge.so`
   - `jniLibs/x86_64/libtunbridge.so`
   - `cpp/include/tunbridge.h`
   - `apk/debug/app-debug.apk`

### Local flow after download

1. Download the `native-bridge-bundle` artifact ZIP from GitHub Actions.
2. Extract it.
3. Copy:
   - `jniLibs/arm64-v8a/libtunbridge.so` -> `app/src/main/jniLibs/arm64-v8a/libtunbridge.so`
   - `jniLibs/x86_64/libtunbridge.so` -> `app/src/main/jniLibs/x86_64/libtunbridge.so`
   - `cpp/include/tunbridge.h` -> `app/src/main/cpp/include/tunbridge.h`
4. If you only want to test the server-built app, install `apk/debug/app-debug.apk`.
5. If you want the local workspace to include the real bridge for future builds, keep the copied
   files in place and rebuild locally.

Tracked empty ABI directories already exist in git:

- `app/src/main/jniLibs/arm64-v8a/.gitkeep`
- `app/src/main/jniLibs/x86_64/.gitkeep`
