package com.example.smsforwarder.license

/** Crockford Base32：字母表不含 I/L/O/U；解码宽松接受 I/L→1、O→0。 */
object Crockford {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val DECODE = IntArray(128).also { m ->
        repeat(128) { m[it] = -1 }
        ALPHABET.forEachIndexed { i, c -> m[c.code] = i }
        m['I'.code] = 1
        m['L'.code] = 1
        m['O'.code] = 0
    }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buf = 0
        var bits = 0
        for (b in bytes) {
            buf = (buf shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buf shr bits) and 0x1F])
            }
            buf = if (bits > 0) buf and ((1 shl bits) - 1) else 0
        }
        if (bits > 0) sb.append(ALPHABET[(buf shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    fun decode(s: String): ByteArray? {
        if (s.isEmpty()) return null
        val byteLen = s.length * 5 / 8
        if (byteLen == 0) return null
        val out = ByteArray(byteLen)
        var buf = 0
        var bits = 0
        var idx = 0
        for (c in s) {
            val v = if (c.code < 128) DECODE[c.code] else -1
            if (v < 0) return null
            buf = (buf shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                if (idx < byteLen) out[idx++] = ((buf shr bits) and 0xFF).toByte()
            }
            buf = if (bits > 0) buf and ((1 shl bits) - 1) else 0
        }
        return if (idx == byteLen) out else null
    }
}
