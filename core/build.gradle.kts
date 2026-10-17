// Drive's protocol on top of neutrino_shared_android's core (E2EE, keyring, sign-in): the upload
// wire format, downloads, sharing and the Drive API. Plain Kotlin/JVM with no Android dependency,
// so all of it is tested on the JVM in seconds.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.neutrino.shared:neutrino-core")
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(libs.bouncycastle)

    testImplementation(testFixtures("com.neutrino.shared:neutrino-core"))
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
