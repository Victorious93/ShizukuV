# ShizukuV debug keystore (public, debug-only)

`shizukuv-debug.keystore` is a **deliberately public** key that signs **debug** builds only, so every machine and every
CI run produces a debug APK with the same signature and `adb install -r` upgrades work across them.

| Field | Value |
|---|---|
| Store type | PKCS12 |
| Store password | `android` |
| Key alias | `androiddebugkey` |
| Key password | `android` |
| SHA-256 certificate fingerprint | `01:52:AD:3E:B0:7A:FA:56:3F:8E:39:9E:62:09:3E:6A:D9:47:3B:E4:F9:8C:4C:DA:51:C9:BE:32:CC:AA:3C:FB` |

The credentials are the well-known Android debug credentials. Anyone can sign an APK with this key, so:

* **It is not a release key** and must never be used to publish, distribute to users, or protect anything.
* A debug APK signed with it proves nothing about who built it.
* Release builds never fall back to it: `:manager:assembleRelease` is **unsigned** unless a git-ignored
  `signing.properties` supplies a real key (see `docs/TOOLCHAIN.md`).
* No secret is needed or read for debug builds, so forks and pull requests can build them.

Regenerate (this changes the debug signature, so existing debug installs must be uninstalled once):

```sh
keytool -genkeypair -keystore keystore/shizukuv-debug.keystore -storetype PKCS12 -storepass android \
  -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 36500 \
  -dname "CN=ShizukuV Debug Only,O=Victorious93,C=US"
```
