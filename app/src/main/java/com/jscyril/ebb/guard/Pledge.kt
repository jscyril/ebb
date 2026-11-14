package com.jscyril.ebb.guard

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class PledgeRecord(val endsAt: Long, val salt: ByteArray, val hash: ByteArray) {
    override fun equals(other: Any?) = other is PledgeRecord && endsAt == other.endsAt &&
        salt.contentEquals(other.salt) && hash.contentEquals(other.hash)
    override fun hashCode() = 31 * (31 * endsAt.hashCode() + salt.contentHashCode()) + hash.contentHashCode()
}

object Pledge {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val LEN = 24
    private val rng = SecureRandom()

    private fun hash(passcode: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passcode.toCharArray(), salt, 120_000, 256)).encoded

    /** Returns the record to store and the passcode, which is shown once and never stored. */
    fun create(days: Int, trustedNow: Long): Pair<PledgeRecord, String> {
        require(days in setOf(7, 30, 90)) { "days must be 7, 30 or 90" }
        val code = String(CharArray(LEN) { ALPHABET[rng.nextInt(ALPHABET.length)] })
        val salt = ByteArray(16).also(rng::nextBytes)
        return PledgeRecord(trustedNow + days * 86_400_000L, salt, hash(code, salt)) to code
    }

    fun isActive(r: PledgeRecord?, trustedNow: Long) = r != null && trustedNow < r.endsAt

    fun verify(r: PledgeRecord, passcode: String): Boolean {
        val norm = passcode.filterNot { it.isWhitespace() || it == '-' }.uppercase()
        return MessageDigest.isEqual(hash(norm, r.salt), r.hash)
    }
}
