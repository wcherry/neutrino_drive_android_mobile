package com.neutrino.drive.core

import com.neutrino.drive.core.api.NeutrinoApi
import com.neutrino.drive.core.drive.Downloader
import com.neutrino.drive.core.drive.FilePendingKeyStore
import com.neutrino.drive.core.drive.ImportMetadata
import com.neutrino.drive.core.drive.KeyShareFailure
import com.neutrino.drive.core.drive.SharePartialException
import com.neutrino.drive.core.drive.ShareRole
import com.neutrino.drive.core.drive.SharingService
import com.neutrino.drive.core.drive.UploadSource
import com.neutrino.drive.core.drive.Uploader
import com.neutrino.shared.core.FakeServer
import com.neutrino.shared.core.crypto.B64
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.crypto.Sodium
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.InMemoryKeyringStore
import com.neutrino.shared.core.keys.KeyException
import com.neutrino.shared.core.keys.KeyManager
import com.neutrino.shared.core.keys.Keyring
import com.neutrino.shared.core.keys.KeyringException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * Drive's protocol against [FakeServer]: real HTTP, real crypto, real multipart. Sign-in and key
 * provisioning are tested in neutrino_shared_android.
 */
class EndToEndTest {

    private lateinit var fake: FakeServer
    private lateinit var tmp: File

    @Before
    fun setUp() {
        fake = FakeServer()
        tmp = Files.createTempDirectory("nd-test").toFile()
    }

    @After
    fun tearDown() {
        fake.shutdown()
        tmp.deleteRecursively()
    }

    private class Client(val fake: FakeServer, tmp: File) {
        val api = fake.client { http, auth -> NeutrinoApi(http, auth) }
        val keys = InMemoryKeyringStore()
        val check = DeviceKeyCheck(api, keys)
        val manager = KeyManager(api, keys, check)
        val pending = FilePendingKeyStore(File(tmp, "pending.json"))
        val uploader = Uploader(api, keys, check, pending, File(tmp, "up"))
        val downloader = Downloader(api, keys)
    }

    private fun signedIn(): Client = Client(fake, tmp).also { runBlocking { it.api.auth.login(fake.email, fake.password) } }

    @Test
    fun uploadThenDownloadRoundTripsAndSendsThePartsInTheServersOrder() = runBlocking {
        val c = signedIn()
        c.manager.provision()
        val content = ByteArray(200_000) { (it * 13).toByte() }
        val created = Instant.parse("2025-06-01T10:15:30Z")
        val result = c.uploader.upload(
            UploadSource("Plan — v2.pdf", "application/pdf") { content.inputStream() },
            folderId = null,
            thumbnailBase64 = "AAAA",
            importMetadata = ImportMetadata(created, created, "photo-sync:42"),
        )
        assertEquals(
            listOf("encrypted_metadata", "thumbnail_b64", "encrypted_file_key", "key_version",
                "created_at", "updated_at", "import_source", "file"),
            fake.lastUploadParts,
        )
        val stored = fake.files[result.id]!!
        assertEquals("application/pdf", stored.mimeType)
        assertEquals("Plan — v2.pdf", stored.name)
        assertEquals("2025-06-01T10:15:30Z", stored.createdAt)
        // The server holds ciphertext, not the file.
        assertFalse(String(stored.blob, Charsets.ISO_8859_1).contains(String(content.copyOf(64), Charsets.ISO_8859_1)))
        // The key came in the body; no separate PUT was needed.
        assertFalse(fake.requestLog.any { it.startsWith("PUT /api/v1/drive/files/${result.id}/key") })
        assertTrue(c.pending.all().isEmpty())

        val out = File(tmp, "out.pdf")
        c.downloader.download(result.id, out)
        assertArrayEquals(content, out.readBytes())

        // And the metadata is what the web would read.
        val dek = FileCrypto.openSealedDek(fake.keyRefs[result.id]!![fake.userId]!!.first, c.manager.keyring()!!.active!!.secretKey)!!
        assertEquals(FileCrypto.Metadata("Plan — v2.pdf", "application/pdf"), FileCrypto.decryptMetadata(stored.encryptedMetadata, dek))
    }

    @Test
    fun anOlderServerGetsTheKeyByPut() = runBlocking {
        fake.acceptsKeyInBody = false
        val c = signedIn()
        c.manager.provision()
        val result = c.uploader.upload(UploadSource("a.txt", "text/plain") { "hi".byteInputStream() }, null)
        assertTrue(fake.requestLog.any { it == "PUT /api/v1/drive/files/${result.id}/key" })
        val out = File(tmp, "a.txt")
        c.downloader.download(result.id, out)
        assertEquals("hi", out.readText())
    }

    @Test
    fun aStaleKeyIsRefusedBeforeAnythingIsSealed() = runBlocking {
        val c = signedIn()
        c.manager.provision()
        // Another device replaces the account key.
        fake.publishedKeys[fake.userId]!! += FakeServer.Published(B64.encode(Sodium.boxKeyPair().publicKey), 2)
        c.check.forget()
        try {
            c.uploader.upload(UploadSource("a.txt", "text/plain") { "x".byteInputStream() }, null); fail()
        } catch (_: KeyException.StaleEncryptionKey) {
        }
        assertTrue(fake.files.isEmpty())
    }

    @Test
    fun noKeyMeansNoUpload() = runBlocking {
        val c = signedIn()
        try {
            c.uploader.upload(UploadSource("a.txt", "text/plain") { "x".byteInputStream() }, null); fail()
        } catch (_: KeyException.NoEncryptionKey) {
        }
    }

    @Test
    fun aFileSealedToARetiredVersionNeedsThatVersion() = runBlocking {
        val c = signedIn()
        c.manager.provision()
        val id = c.uploader.upload(UploadSource("old.txt", "text/plain") { "old".byteInputStream() }, null).id

        // Rotate: v1 retired, v2 active. A device holding only v2 cannot open the v1 file ...
        val v1 = c.manager.keyring()!!.active!!
        val v2 = Sodium.boxKeyPair()
        fake.publishedKeys[fake.userId]!! += FakeServer.Published(B64.encode(v2.publicKey), 2)
        val phone = signedIn()
        phone.keys.store(Keyring.fromKeyPair(fake.userId, v2.secretKey, 2))
        try {
            phone.downloader.download(id, File(tmp, "x")); fail()
        } catch (e: KeyringException.MissingVersion) {
            assertEquals(1, e.version)
        }

        // ... until it pulls the key file, where v1 is sealed to v2.
        fake.keyFile = """{"userId":"${fake.userId}","keys":[{"keyVersion":1,"encryptedKey":"${B64.encode(Sodium.boxSeal(v1.secretKey, v2.publicKey))}","publicKey":"${B64.encode(v1.publicKey)}"}],"createdAt":"c","updatedAt":"u"}"""
        val outcome = phone.manager.pullKeyFile()
        assertEquals(1, outcome.recovered)
        assertFalse(outcome.activeIsStale)
        phone.downloader.download(id, File(tmp, "old.txt"))
        assertEquals("old", File(tmp, "old.txt").readText())
        assertEquals(2, phone.manager.keyring()!!.active!!.version)
    }

    @Test
    fun sharingGrantsBeforeItSharesTheKeyAndReportsPartialSuccess() = runBlocking {
        val c = signedIn()
        c.manager.provision()
        val id = c.uploader.upload(UploadSource("s.txt", "text/plain") { "secret".byteInputStream() }, null).id
        val sharing = SharingService(c.api, c.keys)

        // Recipient has no key yet: permission granted, key share reported as partial.
        fake.users["user-0002"] = "b@example.com"
        try {
            sharing.addPerson("b@example.com", ShareRole.VIEWER, false, id); fail()
        } catch (e: SharePartialException) {
            assertEquals(KeyShareFailure.RECIPIENT_HAS_NO_KEY, e.reason)
        }
        assertTrue(fake.permissions.any { it.second == "user-0002" })

        // Once they publish one, the DEK is re-sealed to it, under their version.
        val theirs = Sodium.boxKeyPair()
        fake.publishedKeys["user-0002"] = mutableListOf(FakeServer.Published(B64.encode(theirs.publicKey), 1))
        sharing.addPerson("b@example.com", ShareRole.EDITOR, false, id)
        val (sealed, version) = fake.keyRefs[id]!!["user-0002"]!!
        assertEquals(1, version)
        val dek = FileCrypto.openSealedDek(sealed, theirs.secretKey)
        assertNotNull(dek)
        assertEquals("secret", FileCrypto.decryptFile(fake.files[id]!!.blob, dek!!).decodeToString())

        val grant = fake.requestLog.indexOfLast { it.endsWith("/permissions") }
        val share = fake.requestLog.indexOfLast { it.endsWith("/key/share") }
        assertTrue(grant < share)
    }

    @Test
    fun anInterruptedUploadIsFinishedNotDuplicated() = runBlocking {
        val c = signedIn()
        c.manager.provision()
        fake.acceptsKeyInBody = false
        val first = c.uploader.upload(UploadSource("p.jpg", "image/jpeg") { "pixels".byteInputStream() }, null, uploadId = "photo-sync:7")
        // Simulate a crash between the blob POST and the key PUT: drop the key ref, leave a record.
        val (sealed, version) = fake.keyRefs[first.id]!![fake.userId]!!
        fake.keyRefs.remove(first.id)
        c.pending.put(com.neutrino.drive.core.drive.PendingKey("photo-sync:7", sealed, version, "p.jpg", first.id, System.currentTimeMillis()))

        val again = c.uploader.upload(UploadSource("p.jpg", "image/jpeg") { "pixels".byteInputStream() }, null, uploadId = "photo-sync:7")
        assertEquals(first.id, again.id)
        assertEquals(1, fake.files.size)
        assertNotNull(fake.keyRefs[first.id])
        assertNull(c.pending.get("photo-sync:7"))
    }
}
