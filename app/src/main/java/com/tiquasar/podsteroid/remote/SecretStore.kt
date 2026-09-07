/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Secure storage for remote-server secrets (SSH passwords, private keys,
 * passphrases, Tailscale auth keys). Backed by the Android Keystore with
 * AES/GCM; the app holds only opaque Base64 ciphertext tokens, never the
 * plaintext in DataStore.
 */
package com.tiquasar.podsteroid.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecretStore @Inject constructor() {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun ensureKey(alias: String): SecretKey {
        val existing = keyStore.getEntry(alias, null)
        if (existing is KeyStore.SecretKeyEntry) return existing.secretKey
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey()
    }

    /**
     * Encrypts [plaintext] and returns a token of the form
     * "base64(iv):base64(ciphertext)". Useful for DataStore string values.
     */
    fun encrypt(alias: String, plaintext: String): String {
        val key = ensureKey(alias)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val enc = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "${iv.toB64()}:${enc.toB64()}"
    }

    fun decrypt(alias: String, token: String): String {
        val (ivB64, ctB64) = token.split(':', limit = 2)
        val key = ensureKey(alias)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, ivB64.fromB64()),
        )
        return String(cipher.doFinal(ctB64.fromB64()), Charsets.UTF_8)
    }

    /** Removes a stored key (e.g. when a server is deleted). */
    fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun ByteArray.toB64(): String = android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
    private fun String.fromB64(): ByteArray = android.util.Base64.decode(this, android.util.Base64.NO_WRAP)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
