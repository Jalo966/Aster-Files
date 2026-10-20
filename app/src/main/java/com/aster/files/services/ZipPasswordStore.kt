package com.aster.files.services

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** ZIP 密码仓库抽象，便于 JVM 测试与替换。 */
interface ZipPasswordStore {
    suspend fun save(zipPath: String, password: String)
    suspend fun find(zipPath: String): String?
}

/**
 * 使用 Android Keystore AES-256-GCM 加密密码，再存入 SharedPreferences。
 * 键名为 ZIP 绝对路径的 SHA-256；密文中同时包含规范化路径，防止碰撞/串用。
 */
class EncryptedZipPasswordStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ZipPasswordStore {

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun save(zipPath: String, password: String) = withContext(ioDispatcher) {
        if (password.isBlank()) return@withContext
        val normalized = normalize(zipPath)
        val plain = normalized + "\n" + password
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        preferences.edit()
            .putString(keyFor(normalized), Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    override suspend fun find(zipPath: String): String? = withContext(ioDispatcher) {
        val normalized = normalize(zipPath)
        val encoded = preferences.getString(keyFor(normalized), null) ?: return@withContext null
        try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= IV_BYTES) return@withContext null
            val iv = payload.copyOfRange(0, IV_BYTES)
            val cipherText = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, iv)
            )
            val plain = String(cipher.doFinal(cipherText), Charsets.UTF_8)
            val separator = plain.indexOf('\n')
            if (separator < 0 || plain.substring(0, separator) != normalized) return@withContext null
            plain.substring(separator + 1)
        } catch (_: Exception) {
            null
        }
    }

    private fun normalize(path: String): String =
        try {
            File(path).canonicalPath
        } catch (_: Exception) {
            File(path).absolutePath
        }

    private fun keyFor(normalizedPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedPath.toByteArray(Charsets.UTF_8))
        return "zip_" + Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "aster_zip_passwords"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFERENCES_NAME = "aster_zip_passwords"
        const val IV_BYTES = 12
    }
}
