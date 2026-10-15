// Everything that has to agree byte for byte with the web, iOS, macOS and Windows clients — the
// E2EE primitives, the keyring and its transports, the upload wire format — plus the HTTP client.
// Plain Kotlin/JVM with no Android dependency, so all of it is tested on the JVM in seconds.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(libs.bouncycastle)

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

// Driven by scripts/crosscheck-web-crypto.sh: `-Pargs="emit out.json"` / `-Pargs="verify a.json w.json"`.
tasks.register<JavaExec>("crossCheck") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.neutrino.drive.core.CrossCheck")
    args = (project.findProperty("args") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}
