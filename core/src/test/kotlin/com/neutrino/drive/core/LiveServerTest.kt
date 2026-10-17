package com.neutrino.drive.core

import com.neutrino.drive.core.api.NeutrinoApi
import com.neutrino.drive.core.drive.Downloader
import com.neutrino.drive.core.drive.FilePendingKeyStore
import com.neutrino.drive.core.drive.ImportMetadata
import com.neutrino.drive.core.drive.ShareRole
import com.neutrino.drive.core.drive.SharingService
import com.neutrino.drive.core.drive.UploadSource
import com.neutrino.drive.core.drive.Uploader
import com.neutrino.shared.core.api.AuthClient
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.InMemoryKeyringStore
import com.neutrino.shared.core.keys.KeyManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * The client against a real Neutrino server. Skipped unless `NEUTRINO_LIVE_URL` names one with two
 * registered accounts (`NEUTRINO_LIVE_EMAIL`/`_PASSWORD` and `NEUTRINO_LIVE_EMAIL2`/`_PASSWORD2`)
 * that have no keys yet — use a throwaway database; see agent_docs/android-port.md.
 */
class LiveServerTest {
    private val url = System.getenv("NEUTRINO_LIVE_URL")

    private class Device(url: String, tmp: File) {
        val api = NeutrinoApi(OkHttpClient(), AuthClient(com.neutrino.drive.core.api.AppConfig.config, OkHttpClient(), com.neutrino.shared.core.MemorySessionStore(), url) { "JVM test - Neutrino Drive" })
        val keys = InMemoryKeyringStore()
        val check = DeviceKeyCheck(api, keys)
        val manager = KeyManager(api, keys, check)
        val uploader = Uploader(api, keys, check, FilePendingKeyStore(File(tmp, "p-${hashCode()}.json")), File(tmp, "u"))
        val downloader = Downloader(api, keys)
    }

    @Test
    fun fullRoundTripAgainstARealServer(): Unit = runBlocking {
        assumeTrue(url != null)
        val tmp = Files.createTempDirectory("nd-live").toFile()
        val a = Device(url!!, tmp)
        a.api.auth.login(System.getenv("NEUTRINO_LIVE_EMAIL"), System.getenv("NEUTRINO_LIVE_PASSWORD"))
        val kit = if (a.manager.canProvision()) a.manager.provision() else error("account already has a key")

        val content = ByteArray(300_000) { (it * 7).toByte() }
        val folder = a.api.createFolder("Live test ${System.currentTimeMillis()}", null)
        val taken = Instant.parse("2025-06-01T10:15:30Z")
        val uploaded = a.uploader.upload(
            UploadSource("report.bin", "application/octet-stream") { content.inputStream() }, folder.id,
            importMetadata = ImportMetadata(taken, taken, "photo-sync:android:image:1"),
        )
        assertEquals("photo-sync:android:image:1", uploaded.importSource)
        assertEquals(uploaded.id, a.api.folder(folder.id).files.single().id)

        val out = File(tmp, "out.bin")
        a.downloader.download(uploaded.id, out)
        assertArrayEquals(content, out.readBytes())

        // A second device restores from the kit and reads the same file.
        val b = Device(url, tmp)
        b.api.auth.login(System.getenv("NEUTRINO_LIVE_EMAIL"), System.getenv("NEUTRINO_LIVE_PASSWORD"))
        b.manager.restoreRecoveryKit(kit)
        b.downloader.download(uploaded.id, File(tmp, "b.bin"))
        assertArrayEquals(content, File(tmp, "b.bin").readBytes())

        // Share with the second account, which gets its own key first.
        val other = Device(url, tmp)
        other.api.auth.login(System.getenv("NEUTRINO_LIVE_EMAIL2"), System.getenv("NEUTRINO_LIVE_PASSWORD2"))
        other.manager.provision()
        SharingService(a.api, a.keys).addPerson(System.getenv("NEUTRINO_LIVE_EMAIL2"), ShareRole.VIEWER, false, uploaded.id)
        other.downloader.download(uploaded.id, File(tmp, "c.bin"))
        assertArrayEquals(content, File(tmp, "c.bin").readBytes())

        // Metadata is what the web would read.
        val ref = a.api.fileKey(uploaded.id)!!
        assertNotNull(FileCrypto.openSealedDek(ref.encryptedFileKey, a.manager.keyring()!!.active!!.secretKey))
        println("LIVE OK: ${a.api.versions(uploaded.id).size} versions, quota used ${a.api.quota().usedBytes}")
        tmp.deleteRecursively()
    }
}
