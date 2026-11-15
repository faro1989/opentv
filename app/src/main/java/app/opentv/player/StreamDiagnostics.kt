/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import app.opentv.vpn.VpnController

/**
 * Stream diagnosis: watches one player and answers "why does it stutter?".
 *
 * It listens to ExoPlayer's analytics — every finished segment/chunk download (bytes and time →
 * real throughput, and the server's host name), the bandwidth estimate, buffer stalls and frames the
 * decoder had to drop — and combines that with what the device knows about its own link (Wi-Fi
 * signal strength or Ethernet, VPN on or off). [snapshot] turns it into numbers for the info panel
 * and a [Verdict]: fine, the provider's server, the Wi-Fi, the VPN, the internet line, or the box
 * itself being too slow to decode.
 */
@OptIn(UnstableApi::class)
class StreamDiagnostics(private val context: Context, private val player: ExoPlayer) : AnalyticsListener {

    enum class Verdict { STARTING, OK, PROVIDER, WIFI, VPN, CONNECTION, DEVICE }
    enum class Link { ETHERNET, WIFI, MOBILE, OTHER, NONE }

    data class Snapshot(
        val width: Int,
        val height: Int,
        val frameRate: Float,
        val videoCodec: String?,
        /** Declared stream bitrate (bits/s), or the measured media rate when the stream declares none. */
        val videoBitrate: Long,
        val audioCodec: String?,
        val audioChannels: Int,
        /** Measured download speed from the server (bits/s). */
        val throughput: Long,
        val bufferMs: Long,
        val stalls: Int,
        val droppedFrames: Int,
        val server: String?,
        val link: Link,
        val wifiRssi: Int?,
        val vpnOn: Boolean,
        val verdict: Verdict,
    ) {
        /** How much faster the line delivers than the stream needs (≥ 1.3 is comfortable). */
        val headroom: Float get() = if (videoBitrate > 0) throughput.toFloat() / videoBitrate else 0f
    }

    private var throughputEma = 0L
    private var bandwidthEstimate = 0L
    private var stalls = 0
    private var dropped = 0
    private var server: String? = null
    private var lastState = Player.STATE_IDLE
    private var mediaBytes = 0L
    private var mediaStartMs = 0L

    init { runCatching { player.addAnalyticsListener(this) } }

    /** New channel: start the counters again (the server and line stats carry over sensibly). */
    fun reset() {
        stalls = 0
        dropped = 0
        mediaBytes = 0
        mediaStartMs = 0
        lastState = Player.STATE_IDLE
    }

    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
        bandwidthEstimate = bitrateEstimate
    }

    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        loadEventInfo.uri.host?.let { server = it }
        if (mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA) {
            if (mediaStartMs == 0L) mediaStartMs = System.currentTimeMillis()
            mediaBytes += loadEventInfo.bytesLoaded
        }
        // Throughput of this one download; ignore tiny ones (playlists), they only measure latency.
        if (loadEventInfo.loadDurationMs > 40 && loadEventInfo.bytesLoaded > 32_000) {
            val bps = loadEventInfo.bytesLoaded * 8_000 / loadEventInfo.loadDurationMs
            throughputEma = if (throughputEma == 0L) bps else (throughputEma * 7 + bps * 3) / 10
        }
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        if (lastState == Player.STATE_READY && state == Player.STATE_BUFFERING) stalls++
        lastState = state
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        dropped += droppedFrames
    }

    /** Call on the main thread (reads the player). */
    fun snapshot(): Snapshot {
        val v: Format? = player.videoFormat
        val a: Format? = player.audioFormat
        val declared = listOf(v?.bitrate, v?.averageBitrate, v?.peakBitrate).firstOrNull { it != null && it > 0 }?.toLong() ?: 0L
        // Many IPTV streams declare no bitrate: derive it from the media actually received.
        val elapsed = (System.currentTimeMillis() - mediaStartMs).coerceAtLeast(1)
        val measuredMedia = if (mediaStartMs > 0 && elapsed > 5_000) mediaBytes * 8_000 / elapsed else 0L
        val videoBitrate = if (declared > 0) declared else measuredMedia
        val throughput = maxOf(throughputEma, bandwidthEstimate)
        val buffer = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
        val (link, rssi) = linkInfo()
        val vpnOn = runCatching { VpnController.get(context).status.value == VpnController.Status.CONNECTED }.getOrDefault(false)

        val verdict = when {
            v == null || lastState == Player.STATE_IDLE -> Verdict.STARTING
            // Enough data arrives, yet frames are thrown away: the decoder can't keep up.
            dropped > 60 && stalls == 0 -> Verdict.DEVICE
            stalls > 0 || (buffer < 2_500 && lastState == Player.STATE_READY) -> when {
                link == Link.WIFI && rssi != null && rssi < -70 -> Verdict.WIFI
                videoBitrate > 0 && throughput in 1 until (videoBitrate * 13 / 10) ->
                    if (vpnOn) Verdict.VPN else Verdict.CONNECTION
                else -> Verdict.PROVIDER
            }
            else -> Verdict.OK
        }
        return Snapshot(
            width = v?.width ?: 0, height = v?.height ?: 0,
            frameRate = v?.frameRate?.takeIf { it > 0 } ?: 0f,
            videoCodec = codecName(v),
            videoBitrate = videoBitrate,
            audioCodec = codecName(a),
            audioChannels = a?.channelCount?.takeIf { it > 0 } ?: 0,
            throughput = throughput,
            bufferMs = buffer,
            stalls = stalls,
            droppedFrames = dropped,
            server = server,
            link = link,
            wifiRssi = rssi,
            vpnOn = vpnOn,
            verdict = verdict,
        )
    }

    private fun codecName(f: Format?): String? {
        f ?: return null
        val c = f.codecs.orEmpty().lowercase()
        return when {
            c.startsWith("avc") || f.sampleMimeType == MimeTypes.VIDEO_H264 -> "H.264"
            c.startsWith("hvc") || c.startsWith("hev") || f.sampleMimeType == MimeTypes.VIDEO_H265 -> "HEVC"
            c.startsWith("av01") || f.sampleMimeType == MimeTypes.VIDEO_AV1 -> "AV1"
            f.sampleMimeType == MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            c.startsWith("mp4a") || f.sampleMimeType == MimeTypes.AUDIO_AAC -> "AAC"
            f.sampleMimeType == MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            f.sampleMimeType == MimeTypes.AUDIO_E_AC3 || f.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
            f.sampleMimeType == MimeTypes.AUDIO_MPEG || f.sampleMimeType == MimeTypes.AUDIO_MPEG_L2 -> "MPEG Audio"
            else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun linkInfo(): Pair<Link, Int?> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return Link.NONE to null
        // With the VPN up the active network is the tunnel; look at the physical networks under it.
        val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .filterNot { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        val link = when {
            caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } -> Link.ETHERNET
            caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> Link.WIFI
            caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } -> Link.MOBILE
            caps.isNotEmpty() -> Link.OTHER
            else -> Link.NONE
        }
        val rssi = if (link == Link.WIFI) runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.rssi
                .takeIf { it in -126..0 }
        }.getOrNull() else null
        return link to rssi
    }
}
