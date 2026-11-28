package com.example.smsforwarder.license

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * 人读设备码：10 字节随机体 → Base32 16 位 + CRC16 4 位，分 5 组。
 * 10 字节存在本机私有偏好，不进配置备份；换机即原码失效。
 */
object DeviceCode {
    private const val PREF = "device_prefs"
    private const val KEY_BYTES = "device_bytes_b64"

    fun bytes(context: Context): ByteArray {
        val prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.getString(KEY_BYTES, null)?.let { raw ->
            runCatching { Base64.decode(raw, Base64.NO_WRAP) }.getOrNull()?.let {
                if (it.size == 10) return it
            }
        }
        val fresh = ByteArray(10).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_BYTES, Base64.encodeToString(fresh, Base64.NO_WRAP)).apply()
        return fresh
    }

    /** 10 字节 → 5 组人读串（连字符分组，用于展示与复制） */
    fun format(device: ByteArray): String {
        val body = Crockford.encode(device)
        val crc = Crc16.compute(device)
        val crcBytes = byteArrayOf(((crc shr 8) and 0xFF).toByte(), (crc and 0xFF).toByte())
        val crcStr = Crockford.encode(crcBytes)
        return body.substring(0, 4) + "-" + body.substring(4, 8) + "-" +
            body.substring(8, 12) + "-" + body.substring(12, 16) + "-" + crcStr
    }

    /** 展示用空格分组 */
    fun human(device: ByteArray): String = format(device).replace('-', ' ')

    fun parse(human: String?): ByteArray? {
        if (human == null) return null
        val clean = human.uppercase().filter { it.isLetterOrDigit() }
        if (clean.length != 20) return null
        val dc = Crockford.decode(clean.substring(0, 16)) ?: return null
        if (dc.size != 10) return null
        val crcBytes = Crockford.decode(clean.substring(16)) ?: return null
        if (crcBytes.size != 2) return null
        val expect = Crc16.compute(dc)
        val got = ((crcBytes[0].toInt() and 0xFF) shl 8) or (crcBytes[1].toInt() and 0xFF)
        return if (expect == got) dc else null
    }
}
