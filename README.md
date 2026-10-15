# Neutrino Drive for Android

End-to-end encrypted file storage — the Android port of `neutrino_drive_ios_mobile`. Files are
encrypted on the phone before upload with the same scheme as the web, iOS, macOS and Windows
clients, and open on all of them.

- `core/` — plain Kotlin/JVM: libsodium-compatible crypto, keyring, recovery kit, pairing, the
  Neutrino API client and the upload/download protocol. Tested on the JVM.
- `app/` — the Android app (Jetpack Compose).

## Build

Needs JDK 17 and the Android SDK (API 36).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :core:test                 # crypto, protocol, end-to-end against a fake server
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./scripts/crosscheck-web-crypto.sh   # both ways against the web's own e2e-crypto (needs Node)
```

The server must carry `neutrino/migrations/00141_oauth__*`, which registers this app's OAuth
client `neutrino-android`. From the emulator, a server on your computer is `http://10.0.2.2:8080`.

See `agent_docs/android-port.md` for decisions, what is verified, and what is left.
