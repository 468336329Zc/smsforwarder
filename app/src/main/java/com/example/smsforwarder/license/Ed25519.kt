package com.example.smsforwarder.license

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Ed25519 验签（客户端只验签，不持有私钥）。 */
object Ed25519 {

    fun verify(payload: ByteArray, sig: ByteArray, pub: ByteArray): Boolean {
        if (pub.size != 32 || sig.size != 64) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(pub, 0))
            verifier.update(payload, 0, payload.size)
            verifier.verifySignature(sig)
        } catch (_: Throwable) {
            false
        }
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace("[^0-9a-fA-F]".toRegex(), "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) +
                Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
