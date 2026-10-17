# Neutrino Drive for Android — port notes and handoff

**Started:** 2026-10-15. **Status:** phase 1 feature-complete against the iOS Drive app's core;
run on an Android 14 emulator against a real local server. Never yet on a physical device or the
hosted server.

## 1. Decisions

| Decision | Why |
|---|---|
| Shared code lives in `neutrino_shared_android` (since 2026-10-16) | Extracted when Calendar for Android was built: auth, base API client, crypto, keys, Keystore storage, app lock, sign-in/key screens. Built from source by `includeBuild("../neutrino_shared_android")`. File references below to `crypto/`, `keys/` and the crosscheck now point there. See that repo's `agent_docs/shared-android.md`. |
| Kotlin + Jetpack Compose, `minSdk 26`, `targetSdk 36` | Native, current toolkit. 26 gives Keystore AES-GCM, adaptive icons, content-URI job triggers. |
| `:core` is plain Kotlin/JVM; `:app` is Android | Everything that must match other clients byte for byte (crypto, keyring, upload wire format, API) is tested on the JVM in seconds, with no emulator. |
| Pure-Kotlin libsodium on BouncyCastle | No JNI `.so` (no 16 KB page-alignment work, no ABI splits); runs in JVM tests. HSalsa20/HChaCha20/secretstream are written out in `Sodium.kt`/`SecretStream.kt`. Proven both ways — §3. |
| Streaming secretstream | Same one-message wire format as every client, but encrypt and decrypt run over streams (the MAC is held back and checked at the end), so a 2 GB video never sits in memory. Decrypted output is written to a `.part` file and renamed only after the MAC verifies. |
| OAuth client `neutrino-android` | Root CLAUDE.md rule 3. Registered by `neutrino/migrations/00141_oauth__2026-10-15-000000_add_android_drive_client` (branch `feature/android-drive-client` in `neutrino`). **Sign-in fails against any server without that migration.** |
| Keyring model, per account, Keystore-sealed | One file per item under `noBackupFilesDir/secrets`, AES-256-GCM with a non-exportable Keystore key; the web's serialised keyring JSON inside. No user-auth requirement on the key (like iOS `AfterFirstUnlock`) so photo backup works while locked. Android has no cross-app keychain, so each Neutrino Android app (Drive `nd`, Calendar `ncal`) holds its own copy. |
| Uploads and photo backup are WorkManager jobs | Android's equivalent of iOS's background `URLSession`/`BGTask`: they survive leaving the app, honour Wi-Fi/charging constraints, and run as a `dataSync` foreground service while transferring. |
| Shared/picked files are copied into a private outbox first | `content://` grants end with the activity. Copies live in `noBackupFilesDir/outbox` and are deleted once uploaded. Each file has a stable upload id so a retry finishes rather than duplicates. |
| Device name sent ASCII-only | The server reads `X-Device-Name` with actix `to_str()`, which rejects non-ASCII and records no name. See §5. |
| Search is local only | Walks the drive and indexes names on the phone (same tokenizer as web/iOS). Syncing the encrypted snapshot (`/api/v1/search/index`) is phase 2 — pushing a malformed one would clobber the web's index. |
| Native documents open in the web editor (WebView + token injection) | As iOS's `NeutrinoFileViewer`. There are no Android Docs/Sheets/Notes apps to hand off to. "Open with another app" still downloads the real `.docx`/`.xlsx`. |

## 2. Feature map (iOS → Android)

Sign-in with 2FA, registration, first-run key setup + recovery kit · key by pairing (receiver),
recovery kit, PIN key code, key file · retired keys pulled from the key file after every key
arrival and on foreground · stale-key refusal before sealing (`DeviceKeyCheck`) · My Drive /
Starred / Shared / Trash / Recents · upload (picker, share sheet) with cover thumbnails · open
(download + decrypt → system viewer), zip viewer, web viewer · offline files · rename, move, star,
trash, restore, delete forever, empty trash · sharing (DEK re-sealed to recipient, partial success
reported) and share links · version history (view, restore) · search · app lock (biometric/PIN,
`FLAG_SECURE`) · photo backup · storage quota · pending-key reconciliation · app links
(`/open/<kind>/<id>`) · report a bug.

**Not ported:** device-key *repair* (re-sealing files a stale key sealed — iOS
`DeviceKeyRepairService`; Android refuses to seal with a stale key, so it cannot create such
files, but it cannot repair ones another stale device made); search snapshot sync; photo date
repair (an iOS one-off); companion-app handoff.

## 3. What is verified (2026-10-15, macOS host)

- `./gradlew :core:test` — 44 tests: web-generated `place_envelope_vectors.json`, NaCl/XChaCha/RFC
  known answers, keyring/kit/pairing/PIN-QR/key-file units, and an end-to-end suite against an
  in-memory server holding real ciphertext (upload part order, sealed key in body vs. `PUT` for an
  old server, stale key refused, rotation + key-file recovery, kit renumbering, pairing wrong
  account, sharing order and partial success, interrupted upload finished not duplicated, 401
  refresh, rejected refresh signs out).
- `scripts/crosscheck-web-crypto.sh` — 15/15 both ways against the web's **own** `e2e-crypto` on
  real libsodium: sealed DEKs, files (incl. a streamed 200 KB one), metadata, keyring JSON,
  recovery kit (exports byte-identical), PIN key QR, pairing reply + confirmation code.
- `LiveServerTest` against a locally built server with the new migration: sign-in, mint and
  publish key, upload with import metadata (echoed), list, download, restore kit on a second
  device, share to a second account which decrypts.
- On the emulator (Pixel 7, API 34) against that server: sign in, set up key, upload via share
  sheet (thumbnail stored and shown), open → system viewer with **byte-identical** plaintext,
  offline copy byte-identical, search, photo backup (one folder, one upload, capture date kept —
  after fixing a concurrent-run duplicate bug), secrets on disk are ciphertext.

**Not verified:** a physical device; the hosted server; pairing/key-code scanning with a real
camera; the web viewer; sharing UI, version history UI, move/rename UI by hand (their core paths
are tested); app lock on a device with biometrics; release (R8) build at run time.

## 4. Next session checklist

1. Merge/deploy `neutrino` `feature/android-drive-client` (the migration) — nothing works against
   a server without it.
2. Physical device: sign in to an existing account, **pair with the web** (Settings › Encryption ›
   add a device), compare codes, open a web-uploaded file; upload from the phone and open it on
   the web.
3. Key code: show the web's phone key code, scan, enter PIN.
4. Photo backup on a real camera roll with Wi-Fi only; take a photo and watch it arrive.
5. `./gradlew :app:assembleRelease` with a signing config; run it (R8 + serialization).
6. Publish `deploy/assetlinks.json` with the release certificate's SHA-256 so app links verify.

## 5. Found while porting (not fixed here)

- **iOS device names are dropped by the server.** iOS sends `"<device> — Neutrino Drive"` (em
  dash) in `X-Device-Name`; the server's `to_str()` rejects non-ASCII, so iOS sessions list as
  "Unknown device". Fix either side: the server could read the raw bytes as UTF-8 lossily, or the
  shared iOS package could send ASCII. Android sends ASCII.

## 6. Building

JDK 17 (Gradle 8.14 does not run on JDK 25): `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
`./gradlew :core:test`, `./gradlew :app:assembleDebug`,
`../neutrino_shared_android/scripts/crosscheck-web-crypto.sh`.
Live test: `NEUTRINO_LIVE_URL=http://localhost:8080 NEUTRINO_LIVE_EMAIL=… NEUTRINO_LIVE_PASSWORD=…
NEUTRINO_LIVE_EMAIL2=… NEUTRINO_LIVE_PASSWORD2=… ./gradlew :core:test --tests '*LiveServerTest*'`
(two fresh accounts with no keys; use a throwaway database). From the emulator the host is
`http://10.0.2.2:8080`.

## 7. Since the shared extraction (2026-10-16)

The crypto, keys and auth tests moved with the code to `neutrino_shared_android` (41 tests +
crosscheck). This repo's `:core:test` now holds the Drive flows only (7 tests + `LiveServerTest`);
all pass, and `LiveServerTest` passed against a local server with two fresh accounts.
