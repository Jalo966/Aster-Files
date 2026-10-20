package com.aster.files.ui.components

import android.content.Context
import android.util.Base64

data class ApkSigningConfig(
    val keystoreUri: String = "",
    val keystorePath: String = "",
    val storePassword: String = "",
    val keyAlias: String = "",
    val keyPassword: String = ""
)

/** APK 签名配置存储；真实签名引擎在 V3.4.0 接入。 */
class ApkSigningConfigStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "aster_apk_signing",
        Context.MODE_PRIVATE
    )

    fun load(): ApkSigningConfig = ApkSigningConfig(
        keystoreUri = preferences.getString(KEY_URI, "").orEmpty(),
        keystorePath = preferences.getString(KEY_PATH, "").orEmpty(),
        storePassword = decode(preferences.getString(KEY_STORE, "").orEmpty()),
        keyAlias = preferences.getString(KEY_ALIAS, "").orEmpty(),
        keyPassword = decode(preferences.getString(KEY_KEY, "").orEmpty())
    )

    fun save(config: ApkSigningConfig) {
        preferences.edit()
            .putString(KEY_URI, config.keystoreUri)
            .putString(KEY_PATH, config.keystorePath)
            .putString(KEY_STORE, encode(config.storePassword))
            .putString(KEY_ALIAS, config.keyAlias)
            .putString(KEY_KEY, encode(config.keyPassword))
            .apply()
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decode(value: String): String = runCatching {
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
    }.getOrDefault("")

    private companion object {
        const val KEY_URI = "keystore_uri"
        const val KEY_PATH = "keystore_path"
        const val KEY_STORE = "store_password"
        const val KEY_ALIAS = "key_alias"
        const val KEY_KEY = "key_password"
    }
}
