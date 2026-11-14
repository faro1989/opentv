/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import android.content.Context
import android.net.Uri
import app.opentv.core.ServiceLocator
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.recording.CloudCrypto
import app.opentv.vpn.VpnController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup & restore in one file the user keeps wherever they like (USB stick, phone, cloud).
 *
 * The file holds everything needed to rebuild OpenTV on a new box: providers (with their logins),
 * favourites and named lists, own channel numbers, locks, watch positions, numbering rules, cloud,
 * NAS and VPN settings. Because that includes passwords and the VPN key, the whole file is
 * encrypted with a password the user picks (AES-256-GCM, key via PBKDF2) — without it, the file
 * is unreadable, to us as well.
 *
 * Channels only exist after the provider's catalogue has loaded, so favourites/lists/numbers are
 * parked as a "pending restore" and merged once the channels are there ([applyPendingIfReady]).
 */
object BackupManager {
    private const val PENDING = "pending-restore.json"
    private fun key(password: String) = CloudCrypto.key("opentv-backup", password)

    suspend fun export(context: Context, target: Uri, password: String) = withContext(Dispatchers.IO) {
        val graph = ServiceLocator.get(context)
        val s = graph.settings
        val root = JSONObject()
        root.put("format", "opentv-backup").put("version", 1).put("createdAt", System.currentTimeMillis())
        root.put("sources", JSONArray().apply {
            graph.database.sources().enabled().forEach { src ->
                put(JSONObject().put("name", src.name).put("kind", src.kind.name).put("url", src.url)
                    .put("username", src.username).put("password", src.password).put("mac", src.macAddress)
                    .put("epgUrl", src.epgUrl).put("userAgent", src.userAgent).put("liveFormat", src.liveFormat.name))
            }
        })
        root.put("sync", SyncEngine(graph).let { it.encode(it.gather()) })
        root.put("settings", JSONObject()
            .put("lcn", s.lcnNumbers.value).put("forceCustom", s.forceCustomNumbers.value).put("closeGaps", s.closeNumberGaps.value)
            .put("cloudProvider", s.cloudProvider.value).put("cloudUrl", s.cloudUrl.value).put("cloudUser", s.cloudUser.value)
            .put("cloudPassword", s.cloudPassword.value).put("cloudFolder", s.cloudFolder.value).put("cloudKeepLocal", s.cloudKeepLocal.value)
            .put("cloudE2E", s.cloudE2E.value).put("cloudE2EPassword", s.cloudE2EPassword.value)
            .put("smbHost", s.smbHost.value).put("smbShare", s.smbShare.value).put("smbFolder", s.smbFolder.value)
            .put("smbUser", s.smbUser.value).put("smbPassword", s.smbPassword.value).put("smbEncrypt", s.smbEncrypt.value)
            .put("syncWifiOnly", s.syncWifiOnly.value).put("cloudAutoUpload", s.cloudAutoUpload.value).put("cloudLiveSync", s.cloudLiveSync.value))
        VpnController.get(context).profile.value.let { v ->
            root.put("vpn", JSONObject().put("provider", v.provider).put("privateKey", v.privateKey).put("address", v.address)
                .put("dns", v.dns).put("publicKey", v.publicKey).put("presharedKey", v.presharedKey).put("endpoint", v.endpoint)
                .put("allowedIps", v.allowedIps).put("onlyThisApp", v.onlyThisApp).put("autoConnect", v.autoConnect).put("killSwitch", v.killSwitch))
        }
        val sealed = CloudCrypto.encryptText(root.toString(), key(password))
        context.contentResolver.openOutputStream(target, "wt")!!.use { it.write(sealed.toByteArray()) }
    }

    /** Restores a backup. Returns the number of providers added; throws on a wrong password. */
    suspend fun import(context: Context, source: Uri, password: String): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(source)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        require(CloudCrypto.isEncryptedText(text)) { "Not an OpenTV backup" }
        val root = runCatching { JSONObject(CloudCrypto.decryptText(text, key(password))) }
            .getOrElse { throw IllegalArgumentException("Wrong password or damaged file") }
        require(root.optString("format") == "opentv-backup") { "Not an OpenTV backup" }
        val graph = ServiceLocator.get(context)
        val s = graph.settings

        // Providers: add any that this device doesn't have yet (same URL + user = same provider).
        val existing = graph.database.sources().enabled().map { it.url to it.username }.toSet()
        var added = 0
        val arr = root.optJSONArray("sources") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val user = o.optString("username").takeIf { it.isNotEmpty() && it != "null" }
            if ((o.getString("url") to user) in existing) continue
            graph.database.sources().insert(
                Source(
                    name = o.getString("name"),
                    kind = SourceKind.valueOf(o.getString("kind")),
                    url = o.getString("url"),
                    username = user,
                    password = o.optString("password").takeIf { it.isNotEmpty() && it != "null" },
                    macAddress = o.optString("mac").takeIf { it.isNotEmpty() && it != "null" },
                    epgUrl = o.optString("epgUrl").takeIf { it.isNotEmpty() && it != "null" },
                    userAgent = o.optString("userAgent", Source.DEFAULT_USER_AGENT),
                    liveFormat = runCatching { LiveStreamFormat.valueOf(o.optString("liveFormat")) }.getOrDefault(LiveStreamFormat.HLS),
                ),
            )
            added++
        }

        root.optJSONObject("settings")?.let { o ->
            s.setNumbering(o.optBoolean("lcn", true), o.optBoolean("forceCustom", true), o.optBoolean("closeGaps", false))
            if (o.optString("cloudUrl").isNotEmpty()) {
                s.setCloudConfig(o.optString("cloudProvider"), o.optString("cloudUrl"), o.optString("cloudUser"),
                    o.optString("cloudPassword"), o.optString("cloudFolder", "OpenTV"), o.optBoolean("cloudKeepLocal"))
                s.setCloudE2E(o.optBoolean("cloudE2E"), o.optString("cloudE2EPassword"))
            }
            if (o.optString("smbHost").isNotEmpty()) {
                s.setSmbConfig(o.optString("smbHost"), o.optString("smbShare"), o.optString("smbFolder"), o.optString("smbUser"), o.optString("smbPassword"))
                s.setSmbEncrypt(o.optBoolean("smbEncrypt", true))
            }
            s.setSyncWifiOnly(o.optBoolean("syncWifiOnly", true))
            s.setCloudAutoUpload(o.optBoolean("cloudAutoUpload", true))
            s.setCloudLiveSync(o.optBoolean("cloudLiveSync", false))
        }
        root.optJSONObject("vpn")?.let { v ->
            if (v.optString("privateKey").isNotEmpty()) {
                VpnController.get(context).save(
                    app.opentv.vpn.VpnProfile(
                        provider = v.optString("provider"), privateKey = v.optString("privateKey"), address = v.optString("address"),
                        dns = v.optString("dns"), publicKey = v.optString("publicKey"), presharedKey = v.optString("presharedKey"),
                        endpoint = v.optString("endpoint"), allowedIps = v.optString("allowedIps", VpnController.DEFAULT_ALLOWED),
                        onlyThisApp = v.optBoolean("onlyThisApp", true), autoConnect = v.optBoolean("autoConnect"),
                        killSwitch = v.optBoolean("killSwitch"),
                    ),
                )
            }
        }
        // Favourites, lists, numbers, locks, positions: merge now, and again once channels load.
        root.optString("sync").takeIf { it.isNotEmpty() }?.let { bundle ->
            File(context.filesDir, PENDING).writeText(bundle)
            applyPendingIfReady(context)
        }
        added
    }

    /** Merges a parked restore when the channels it refers to exist; then removes it. */
    suspend fun applyPendingIfReady(context: Context) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, PENDING)
        if (!file.exists()) return@withContext
        val graph = ServiceLocator.get(context)
        if (graph.database.channels().countVisible() == 0) return@withContext
        runCatching {
            val engine = SyncEngine(graph)
            engine.apply(engine.decode(file.readText()))
        }
        file.delete()
    }
}
