package com.neutrino.drive.core.drive

import com.neutrino.drive.core.api.ApiException
import com.neutrino.drive.core.api.NeutrinoApi
import com.neutrino.drive.core.api.PermissionDto
import com.neutrino.drive.core.api.UserDto
import com.neutrino.drive.core.crypto.FileCrypto

/** Roles the permissions endpoints accept; the Rust `Role` enum serialises them lowercase. */
enum class ShareRole(val wire: String, val label: String) {
    VIEWER("viewer", "Viewer"),
    COMMENTER("commenter", "Commenter"),
    EDITOR("editor", "Editor"),
    OWNER("owner", "Owner");

    companion object {
        val assignable = listOf(VIEWER, COMMENTER, EDITOR)
        fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: VIEWER
    }
}

/** Why a DEK re-wrap could not be completed — each is a different story for the user. */
enum class KeyShareFailure(val explanation: String) {
    RECIPIENT_HAS_NO_KEY("they have not set up an encryption key yet. Ask them to sign in to Neutrino and set one up, then share again."),
    FILE_HAS_NO_KEY("this file has no encryption key stored."),
    CANNOT_UNSEAL_OWN_KEY("your encryption key could not open this file's key."),
    RECIPIENT_KEY_INVALID("their encryption key is not valid."),
    SERVER("the server rejected the key share."),
}

class SharePartialException(val recipientName: String, val reason: KeyShareFailure) :
    Exception("Shared with $recipientName, but they cannot decrypt it yet — ${reason.explanation}")

class UserNotFoundException(email: String) : Exception("No Neutrino account found for $email.")

/**
 * Sharing, and the part that matters: re-wrapping a file's DEK to the recipient's public key so
 * end-to-end encryption survives being shared. The same seven steps as `SharingService.swift`:
 *
 * 1. look the recipient up by email
 * 2. grant the permission — **before** the key share: the server rejects a key share
 *    (`RECIPIENT_NO_ACCESS`) for someone with no permission yet
 * 3. fetch our sealed DEK, 4. open it, 5. fetch their public key, 6. seal to it,
 * 7. `POST /files/{id}/key/share`, filed under *their* key version
 *
 * Folders stop after step 2 — there is no folder DEK. A failed re-wrap leaves the permission in
 * place and throws [SharePartialException]: a partial success, never reported as either extreme.
 */
class SharingService(private val api: NeutrinoApi, private val keys: KeyringStore) {

    suspend fun addPerson(email: String, role: ShareRole, isFolder: Boolean, resourceId: String): PermissionDto {
        val trimmed = email.trim()
        val user = api.lookupUser(trimmed) ?: throw UserNotFoundException(trimmed)
        val granted = api.grant(resource(isFolder), resourceId, user, role.wire)
        if (!isFolder) {
            shareFileKey(resourceId, user)?.let {
                throw SharePartialException(user.name.ifBlank { user.email }, it)
            }
        }
        return granted
    }

    /** `null` on success, or why the DEK could not be re-wrapped. */
    suspend fun shareFileKey(fileId: String, recipient: UserDto): KeyShareFailure? {
        val ours = try {
            api.fileKey(fileId) ?: return KeyShareFailure.FILE_HAS_NO_KEY
        } catch (_: ApiException) {
            return KeyShareFailure.SERVER
        }
        val keyring = api.auth.userId?.let(keys::load) ?: return KeyShareFailure.CANNOT_UNSEAL_OWN_KEY
        val (dek, _) = keyring.openDek(ours.encryptedFileKey, ours.keyVersion ?: 1)
            ?: return KeyShareFailure.CANNOT_UNSEAL_OWN_KEY
        try {
            val theirs = try {
                api.publishedKey(recipient.id) ?: return KeyShareFailure.RECIPIENT_HAS_NO_KEY
            } catch (_: ApiException) {
                return KeyShareFailure.SERVER
            }
            val resealed = try {
                FileCrypto.sealDek(dek, theirs.publicKey)
            } catch (_: FileCrypto.CryptoException) {
                return KeyShareFailure.RECIPIENT_KEY_INVALID
            }
            return try {
                api.shareFileKey(fileId, recipient.id, resealed, theirs.version)
                null
            } catch (_: ApiException) {
                KeyShareFailure.SERVER
            }
        } finally {
            dek.fill(0)
        }
    }

    suspend fun permissions(isFolder: Boolean, id: String) = api.permissions(resource(isFolder), id)

    suspend fun updateRole(isFolder: Boolean, id: String, userId: String, role: ShareRole) =
        api.updatePermission(resource(isFolder), id, userId, role.wire)

    /** Not forward secrecy: a recipient who already fetched the DEK keeps it. */
    suspend fun revoke(isFolder: Boolean, id: String, userId: String) = api.revoke(resource(isFolder), id, userId)

    suspend fun createLink(isFolder: Boolean, id: String, role: ShareRole = ShareRole.VIEWER): String {
        val link = api.upsertShareLink(resource(isFolder), id, role.wire)
        return "${api.auth.serverUrl}/share/${link.token}"
    }

    suspend fun deleteLink(isFolder: Boolean, id: String) = api.deleteShareLink(resource(isFolder), id)

    private fun resource(isFolder: Boolean) = if (isFolder) "folders" else "files"
}
