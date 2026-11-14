/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.opentv.core.ServiceLocator
import app.opentv.recording.CloudClient
import app.opentv.recording.CloudCrypto
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient

/**
 * Plays a recording stored in the user's WebDAV cloud: turns our `davs://`/`dav://` locator back
 * into its https/http URL and streams it with the cloud login as HTTP Basic auth. Range requests go
 * straight through, so seeking in a cloud recording works like in any other.
 *
 * End-to-end encrypted recordings (`*.otve`, see [CloudCrypto]) are decrypted chunk by chunk on the
 * way in: a seek to plaintext offset `p` becomes a range request from the start of chunk
 * `p / 64 KiB`, and the first `p % 64 KiB` decrypted bytes are skipped.
 */
@OptIn(UnstableApi::class)
class CloudDataSource private constructor(
    private val http: DataSource,
    private val key: SecretKeySpec?,
) : DataSource {

    private var uri: Uri? = null
    private var encrypted = false

    // Decrypt state
    private var base = ByteArray(8)
    private var chunkIndex = 0
    private var plainBuf = ByteArray(0)
    private var plainPos = 0
    private var remaining = C.LENGTH_UNSET.toLong()
    private val cipherBuf = ByteArray(CloudCrypto.CHUNK + CloudCrypto.TAG)

    override fun addTransferListener(transferListener: TransferListener) = http.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val url = Uri.parse(CloudClient.httpUrlOf(dataSpec.uri.toString()))
        uri = url
        encrypted = CloudCrypto.isEncryptedLocator(dataSpec.uri.toString())
        if (!encrypted) return http.open(dataSpec.buildUpon().setUri(url).build())

        val k = key ?: throw IOException("Encrypted recording — set the E2E password under Recording settings")
        // 1) Header: the base nonce.
        val header = ByteArray(CloudCrypto.HEADER)
        http.open(DataSpec.Builder().setUri(url).setPosition(0).setLength(CloudCrypto.HEADER.toLong()).build())
        readExactly(header, CloudCrypto.HEADER)
        http.close()
        base = CloudCrypto.parseHeader(header)

        // 2) Body from the chunk that holds the requested position.
        val p = dataSpec.position
        chunkIndex = (p / CloudCrypto.CHUNK).toInt()
        val cipherStart = CloudCrypto.cipherOffsetOfChunk(chunkIndex.toLong())
        val cipherRemaining = http.open(DataSpec.Builder().setUri(url).setPosition(cipherStart).build())
        plainBuf = ByteArray(0)
        plainPos = 0
        val skip = (p % CloudCrypto.CHUNK).toInt()
        if (!fillChunk()) return 0
        plainPos = skip.coerceAtMost(plainBuf.size)

        remaining = if (cipherRemaining == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
        else CloudCrypto.plainLength(cipherStart + cipherRemaining) - p
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            remaining = if (remaining == C.LENGTH_UNSET.toLong()) dataSpec.length else minOf(remaining, dataSpec.length)
        }
        return remaining
    }

    private fun readExactly(into: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = http.read(into, total, len - total)
            if (n == C.RESULT_END_OF_INPUT) break
            total += n
        }
        return total
    }

    /** Reads and decrypts the next chunk; false at end of file. */
    private fun fillChunk(): Boolean {
        val n = readExactly(cipherBuf, cipherBuf.size)
        if (n <= CloudCrypto.TAG) return false
        plainBuf = try {
            CloudCrypto.decryptChunk(key!!, base, chunkIndex, cipherBuf, n)
        } catch (e: Exception) {
            throw IOException("Decryption failed — wrong E2E password or damaged file", e)
        }
        plainPos = 0
        chunkIndex++
        return true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!encrypted) return http.read(buffer, offset, length)
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (plainPos >= plainBuf.size && !fillChunk()) return C.RESULT_END_OF_INPUT
        var n = minOf(length, plainBuf.size - plainPos)
        if (remaining != C.LENGTH_UNSET.toLong()) n = minOf(n.toLong(), remaining).toInt()
        System.arraycopy(plainBuf, plainPos, buffer, offset, n)
        plainPos += n
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        http.close()
        plainBuf = ByteArray(0)
    }

    class Factory(private val context: Context) : DataSource.Factory {
        private val client by lazy { OkHttpClient() }
        override fun createDataSource(): DataSource {
            val settings = ServiceLocator.get(context).settings
            val cfg = CloudClient.config(settings)
            val http = OkHttpDataSource.Factory(client)
                .setDefaultRequestProperties(mapOf("Authorization" to CloudClient.authHeader(cfg)))
                .createDataSource()
            // Decryption needs the password even if new uploads currently go out unencrypted.
            val key = settings.cloudE2EPassword.value.takeIf { it.isNotEmpty() }?.let { CloudCrypto.key(cfg.username, it) }
            return CloudDataSource(http, key)
        }
    }
}
