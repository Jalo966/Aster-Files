package com.aster.files.security

import com.android.apksig.ApkVerifier
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkSigningEngineTest {

    private fun resource(name: String, suffix: String): File {
        val file = File.createTempFile("signing-test-", suffix)
        javaClass.getResourceAsStream(name)!!.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    @Test
    fun signsApkAndProducesVerifiedV1V2Signature() {
        val keystore = resource("/test-signing.jks", ".jks")
        val input = resource("/fixture.apk", ".apk")
        val output = File.createTempFile("signed-test-", ".apk")
        try {
            ApkSigningEngine.sign(
                inputApk = input,
                outputApk = output,
                keystoreFile = keystore,
                storePassword = "changeit".toCharArray(),
                alias = "testkey",
                keyPassword = "changeit".toCharArray()
            )
            assertTrue(output.length() > 0)
            val result = ApkVerifier.Builder(output).build().verify()
            assertTrue(result.isVerified)
            assertTrue(result.isVerifiedUsingV1Scheme)
            assertTrue(result.isVerifiedUsingV2Scheme)
        } finally {
            keystore.delete()
            input.delete()
            output.delete()
        }
    }

    @Test
    fun rejectsUnknownAlias() {
        val keystore = resource("/test-signing.jks", ".jks")
        val input = resource("/fixture.apk", ".apk")
        val output = File.createTempFile("signed-invalid-", ".apk")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ApkSigningEngine.sign(
                    inputApk = input,
                    outputApk = output,
                    keystoreFile = keystore,
                    storePassword = "changeit".toCharArray(),
                    alias = "missing",
                    keyPassword = "changeit".toCharArray()
                )
            }
        } finally {
            keystore.delete()
            input.delete()
            output.delete()
        }
    }
}
