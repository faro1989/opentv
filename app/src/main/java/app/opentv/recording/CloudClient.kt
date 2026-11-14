/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import app.opentv.core.AppSettings
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Where and as whom to talk WebDAV. [baseUrl] is the provider's WebDAV root, e.g. `https://ewebdav.pcloud.com`. */
data class CloudConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    val folder: String,
    /** End-to-end password, or null when E2E encryption is off. */
    val e2ePassword: String? = null,
) {
    /** The E2E key, or null when off. Salted with the account so all the user's devices agree. */
    val e2eKey: javax.crypto.spec.SecretKeySpec?
        get() = e2ePassword?.takeIf { it.isNotEmpty() }?.let { CloudCrypto.key(username, it) }
}

/**
 * A minimal WebDAV client — just what recordings need: check the login, make the folder, upload a
 * finished file, delete one, and build authenticated playback URLs. WebDAV is the one open cloud
 * protocol nearly every storage provider speaks with a plain username + (app) password, so the user
 * signs in right inside OpenTV with no developer keys or third-party OAuth apps involved.
 *
 * ## Locators
 * An uploaded recording is stored as `davs://host/path/file.ts` (https) or `dav://…` (plain http).
 * The scheme is ours, not a real one: it marks the file as "in the cloud" for [RecordingStorage],
 * and the player routes it to an authenticated HTTP source ([CloudDataSource]).
 */
object CloudClient {

    /** One provider shortcut in the login form: its WebDAV root (with `{user}` filled from the login). */
    data class Provider(val id: String, val name: String, val url: String)

    val PROVIDERS = listOf(
        Provider("nextcloud", "Nextcloud / ownCloud", "https://cloud.example.com/remote.php/dav/files/{user}"),
        Provider("magenta", "MagentaCLOUD", "https://magentacloud.de/remote.php/webdav"),
        Provider("pcloud_eu", "pCloud (EU)", "https://ewebdav.pcloud.com"),
        Provider("pcloud_us", "pCloud (US)", "https://webdav.pcloud.com"),
        Provider("koofr", "Koofr", "https://app.koofr.net/dav/Koofr"),
        // Google Drive, Dropbox and OneDrive only offer sign-in through their own OAuth apps. Linked
        // into a (free) Koofr account they appear as WebDAV folders — no developer keys needed.
        Provider("gdrive_koofr", "Google Drive (via Koofr)", "https://app.koofr.net/dav/Google%20Drive"),
        Provider("dropbox_koofr", "Dropbox (via Koofr)", "https://app.koofr.net/dav/Dropbox"),
        Provider("onedrive_koofr", "OneDrive (via Koofr)", "https://app.koofr.net/dav/OneDrive"),
        Provider("hidrive", "STRATO HiDrive", "https://webdav.hidrive.strato.com/users/{user}"),
        Provider("gmx", "GMX Cloud", "https://webdav.mc.gmx.net"),
        Provider("webde", "WEB.DE Online-Speicher", "https://webdav.smartdrive.web.de"),
        Provider("custom", "WebDAV", ""),
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // Recordings are big; a slow uplink must not time a multi-GB PUT out halfway.
            .writeTimeout(30, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
    }

    fun config(settings: AppSettings) = CloudConfig(
        baseUrl = settings.cloudUrl.value,
        username = settings.cloudUser.value,
        password = settings.cloudPassword.value,
        folder = settings.cloudFolder.value,
        e2ePassword = settings.cloudE2EPassword.value.takeIf { settings.cloudE2E.value },
    )

    fun isCloud(locator: String): Boolean = locator.startsWith("davs://") || locator.startsWith("dav://")

    /** `davs://host/a/b.ts` → `https://host/a/b.ts`. */
    fun httpUrlOf(locator: String): String = when {
        locator.startsWith("davs://") -> "https://" + locator.removePrefix("davs://")
        locator.startsWith("dav://") -> "http://" + locator.removePrefix("dav://")
        else -> locator
    }

    fun authHeader(cfg: CloudConfig): String = Credentials.basic(cfg.username, cfg.password, Charsets.UTF_8)

    private fun base(cfg: CloudConfig): HttpUrl =
        cfg.baseUrl.trim().trimEnd('/').replace("{user}", cfg.username.trim()).toHttpUrl()

    private fun folderUrl(cfg: CloudConfig): HttpUrl {
        val b = base(cfg).newBuilder()
        cfg.folder.split('/').filter { it.isNotBlank() }.forEach { b.addPathSegment(it) }
        return b.build()
    }

    /** Signs in (PROPFIND on the root) and makes sure the recordings folder exists. Throws with a readable reason. */
    fun test(cfg: CloudConfig) {
        val propfind = Request.Builder()
            .url(base(cfg))
            .header("Authorization", authHeader(cfg))
            .header("Depth", "0")
            .method("PROPFIND", "".toRequestBody("application/xml".toMediaType()))
            .build()
        client.newCall(propfind).execute().use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw IOException("Anmeldung abgelehnt (${r.code}) – Benutzer/Passwort bzw. App-Passwort prüfen")
                !r.isSuccessful -> throw IOException("Server antwortet mit ${r.code}")
            }
        }
        ensureFolder(cfg)
    }

    /** MKCOL each level of the folder path; "already exists" (405) is fine. */
    private fun ensureFolder(cfg: CloudConfig) {
        val b = base(cfg).newBuilder()
        for (segment in cfg.folder.split('/').filter { it.isNotBlank() }) {
            b.addPathSegment(segment)
            val req = Request.Builder()
                .url(b.build())
                .header("Authorization", authHeader(cfg))
                .method("MKCOL", null)
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful && r.code != 405 && r.code != 301) throw IOException("Ordner anlegen fehlgeschlagen (${r.code})")
            }
        }
    }

    /** Uploads a finished recording and returns its cloud locator. */
    fun upload(cfg: CloudConfig, file: File, name: String = file.name): String {
        ensureFolder(cfg)
        val key = cfg.e2eKey
        // With E2E on, the file is encrypted on the fly while it uploads and named *.otve.
        val remoteName = if (key != null) name + CloudCrypto.SUFFIX else name
        val url = folderUrl(cfg).newBuilder().addPathSegment(remoteName).build()
        val body = if (key != null) CloudCrypto.encryptingBody(file, key) else file.asRequestBody("video/mp2t".toMediaType())
        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader(cfg))
            .put(body)
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Upload fehlgeschlagen (${r.code})")
        }
        val text = url.toString()
        return if (text.startsWith("https://")) "davs://" + text.removePrefix("https://") else "dav://" + text.removePrefix("http://")
    }

    // ---- Small text files, for cross-device sync bundles -------------------------------------

    private fun syncUrl(cfg: CloudConfig, name: String? = null): HttpUrl {
        val b = folderUrl(cfg).newBuilder().addPathSegment("sync")
        if (name != null) b.addPathSegment(name)
        return b.build()
    }

    fun writeSyncFile(cfg: CloudConfig, name: String, text: String) {
        ensureFolder(cfg.copy(folder = cfg.folder.trimEnd('/') + "/sync"))
        val payload = cfg.e2eKey?.let { CloudCrypto.encryptText(text, it) } ?: text
        val req = Request.Builder()
            .url(syncUrl(cfg, name))
            .header("Authorization", authHeader(cfg))
            .put(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { r -> if (!r.isSuccessful) throw IOException("Sync-Upload fehlgeschlagen (${r.code})") }
    }

    fun readSyncFile(cfg: CloudConfig, name: String): String {
        val req = Request.Builder().url(syncUrl(cfg, name)).header("Authorization", authHeader(cfg)).get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val text = r.body?.string().orEmpty()
            if (!CloudCrypto.isEncryptedText(text)) return text
            val key = cfg.e2eKey ?: throw IOException("Verschlüsselte Sync-Daten – E2E-Passwort fehlt")
            return runCatching { CloudCrypto.decryptText(text, key) }
                .getOrElse { throw IOException("Falsches E2E-Passwort") }
        }
    }

    /** File names in the sync folder (PROPFIND depth 1, hrefs read straight off the multistatus XML). */
    fun listSyncFiles(cfg: CloudConfig): List<String> {
        val req = Request.Builder()
            .url(syncUrl(cfg).newBuilder().addPathSegment("").build())
            .header("Authorization", authHeader(cfg))
            .header("Depth", "1")
            .method("PROPFIND", "".toRequestBody("application/xml".toMediaType()))
            .build()
        val xml = client.newCall(req).execute().use { r ->
            if (r.code == 404) return emptyList()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        return Regex("<(?:[a-zA-Z]+:)?href>([^<]+)</(?:[a-zA-Z]+:)?href>").findAll(xml)
            .map { java.net.URLDecoder.decode(it.groupValues[1].trimEnd('/').substringAfterLast('/'), "UTF-8") }
            .filter { it.endsWith(".json", ignoreCase = true) }
            .distinct()
            .toList()
    }

    fun delete(cfg: CloudConfig, locator: String) {
        val req = Request.Builder()
            .url(httpUrlOf(locator))
            .header("Authorization", authHeader(cfg))
            .delete()
            .build()
        client.newCall(req).execute().close()
    }

    /** "pCloud (EU) · /OpenTV" — for the recordings list and settings. */
    fun describe(settings: AppSettings, locator: String): String {
        val provider = PROVIDERS.firstOrNull { it.id == settings.cloudProvider.value }?.name ?: "Cloud"
        val path = runCatching { httpUrlOf(locator).toHttpUrl().encodedPath }.getOrDefault("")
        val folder = path.substringBeforeLast('/').let { java.net.URLDecoder.decode(it, "UTF-8") }
        return "$provider · ${folder.takeLast(60)}"
    }
}
