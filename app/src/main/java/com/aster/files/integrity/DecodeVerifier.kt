package com.aster.files.integrity

import java.io.File

/** L3 解码校验抽象；Android 实现见 AndroidMediaDecodeVerifier，JVM 测试使用 Mock。 */
interface DecodeVerifier {
    /**
     * 真实解码文件，至少解出一帧/一张图。
     *
     * @return true 表示解码成功；false 或抛异常表示解码失败。
     */
    @Throws(DecodeVerificationException::class)
    fun verify(file: File): Boolean
}

class DecodeVerificationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
