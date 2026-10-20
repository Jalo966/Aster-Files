package com.aster.files.support

import com.aster.files.integrity.DecodeVerifier
import java.io.File

/** JVM 测试用 L3 Mock；可返回 true/false、抛异常或在解码时修改文件。 */
class MockDecodeVerifier(
    var result: Boolean = true,
    var failure: Exception? = null,
    var onVerify: ((File) -> Unit)? = null
) : DecodeVerifier {
    var calls = 0
    val files = ArrayList<File>()

    override fun verify(file: File): Boolean {
        calls++
        files.add(file)
        onVerify?.invoke(file)
        failure?.let { throw it }
        return result
    }
}
