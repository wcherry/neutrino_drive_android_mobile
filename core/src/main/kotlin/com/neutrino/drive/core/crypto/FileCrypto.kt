package com.neutrino.drive.core.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Drive E2EE scheme, shared byte for byte with every other client:
 *
 * - **File content**: one [SecretStream] push under a random 32-byte DEK, `[header][ciphertext]`.
 * - **DEK transport**: `crypto_box_seal` to a keyring entry's public key, base64url.
 * - **Metadata**: `{"name","mimeType"}` JSON through the same file encryption, base64url — the
 *   `encrypted_metadata` upload field.
 */
object FileCrypto {

    const val DEK_BYTES = 32

    class CryptoException(message: String) : Exception(message)

    @Serializable
    data class Metadata(val name: String, val mimeType: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun generateDek(): ByteArray = Sodium.randomBytes(DEK_BYTES)

    fun encryptFile(plaintext: ByteArray, dek: ByteArray): ByteArray = SecretStream.encrypt(plaintext, dek)

    fun decryptFile(data: ByteArray, dek: ByteArray): ByteArray =
        SecretStream.decrypt(data, dek)
            ?: throw CryptoException("File content could not be decrypted — the data may be corrupt.")

    fun encryptMetadata(name: String, mimeType: String, dek: ByteArray): String =
        B64.encode(encryptFile(json.encodeToString(Metadata.serializer(), Metadata(name, mimeType)).toByteArray(), dek))

    fun decryptMetadata(encrypted: String, dek: ByteArray): Metadata {
        val data = B64.decode(encrypted) ?: throw CryptoException("Metadata is not valid base64.")
        return json.decodeFromString(Metadata.serializer(), decryptFile(data, dek).decodeToString())
    }

    /**
     * `crypto_box_seal(dek, recipient)` as base64url.
     *
     * Refuses a key that is not 32 bytes rather than sealing to it: the result would be ciphertext
     * nobody can open, and nothing would fail until someone tried.
     */
    fun sealDek(dek: ByteArray, recipientPublicKey: ByteArray): String {
        if (recipientPublicKey.size != Sodium.BOX_PUBLIC_KEY_BYTES) {
            throw CryptoException("That encryption key is not valid.")
        }
        return B64.encode(Sodium.boxSeal(dek, recipientPublicKey))
    }

    fun sealDek(dek: ByteArray, recipientPublicKeyB64: String): String =
        sealDek(dek, B64.decode(recipientPublicKeyB64) ?: throw CryptoException("That encryption key is not valid."))

    /** Opens a base64url sealed DEK with a keyring entry's secret key, or `null`. */
    fun openSealedDek(sealedB64: String, secretKey: ByteArray): ByteArray? {
        val sealed = B64.decode(sealedB64) ?: return null
        val dek = Sodium.boxSealOpen(sealed, secretKey) ?: return null
        return if (dek.size == DEK_BYTES) dek else null
    }

    /** PKCE code challenge: base64url(SHA-256(verifier)). */
    fun pkceChallenge(verifier: String): String = B64.encode(Sodium.sha256(verifier.toByteArray()))

    fun sha256Hex(data: ByteArray): String = Sodium.sha256(data).joinToString("") { "%02x".format(it) }
}
