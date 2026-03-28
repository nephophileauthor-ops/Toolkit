# API Debug Inspector

Android network debugging and API QA workspace with:

- custom send client
- search/filter traffic inspection
- rewrite/mock/block rules
- HAR import/export
- local HTTP proxy
- developer CA generation/export
- VPN capture modes
- root redirect foundation
- native tun2socks bridge wiring

## Cloud Build

The repo includes a GitHub Actions workflow that builds:

- `libtunbridge.so` for `arm64-v8a`
- `libtunbridge.so` for `x86_64`
- `tunbridge.h`
- `app-debug.apk`

Workflow:

- `.github/workflows/build-native-bridge.yml`

See:

- `docs/native-tun-bridge.md`
- `app/src/main/go/tunbridge/README.md`
