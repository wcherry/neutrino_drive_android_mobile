# CLAUDE.md — Neutrino Drive for Android

Part of the Neutrino platform — read the root `../CLAUDE.md` coupling rules first. Account, OAuth, keyring and
the **fifth E2EE implementation** live in `../neutrino_shared_android` (consumed by local path via
`includeBuild`, shared with Calendar for Android); any change to the wire formats must agree with
the web, iOS, macOS and Windows clients, and that repo's `scripts/crosscheck-web-crypto.sh` must
pass. Its OAuth client id is `neutrino-android`
(`neutrino/migrations/00141_oauth__*`).

- Build with JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- Put Drive protocol logic in `:core` (JVM-tested), not `:app`; anything another Neutrino Android
  app would need belongs in `neutrino_shared_android`.
- Status, decisions and the next-session checklist: `agent_docs/android-port.md`.
