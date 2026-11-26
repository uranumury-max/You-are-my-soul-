package com.karam.youaremysoul

import java.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Transport-independent pairing primitives.
 *
 * IMPORTANT: the nine-digit device code is only a discovery identifier.
 * The pairing secret and challenge must be exchanged over an authenticated,
 * encrypted transport (for example, a TLS channel with certificate pinning).
 * Do not call these functions over raw LAN sockets or cleartext HTTP.
 */
data class PairingChallenge(
    val requestId: String,
    val fromDeviceCode: String,
    val hostDeviceCode: String,
    val nonce: String,
    val expiresAtEpochMs: Long
)

data class PairingProof(
    val requestId: String,
    val signature: String
)

object PairingSecurity {
    private const val SECRET_BYTES = 32
    private const val NONCE_BYTES = 32
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private val random = SecureRandom()

    fun isValidDeviceCode(code: String): Boolean =
        code.length == WorldLimits.DEVICE_CODE_DIGITS && code.all { it in '0'..'9' }

    fun validateJoinRequest(
        fromCode: String,
        hostCode: String,
        displayName: String,
        currentMemberCount: Int
    ): String? {
        if (!isValidDeviceCode(fromCode) || !isValidDeviceCode(hostCode)) {
            return "رمز الجهاز يجب أن يتكون من 9 أرقام."
        }
        if (fromCode == hostCode) return "لا يمكن ربط الجهاز بنفسه."
        if (displayName.isBlank() || displayName.length > 24 || displayName.any(Char::isISOControl)) {
            return "اسم المشارك مطلوب وبحد أقصى 24 حرفاً."
        }
        if (currentMemberCount !in 1 until WorldLimits.MAX_MEMBERS) {
            return "العالم ممتلئ أو لم يتم إنشاؤه بشكل صحيح."
        }
        return null
    }

    /** Generate a one-time 256-bit secret. Deliver it only inside authenticated TLS. */
    fun newPairingSecret(): String = randomBytes(SECRET_BYTES).toUrlSafeBase64()

    fun newChallenge(
        requestId: String,
        fromDeviceCode: String,
        hostDeviceCode: String,
        nowEpochMs: Long,
        lifetimeMs: Long = 120_000L
    ): PairingChallenge {
        require(requestId.length in 16..80)
        require(isValidDeviceCode(fromDeviceCode) && isValidDeviceCode(hostDeviceCode))
        require(fromDeviceCode != hostDeviceCode)
        require(lifetimeMs in 10_000L..300_000L)
        return PairingChallenge(
            requestId = requestId,
            fromDeviceCode = fromDeviceCode,
            hostDeviceCode = hostDeviceCode,
            nonce = randomBytes(NONCE_BYTES).toUrlSafeBase64(),
            expiresAtEpochMs = nowEpochMs + lifetimeMs
        )
    }

    fun createProof(challenge: PairingChallenge, pairingSecret: String): PairingProof {
        val secret = decodeSecret(pairingSecret)
        val message = canonicalMessage(challenge)
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret, HMAC_ALGORITHM))
        return PairingProof(challenge.requestId, mac.doFinal(message).toUrlSafeBase64())
    }

    internal fun proofMatches(challenge: PairingChallenge, proof: PairingProof, pairingSecret: String): Boolean {
        if (proof.requestId != challenge.requestId) return false
        val expected = createProof(challenge, pairingSecret).signature
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            proof.signature.toByteArray(Charsets.US_ASCII)
        )
    }

    private fun canonicalMessage(challenge: PairingChallenge): ByteArray =
        "${challenge.requestId}\n${challenge.fromDeviceCode}\n${challenge.hostDeviceCode}\n${challenge.nonce}\n${challenge.expiresAtEpochMs}"
            .toByteArray(Charsets.UTF_8)

    private fun decodeSecret(secret: String): ByteArray {
        val decoded = Base64.getUrlDecoder().decode(secret)
        require(decoded.size == SECRET_BYTES) { "Invalid pairing secret" }
        return decoded
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun ByteArray.toUrlSafeBase64(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)
}

/**
 * Keeps pending challenges single-use. Invalid proofs consume the challenge too,
 * so a caller cannot repeatedly guess signatures against the same nonce.
 */
class PairingChallengeVerifier(private val clock: () -> Long = System::currentTimeMillis) {
    private val pending = ConcurrentHashMap<String, PairingChallenge>()

    @Synchronized
    fun issue(
        requestId: String,
        fromDeviceCode: String,
        hostDeviceCode: String,
        lifetimeMs: Long = 120_000L
    ): PairingChallenge {
        purgeExpired()
        check(pending.size < MAX_PENDING_CHALLENGES) { "Too many pending pairing challenges" }
        val challenge = PairingSecurity.newChallenge(requestId, fromDeviceCode, hostDeviceCode, clock(), lifetimeMs)
        check(pending.putIfAbsent(requestId, challenge) == null) { "Duplicate pairing request" }
        return challenge
    }

    fun verify(challenge: PairingChallenge, proof: PairingProof, pairingSecret: String): Boolean {
        val stored = pending.remove(challenge.requestId) ?: return false
        if (stored != challenge || clock() > stored.expiresAtEpochMs) return false
        return runCatching { PairingSecurity.proofMatches(stored, proof, pairingSecret) }.getOrDefault(false)
    }

    @Synchronized
    fun purgeExpired() {
        val now = clock()
        pending.entries.removeIf { now > it.value.expiresAtEpochMs }
    }

    private companion object {
        const val MAX_PENDING_CHALLENGES = 128
    }
}

/** Simple per-device throttling primitive; production transport must also rate-limit by IP/session. */
class PairingAttemptLimiter(
    private val maxAttempts: Int = 5,
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val attempts = ConcurrentHashMap<String, MutableList<Long>>()

    init {
        require(maxAttempts in 1..20)
        require(windowMs in 1_000L..600_000L)
    }

    @Synchronized
    fun allow(deviceCode: String): Boolean {
        if (!PairingSecurity.isValidDeviceCode(deviceCode)) return false
        val now = clock()
        if (!attempts.containsKey(deviceCode) && attempts.size >= MAX_TRACKED_DEVICES) {
            attempts.entries.removeIf { entry ->
                entry.value.removeAll { now - it >= windowMs || it > now }
                entry.value.isEmpty()
            }
            if (attempts.size >= MAX_TRACKED_DEVICES) return false
        }
        val recent = attempts.getOrPut(deviceCode) { mutableListOf() }
        recent.removeAll { now - it >= windowMs || it > now }
        if (recent.size >= maxAttempts) return false
        recent.add(now)
        return true
    }

    private companion object {
        const val MAX_TRACKED_DEVICES = 512
    }
}
