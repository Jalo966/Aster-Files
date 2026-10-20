package com.aster.files.vault

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** 4 位保险箱 PIN：PBKDF2 哈希 + 随机盐，不保存明文。 */
class VaultPinStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "aster_vault_pin",
        Context.MODE_PRIVATE
    )

    fun hasPin(): Boolean = prefs.contains(KEY_SALT) && prefs.contains(KEY_HASH)

    fun setPin(pin: String): Boolean {
        if (!VALID.matches(pin)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
        return true
    }

    fun verify(pin: String): Boolean {
        if (!VALID.matches(pin)) return false
        val salt = prefs.getString(KEY_SALT, null)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        } ?: return false
        val stored = prefs.getString(KEY_HASH, null)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        } ?: return false
        return MessageDigest.isEqual(stored, derive(pin, salt))
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
            .also { spec.clearPassword() }
    }

    private companion object {
        val VALID = Regex("^[0-9]{4}$")
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
        const val ITERATIONS = 120_000
    }
}
