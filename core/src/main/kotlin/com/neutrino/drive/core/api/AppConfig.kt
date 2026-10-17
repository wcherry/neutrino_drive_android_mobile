package com.neutrino.drive.core.api

import com.neutrino.shared.core.api.NeutrinoAppConfig
import com.neutrino.shared.core.api.NeutrinoApps

/**
 * Drive's identity, from `NeutrinoApps.drive` in neutrino_shared_android: OAuth client
 * `neutrino-android` (`neutrino/migrations/00141_oauth__*`), preferences under `nd.`, Keystore alias
 * `neutrino.drive.secrets.v1`. Those values are what installed copies already hold; never change them.
 */
object AppConfig {
    val config: NeutrinoAppConfig = NeutrinoApps.drive
    val DISPLAY_NAME: String get() = config.displayName
    val DEFAULT_SERVER_URL: String get() = config.defaultServerUrl
}
