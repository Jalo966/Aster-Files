package com.aster.files.security

import com.android.apksig.ApkSigner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 纯 Java APK 重签名核心：剥离旧签名后用新 keystore 生成 v1/v2 签名。 */
object ApkSigningEngine {

    fun sign(
        inputApk: File,
        outputApk: File,
        keystoreFile: File,
        storePassword: CharArray,
        alias: String,
        keyPassword: CharArray
    ) {
        require(inputApk.isFile) { "APK 不存在：" + inputApk.path }
        require(keystoreFile.isFile) { "keystore 不存在：" + keystoreFile.path }
        val keyStore = loadKeyStore(keystoreFile, storePassword)
        val cleanAlias = alias.trim()
        require(cleanAlias.isNotEmpty()) { "Key Alias 不能为空" }
        require(keyStore.containsAlias(cleanAlias)) { "Keystore 中不存在别名：" + cleanAlias }
        val key = keyStore.getKey(cleanAlias, keyPassword) as? PrivateKey
            ?: throw IllegalArgumentException("别名不是私钥条目")
        val chain = keyStore.getCertificateChain(cleanAlias)
            ?.map { it as X509Certificate }
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("别名缺少证书链")
        val config = ApkSigner.SignerConfig.Builder(cleanAlias, key, chain).build()
        val unsigned = File.createTempFile("aster-unsigned-", ".apk", outputApk.parentFile)
        try {
            stripOldSignatures(inputApk, unsigned)
            if (outputApk.exists()) outputApk.delete()
            ApkSigner.Builder(listOf(config))
                .setInputApk(unsigned)
                .setOutputApk(outputApk)
                .setMinSdkVersion(23)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build()
                .sign()
        } finally {
            unsigned.delete()
        }
    }

    private fun loadKeyStore(file: File, password: CharArray): KeyStore {
        var last: Exception? = null
        for (type in listOf("JKS", "PKCS12")) {
            try {
                val store = KeyStore.getInstance(type)
                FileInputStream(file).use { store.load(it, password) }
                return store
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalArgumentException(
            "无法读取 keystore：" + (last?.message ?: "密码错误或格式不支持")
        )
    }

    private fun stripOldSignatures(input: File, output: File) {
        ZipInputStream(FileInputStream(input)).use { zip ->
            ZipOutputStream(FileOutputStream(output)).use { out ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (isOldSignature(entry.name)) {
                        zip.closeEntry()
                        continue
                    }
                    val target = ZipEntry(entry.name)
                    target.time = entry.time
                    out.putNextEntry(target)
                    zip.copyTo(out)
                    out.closeEntry()
                    zip.closeEntry()
                }
            }
        }
    }

    private fun isOldSignature(name: String): Boolean {
        val upper = name.uppercase()
        if (!upper.startsWith("META-INF/")) return false
        if (upper == "META-INF/MANIFEST.MF") return true
        return upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC")
    }
}
