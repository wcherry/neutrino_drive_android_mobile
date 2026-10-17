# Neutrino Drive for Android

End-to-end encrypted file storage — the Android port of `neutrino_drive_ios_mobile`. Files are
encrypted on the phone before upload with the same scheme as the web, iOS, macOS and Windows
clients, and open on all of them.

- `core/` — plain Kotlin/JVM: the Drive API client and the upload/download protocol. Tested on the
  JVM. Crypto, keyring, recovery kit, pairing, sign-in and the base API client come from
  `../neutrino_shared_android`, which must be a sibling of this repo.
- `app/` — the Android app (Jetpack Compose).

## Build

Needs JDK 17 and the Android SDK (API 36).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :core:test                 # Drive protocol end-to-end against a fake server
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
../neutrino_shared_android/scripts/crosscheck-web-crypto.sh   # crypto vs. the web (needs Node)
```

The server must carry `neutrino/migrations/00141_oauth__*`, which registers this app's OAuth
client `neutrino-android`. From the emulator, a server on your computer is `http://10.0.2.2:8080`.

See `agent_docs/android-port.md` for decisions, what is verified, and what is left.
