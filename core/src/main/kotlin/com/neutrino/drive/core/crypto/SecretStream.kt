package com.neutrino.drive.core.crypto

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * libsodium's `crypto_secretstream_xchacha20poly1305`, restricted to what Neutrino uses: a stream
 * of exactly **one** message, serialised as `[24-byte header][tag byte][ciphertext][16-byte MAC]`.
 *
 * Every client writes file content, encrypted metadata and the search snapshot this way (one push
 * tagged `FINAL`), and reads it ignoring the tag. The chunked NEB1 format in
 * `neutrino/agent_docs/chunked-file-encryption.md` is still a proposal; when it lands, decode has
 * to detect and accept both.
 *
 * Unlike the other clients this works on streams. One message still means one MAC over the whole
 * file, but nothing about the construction needs the plaintext in memory at once: encryption
 * writes ciphertext as it goes, and decryption holds back the last 16 bytes (the MAC) and verifies
 * at the end. A phone uploading a 2 GB video therefore never holds 2 GB. **Decrypted output is
 * untrusted until [decrypt] returns true** — callers write it somewhere temporary and discard it
 * on failure.
 *
 * One libsodium quirk is reproduced on purpose: the padding after the ciphertext is
 * `(0x10 - 64 + mlen) & 0xf` bytes, i.e. `mlen mod 16`, not the usual `-mlen mod 16`. It is what
 * libsodium does and therefore what the wire format is.
 */
object SecretStream {

    const val KEY_BYTES = 32
    const val HEADER_BYTES = 24
    /** Tag byte + Poly1305 MAC. */
    const val ABYTES = 17
    const val TAG_MESSAGE: Byte = 0
    const val TAG_FINAL: Byte = 3

    private const val BUFFER = 64 * 1024

    fun ciphertextLength(plaintextLength: Long): Long = HEADER_BYTES + ABYTES + plaintextLength

    /** `[header][one FINAL-tagged message]` of `plaintext`. */
    fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(plaintext.size + HEADER_BYTES + ABYTES)
        encrypt(ByteArrayInputStream(plaintext), out, key)
        return out.toByteArray()
    }

    /** Inverse of [encrypt], or `null` when the data does not authenticate under `key`. */
    fun decrypt(data: ByteArray, key: ByteArray): ByteArray? {
        if (data.size < HEADER_BYTES + ABYTES) return null
        val out = ByteArrayOutputStream(data.size)
        return if (decrypt(ByteArrayInputStream(data), out, key)) out.toByteArray() else null
    }

    /**
     * Streams `input` through one secretstream push into `output`. Returns the plaintext length.
     * Does not close either stream.
     */
    fun encrypt(input: InputStream, output: OutputStream, key: ByteArray, tag: Byte = TAG_FINAL): Long {
        require(key.size == KEY_BYTES) { "secretstream key must be 32 bytes" }
        val header = Sodium.randomBytes(HEADER_BYTES)
        val state = State(key, header)
        output.write(header)

        val block = ByteArray(64)
        block[0] = tag
        state.engine.processBytes(block, 0, 64, block, 0)
        state.poly.update(block, 0, 64)
        output.write(block[0].toInt())

        var mlen = 0L
        val buf = ByteArray(BUFFER)
        val cipher = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            state.engine.processBytes(buf, 0, n, cipher, 0)
            state.poly.update(cipher, 0, n)
            output.write(cipher, 0, n)
            mlen += n
        }
        buf.fill(0)
        output.write(state.finish(mlen))
        return mlen
    }

    /**
     * Streams a one-message secretstream from `input`, writing plaintext to `output` as it goes.
     *
     * Returns true only when the MAC verified. On false, everything written to `output` is
     * unauthenticated and must be discarded. Does not close either stream.
     */
    fun decrypt(input: InputStream, output: OutputStream, key: ByteArray): Boolean {
        if (key.size != KEY_BYTES) return false
        val header = input.readExactly(HEADER_BYTES) ?: return false
        val state = State(key, header)

        val c0 = input.read()
        if (c0 < 0) return false
        val block = ByteArray(64)
        block[0] = c0.toByte()
        state.engine.processBytes(block, 0, 64, block, 0)
        block[0] = c0.toByte()
        state.poly.update(block, 0, 64)

        // Everything up to the last 16 bytes is ciphertext; those 16 are the MAC. The stream's
        // length is not known in advance, so the most recent 16 bytes are always held back.
        val mac = 16
        var held = ByteArray(0)
        var mlen = 0L
        val buf = ByteArray(BUFFER)
        val plain = ByteArray(BUFFER + mac)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            val combined = held + buf.copyOf(n)
            val emit = combined.size - mac
            if (emit > 0) {
                state.poly.update(combined, 0, emit)
                state.engine.processBytes(combined, 0, emit, plain, 0)
                output.write(plain, 0, emit)
                mlen += emit
                held = combined.copyOfRange(emit, combined.size)
            } else {
                held = combined
            }
        }
        plain.fill(0)
        if (held.size != mac) return false
        return MessageDigest.isEqual(state.finish(mlen), held)
    }

    /** The per-message state after `init_push` / `init_pull`, positioned at block 1. */
    private class State(key: ByteArray, header: ByteArray) {
        val engine = ChaCha7539Engine()
        val poly = Poly1305()

        init {
            val subkey = Sodium.hchacha20(key, header.copyOfRange(0, 16))
            // nonce = counter (LE32, starts at 1) || header[16..24]
            val nonce = ByteArray(12)
            Sodium.putLe32(nonce, 0, 1)
            System.arraycopy(header, 16, nonce, 4, 8)
            engine.init(true, ParametersWithIV(KeyParameter(subkey), nonce))
            subkey.fill(0)
            // Block 0 of the keystream is the one-time Poly1305 key.
            val block0 = ByteArray(64)
            engine.processBytes(ByteArray(64), 0, 64, block0, 0)
            poly.init(KeyParameter(block0.copyOfRange(0, 32)))
            block0.fill(0)
            // No additional data, so neither it nor its padding is authenticated.
        }

        /** Padding, the two lengths, and the MAC. */
        fun finish(mlen: Long): ByteArray {
            val pad = (mlen and 0xf).toInt()
            if (pad > 0) poly.update(ByteArray(pad), 0, pad)
            val lengths = ByteArray(16)
            putLe64(lengths, 0, 0L)
            putLe64(lengths, 8, 64L + mlen)
            poly.update(lengths, 0, 16)
            return ByteArray(16).also { poly.doFinal(it, 0) }
        }
    }

    private fun putLe64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (8 * i)).toByte()
    }

    private fun InputStream.readExactly(n: Int): ByteArray? {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(out, read, n - read)
            if (r < 0) return null
            read += r
        }
        return out
    }
}
