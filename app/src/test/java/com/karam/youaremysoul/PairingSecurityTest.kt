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
    }

    @Test
    fun challengeProofIsValidOnlyOnce() {
        var now = 1_000_000L
        val verifier = PairingChallengeVerifier { now }
        val secret = PairingSecurity.newPairingSecret()
        val challenge = verifier.issue("request-id-123456")
        val proof = PairingSecurity.createProof(challenge, secret)

        assertTrue(verifier.verify(challenge, proof, secret))
        assertFalse(verifier.verify(challenge, proof, secret))
    }

    @Test
    fun expiredChallengeIsRejected() {
        var now = 10_000L
        val verifier = PairingChallengeVerifier { now }
        val secret = PairingSecurity.newPairingSecret()
        val challenge = verifier.issue("request-id-expired", 10_000L)
        val proof = PairingSecurity.createProof(challenge, secret)

        now += 10_001L
        assertFalse(verifier.verify(challenge, proof, secret))
    }

    @Test
    fun wrongSecretIsRejectedAndChallengeConsumed() {
        val verifier = PairingChallengeVerifier { 50_000L }
        val challenge = verifier.issue("request-id-wrong-secret")
        val proof = PairingSecurity.createProof(challenge, PairingSecurity.newPairingSecret())

        assertFalse(verifier.verify(challenge, proof, PairingSecurity.newPairingSecret()))
        assertFalse(verifier.verify(challenge, proof, PairingSecurity.newPairingSecret()))
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
