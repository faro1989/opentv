/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encryption at rest for everything secret OpenTV keeps on the device.
 *
 * A 256-bit AES key is generated inside the Android Keystore — on most boxes and phones in the
 * hardware-backed keystore — and never leaves it: the app can ask it to encrypt and decrypt, but
 * cannot read the key out. With it we protect:
 *  - single secrets in preferences (VPN private key, cloud / NAS passwords …) via [put] / [get],
 *    stored as `enc1:<base64(iv | ciphertext+tag)>` (AES-256-GCM, fresh 12-byte IV each time);
 *  - the random passphrase of the SQLCipher-encrypted database ([databasePassphrase]), which holds
 *    the provider logins AND every stream URL (Xtream URLs carry user + password in the path).
 *
 * Older plaintext values are read once and rewritten encrypted ([get] migrates transparently).
 */
object SecretStore {
    private const val TAG = "SecretStore"
    private const val KEY_ALIAS = "opentv_master_v1"
    private const val PREFIX = "enc1:"
    private const val GCM_TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, raw, 0, 12))
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    /** Reads a secret; a legacy plaintext value is re-saved encrypted on the way through. */
    fun get(prefs: SharedPreferences, name: String, default: String = ""): String {
        val stored = prefs.getString(name, null) ?: return default
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) {
            runCatching { prefs.edit().putString(name, encrypt(stored)).apply() }
            return stored
        }
        return runCatching { decrypt(stored) }.getOrElse {
            // Keystore key gone (factory reset of credentials, restored backup): the secret is
            // unrecoverable by design. Treat as unset rather than crash.
            Log.w(TAG, "Could not decrypt $name", it)
            default
        }
    }

    fun put(editor: SharedPreferences.Editor, name: String, value: String): SharedPreferences.Editor =
        editor.putString(name, if (value.isEmpty()) "" else encrypt(value))

    /**
     * The database passphrase: 32 random bytes as hex, created once and kept only encrypted
     * under the Keystore key.
     */
    fun databasePassphrase(context: Context): String {
        val prefs = context.getSharedPreferences("opentv_secure", Context.MODE_PRIVATE)
        get(prefs, "db_passphrase").takeIf { it.isNotEmpty() }?.let { return it }
        // A new passphrase means any existing *encrypted* database (e.g. restored onto a new device
        // without its Keystore key) can never be opened again — drop it so the app starts cleanly.
        val db = context.getDatabasePath("opentv.db")
        if (db.exists() && !isPlainSqlite(db)) {
            listOf("", "-wal", "-shm", "-journal").forEach { File(db.path + it).delete() }
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        put(prefs.edit(), "db_passphrase", hex).commit()
        return hex
    }

    /** True if [file] is a plain (unencrypted) SQLite database — its header says so in clear text. */
    fun isPlainSqlite(file: File): Boolean = runCatching {
        file.exists() && file.length() >= 16 && file.inputStream().use { input ->
            val header = ByteArray(16)
            input.read(header) == 16 && String(header, Charsets.US_ASCII).startsWith("SQLite format 3")
        }
    }.getOrDefault(false)
}
