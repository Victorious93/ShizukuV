# Toolchain and reproducible build

Verified in the session that produced this change (Linux x86_64). Versions come from the repository, not from memory.

| Item | Version | Source |
|---|---|---|
| JDK | 21 (Temurin in CI; OpenJDK 21 locally) | `build.gradle` compile options |
| Gradle | wrapper | `gradle/wrapper` |
| Android Gradle Plugin | 8.10.1 | `settings.gradle` |
| Kotlin | 2.1.21 | `settings.gradle` |
| compileSdk / targetSdk / minSdk | 36 / 36 / 24 | `build.gradle`. Raising the number is not compatibility work; behaviour changes are verified separately |
| Build-tools | 36.0.0 | `build.gradle` |
| NDK | 29.0.13113456 | `build.gradle` |
| CMake | >= 3.31.0 (3.31.6 used) | `manager/build.gradle`, `manager/src/main/jni/CMakeLists.txt` |
| `api/` sources | Shizuku-API `a27f6e4151ba7b39965ca47edb2bf0aeed7102e5`, vendored | `api/UPSTREAM.md` |

Install with `sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;29.0.13113456" "cmake;3.31.6"`.

## Clean clone

`git clone` then `./gradlew :manager:assembleDebug`. The previous `api` submodule used an SSH-only URL
(`git@github.com:...`), so a clone without GitHub SSH keys failed; the sources are now vendored at the pinned commit.
To move to a newer upstream API, diff against that commit and re-apply the changes marked `Victorious:`.

## Artifacts

`:manager:assembleDebug` is secret-free and signed with the committed, public, debug-only key `keystore/shizukuv-debug.keystore` (see `keystore/README.md`), so debug APKs carry the same signature on every machine and in CI. `:manager:assembleRelease` produces
`...-release-unsigned.apk` unless `signing.properties` (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEYSTORE_ALIAS`,
`KEYSTORE_ALIAS_PASSWORD`; git-ignored) provides the project's key; there is no fallback to the debug key.
`scripts/release-artifacts.sh` writes `out/artifacts/SHA256SUMS` and `BUILD-INFO.txt` (commit, version, signing label per APK)
and fails if a file named `-release.apk` is not signed with a non-debug key.

## Local notes

If Maven Central answers HTTP 429 (shared egress), a mirror can be added through a *user-level* Gradle init script; none is
committed to this repository.
