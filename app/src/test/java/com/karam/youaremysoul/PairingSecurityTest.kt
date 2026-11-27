package com.karam.youaremysoul

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSecurityTest {
    @Test
    fun validatesJoinRequestAndMemberLimit() {
        assertNull(PairingSecurity.validateJoinRequest("123456789", "987654321", "Karam", 1))
        assertNotNull(PairingSecurity.validateJoinRequest("123", "987654321", "Karam", 1))
        assertNotNull(PairingSecurity.validateJoinRequest("123456789", "123456789", "Karam", 1))
        assertNotNull(PairingSecurity.validateJoinRequest("123456789", "987654321", "Karam", 5))
        assertNotNull(PairingSecurity.validateJoinRequest("123456789", "987654321", "Bad\nName", 1))
        assertFalse(PairingSecurity.isValidDeviceCode("١٢٣٤٥٦٧٨٩"))
    }

    @Test
    fun requesterInputValidationDoesNotDependOnLocalWorldCapacity() {
        assertNull(PairingSecurity.validateJoinRequestInput("123456789", "987654321", "Guest"))
        assertNotNull(PairingSecurity.validateJoinRequestInput("123", "987654321", "Guest"))
        assertNotNull(PairingSecurity.validateJoinRequestInput("123456789", "123456789", "Guest"))
        assertNotNull(PairingSecurity.validateJoinRequestInput("123456789", "987654321", "Bad\nName"))
    }

    @Test
    fun pairingSecretHasOneCanonicalUnpaddedEncoding() {
        val secret = PairingSecurity.newPairingSecret()
        assertEquals(43, secret.length)
        assertTrue(runCatching {
            PairingSecurity.createProof(
                PairingChallenge("request-id-canonical", "123456789", "987654321", "nonce", 10L),
                "$secret="
            )
        }.isFailure)

        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val last = alphabet.indexOf(secret.last())
        val alternate = secret.dropLast(1) + alphabet[last xor 1]
        assertTrue(runCatching {
            PairingSecurity.createProof(
                PairingChallenge("request-id-canonical", "123456789", "987654321", "nonce", 10L),
                alternate
            )
        }.isFailure)
    }

    @Test
    fun challengeProofIsValidOnlyOnce() {
        var now = 1_000_000L
        val verifier = PairingChallengeVerifier { now }
        val secret = PairingSecurity.newPairingSecret()
        val challenge = verifier.issue("request-id-123456", "123456789", "987654321")
        val proof = PairingSecurity.createProof(challenge, secret)

        assertTrue(verifier.verify(challenge, proof, secret))
        assertFalse(verifier.verify(challenge, proof, secret))
    }

    @Test
    fun expiredChallengeIsRejected() {
        var now = 10_000L
        val verifier = PairingChallengeVerifier { now }
        val secret = PairingSecurity.newPairingSecret()
        val challenge = verifier.issue("request-id-expired", "123456789", "987654321", 10_000L)
        val proof = PairingSecurity.createProof(challenge, secret)

        now += 10_001L
        assertFalse(verifier.verify(challenge, proof, secret))
    }

    @Test
    fun challengeExpiresAtExactDeadline() {
        var now = 40_000L
        val verifier = PairingChallengeVerifier { now }
        val secret = PairingSecurity.newPairingSecret()
        val challenge = verifier.issue("request-id-exact-expiry", "123456789", "987654321")
        val proof = PairingSecurity.createProof(challenge, secret)

        now = challenge.expiresAtEpochMs
        assertFalse(verifier.verify(challenge, proof, secret))
    }

    @Test
    fun requestIdAndExpiryOverflowAreRejected() {
        assertTrue(runCatching {
            PairingSecurity.newChallenge("invalid request id!", "123456789", "987654321", 100L)
        }.isFailure)
        assertTrue(runCatching {
            PairingSecurity.newChallenge("request-id-عربي", "123456789", "987654321", 100L)
        }.isFailure)
        assertTrue(runCatching {
            PairingSecurity.newChallenge("request-id-overflow", "123456789", "987654321", Long.MAX_VALUE - 5L)
        }.isFailure)
    }

    @Test
    fun alteredChallengeAndTamperedProofAreRejected() {
        val verifier = PairingChallengeVerifier { 80_000L }
        val secret = PairingSecurity.newPairingSecret()
        val challenge = verifier.issue("request-id-tamper-check", "123456789", "987654321")
        val validProof = PairingSecurity.createProof(challenge, secret)

        val altered = challenge.copy(hostDeviceCode = "111111111")
        assertFalse(verifier.verify(altered, validProof, secret))

        val verifier2 = PairingChallengeVerifier { 80_000L }
        val challenge2 = verifier2.issue("request-id-proof-tamper", "123456789", "987654321")
        val proof2 = PairingSecurity.createProof(challenge2, secret).copy(signature = "AAAA")
        assertFalse(verifier2.verify(challenge2, proof2, secret))
    }

    @Test
    fun wrongSecretIsRejectedAndChallengeConsumed() {
        val verifier = PairingChallengeVerifier { 50_000L }
        val challenge = verifier.issue("request-id-wrong-secret", "123456789", "987654321")
        val proof = PairingSecurity.createProof(challenge, PairingSecurity.newPairingSecret())

        assertFalse(verifier.verify(challenge, proof, PairingSecurity.newPairingSecret()))
        assertFalse(verifier.verify(challenge, proof, PairingSecurity.newPairingSecret()))
    }


    @Test
    fun pendingChallengeQueueHasHardLimit() {
        val verifier = PairingChallengeVerifier { 100_000L }
        repeat(128) { index ->
            verifier.issue("request-id-test-${index.toString().padStart(4, '0')}", "123456789", "987654321")
        }
        val rejected = runCatching { verifier.issue("request-id-over-limit", "123456789", "987654321") }.isFailure
        assertTrue(rejected)
    }

    @Test
    fun attemptsAreThrottled() {
        var now = 1_000L
        val limiter = PairingAttemptLimiter(maxAttempts = 2, windowMs = 10_000L) { now }

        assertTrue(limiter.allow("123456789"))
        assertTrue(limiter.allow("123456789"))
        assertFalse(limiter.allow("123456789"))
        now += 10_001L
        assertTrue(limiter.allow("123456789"))
    }
}
