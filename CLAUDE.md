# CLAUDE.md — Neutrino Drive for Android

Part of the Neutrino platform — read the root `../CLAUDE.md` coupling rules first. This repo is the
**fifth E2EE implementation** (`core/src/main/kotlin/com/neutrino/drive/core/crypto/`); any change to
the wire formats must agree with the web, iOS, macOS and Windows clients, and
`scripts/crosscheck-web-crypto.sh` must pass. Its OAuth client id is `neutrino-android`
(`neutrino/migrations/00141_oauth__*`).

- Build with JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- Put protocol logic in `:core` (JVM-tested), not `:app`.
- Status, decisions and the next-session checklist: `agent_docs/android-port.md`.
