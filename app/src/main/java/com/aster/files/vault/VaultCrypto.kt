package com.aster.files.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Vault 文件内容 AES-256-GCM 加解密；密钥保存在 Android Keystore。 */
class VaultCrypto {
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun encrypt(source: File, target: File) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                output.write(MAGIC)
                output.write(cipher.iv)
                CipherOutputStream(output, cipher).use { encrypted ->
                    input.copyTo(encrypted, 64 * 1024)
                }
            }
        }
    }

    fun decrypt(source: File, target: File) {
        FileInputStream(source).use { input ->
            val magic = ByteArray(MAGIC.size)
            if (input.read(magic) != MAGIC.size || !magic.contentEquals(MAGIC)) {
                throw IOException("不是 Aster Vault 密文")
            }
            val iv = ByteArray(IV_BYTES)
            if (input.read(iv) != IV_BYTES) throw IOException("Vault 密文头损坏")
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            }
            FileOutputStream(target).use { output ->
                CipherInputStream(input, cipher).use { decrypted ->
                    decrypted.copyTo(output, 64 * 1024)
                }
            }
        }
    }

    fun decryptText(source: File): String {
        val temp = File.createTempFile("vault-text-", ".tmp")
        return try {
            decrypt(source, temp)
            temp.readText()
        } finally {
            temp.delete()
        }
    }

    private fun getOrCreateKey(): SecretKey {
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
        const val KEY_ALIAS = "aster_vault_files"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        val MAGIC = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 1)
    }
}
