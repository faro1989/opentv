/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.util.Base64
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * End-to-end encryption for everything OpenTV puts in the user's cloud.
 *
 * The key is derived from a password only the user knows (PBKDF2-HMAC-SHA256, 210 000 rounds,
 * salted with the cloud account name so every device with the same login + password derives the
 * same key). The cloud provider stores ciphertext only — it can't watch the recordings or read the
 * favourites, and neither could anyone who breaks into the cloud account.
 *
 * ## Recording format ("OTVE1", seekable)
 * ```
 * header : "OTVE1" | version(1) | chunkSize(4, BE) | baseNonce(8)          = 18 bytes
 * chunks : AES-256-GCM(chunk_i) with nonce = baseNonce | i(4, BE)          = plain + 16 tag each
 * ```
 * Each 64 KiB chunk is sealed on its own, so the player can jump anywhere: plaintext offset `p`
 * lives in chunk `p / 64 KiB` at a computable ciphertext offset. Reordering or truncating chunks is
 * caught because the chunk index is part of the nonce and GCM authenticates every chunk.
 */
object CloudCrypto {
    const val MAGIC = "OTVE1"
    const val HEADER = 18
    const val CHUNK = 64 * 1024
    const val TAG = 16
    const val SUFFIX = ".otve"
    private const val TEXT_PREFIX = "OTVE1:"

    @Volatile private var cached: Pair<String, SecretKeySpec>? = null

    /** The AES key for (account, password); cached because PBKDF2 is deliberately slow. */
    fun key(account: String, password: String): SecretKeySpec {
        val id = "$account\u0000$password"
        cached?.takeIf { it.first == id }?.let { return it.second }
        val spec = PBEKeySpec(password.toCharArray(), ("opentv-e2e:" + account.trim().lowercase()).toByteArray(), 210_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES").also { cached = id to it }
    }

    fun isEncryptedLocator(locator: String) = locator.endsWith(SUFFIX)

    private fun nonce(base: ByteArray, index: Int) = ByteBuffer.allocate(12).put(base).putInt(index).array()

    fun encryptedLength(plainLength: Long): Long {
        val full = plainLength / CHUNK
        val rest = plainLength % CHUNK
        return HEADER + full * (CHUNK + TAG) + if (rest > 0) rest + TAG else 0
    }

    /** Plain length of an encrypted file of [cipherLength] bytes. */
    fun plainLength(cipherLength: Long): Long {
        val body = (cipherLength - HEADER).coerceAtLeast(0)
        val full = body / (CHUNK + TAG)
        val rest = body % (CHUNK + TAG)
        return full * CHUNK + if (rest > TAG) rest - TAG else 0
    }

    fun cipherOffsetOfChunk(index: Long): Long = HEADER + index * (CHUNK + TAG)

    /** Streams [file] as an encrypted upload body — no temporary copy, exact Content-Length. */
    fun encryptingBody(file: File, key: SecretKeySpec, type: MediaType = "application/octet-stream".toMediaType()): RequestBody =
        object : RequestBody() {
            override fun contentType() = type
            override fun contentLength() = encryptedLength(file.length())
            override fun writeTo(sink: BufferedSink) {
                val base = ByteArray(8).also { SecureRandom().nextBytes(it) }
                sink.write(MAGIC.toByteArray()).writeByte(1).writeInt(CHUNK).write(base)
                file.inputStream().buffered(CHUNK).use { input ->
                    val buf = ByteArray(CHUNK)
                    var index = 0
                    while (true) {
                        val n = readFully(input, buf)
                        if (n <= 0) break
                        val c = Cipher.getInstance("AES/GCM/NoPadding")
                        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(base, index)))
                        sink.write(c.doFinal(buf, 0, n))
                        index++
                        if (n < CHUNK) break
                    }
                }
            }
        }

    fun readFully(input: InputStream, buf: ByteArray, len: Int = buf.size): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** Parses the header; returns the base nonce. */
    fun parseHeader(header: ByteArray): ByteArray {
        require(header.size >= HEADER && String(header, 0, 5, Charsets.US_ASCII) == MAGIC) { "Not an OpenTV encrypted file" }
        require(ByteBuffer.wrap(header, 6, 4).int == CHUNK) { "Unsupported chunk size" }
        return header.copyOfRange(10, 18)
    }

    fun decryptChunk(key: SecretKeySpec, base: ByteArray, index: Int, data: ByteArray, len: Int): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(base, index)))
        return c.doFinal(data, 0, len)
    }

    // ---- Small text (sync bundles) -------------------------------------------------------

    fun encryptText(plain: String, key: SecretKeySpec): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, iv))
        return TEXT_PREFIX + Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun isEncryptedText(text: String) = text.startsWith(TEXT_PREFIX)

    fun decryptText(text: String, key: SecretKeySpec): String {
        val raw = Base64.decode(text.removePrefix(TEXT_PREFIX), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }
}
