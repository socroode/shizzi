package dev.shizzi.admin

import org.junit.Assert.*
import org.junit.Test

class InterphoneSessionTest {
    @Test fun permissionGrantResumesThePendingConversation() {
        val s = InterphoneSession()
        val attempt = s.request("123", false, 100)!!
        assertEquals(InterphoneSession.Phase.PERMISSION, s.state.phase)
        assertFalse(s.streaming(attempt.generation))
        assertTrue(s.permit(attempt.generation, 200))
        assertTrue(s.streaming(attempt.generation))
        assertEquals(200, s.state.startedRouterUs)
    }
    @Test fun stopDuringPermissionPromptCannotStartRecordingAfterGrant() {
        val s = InterphoneSession()
        val attempt = s.request("123", false, 100)!!
        s.stop()
        assertFalse(s.permit(attempt.generation, 200))
        assertEquals(InterphoneSession.Phase.OFF, s.state.phase)
    }
    @Test fun earlierWorkerCannotMuteOrFailANewConversation() {
        val s = InterphoneSession()
        val old = s.request("123", true, 100)!!
        s.stop()
        val current = s.request("456", true, 200)!!
        assertFalse(s.fail(old.generation, "old failure"))
        s.observe(old.generation, 300, 300, true)
        assertTrue(s.streaming(current.generation))
        assertEquals("456", s.state.target)
        assertEquals(InterphoneSession.Phase.CONNECTING, s.state.phase)
    }
    @Test fun acceptanceAndPlaybackMustBelongToTheNewConversation() {
        val s = InterphoneSession()
        val attempt = s.request("123", true, 2_000)!!
        s.observe(attempt.generation, 1_999, 3_000, true)
        assertEquals(InterphoneSession.Phase.CONNECTING, s.state.phase)
        s.observe(attempt.generation, 2_100, 1_999, true)
        assertEquals(InterphoneSession.Phase.ACCEPTED, s.state.phase)
        assertFalse(s.state.playbackConfirmed)
        s.observe(attempt.generation, 2_200, 2_150, true)
        assertTrue(s.state.playbackConfirmed)
    }
    @Test fun failureRemainsVisibleUntilExplicitRetryOrStop() {
        val s = InterphoneSession()
        val first = s.request("123", true, 100)!!
        assertTrue(s.fail(first.generation, "capture impossible"))
        assertEquals(InterphoneSession.Phase.FAILED, s.state.phase)
        assertFalse(s.streaming(first.generation))
        assertNotNull(s.request("123", true, 200))
        s.stop()
        assertEquals(InterphoneSession.Phase.OFF, s.state.phase)
    }
    @Test fun missingAcceptanceAndInterruptedTransmissionCannotStayGreen() {
        val s = InterphoneSession()
        val first = s.request("123", true, 1_000)!!
        assertFalse(s.expire(10_001_000))
        assertTrue(s.expire(10_001_001))
        assertFalse(s.streaming(first.generation))
        val second = s.request("123", true, 20_000_000)!!
        s.observe(second.generation, 21_000_000, 0, false)
        assertFalse(s.expire(26_000_000))
        assertTrue(s.expire(26_000_001))
        assertEquals(InterphoneSession.Phase.FAILED, s.state.phase)
    }
    @Test fun legacyTrafficFromAnEarlierConversationCannotConfirmANewOne() {
        val s = InterphoneSession()
        val attempt = s.request("123", true, 10_000_000)!!
        s.observeLegacy(attempt.generation, 12_999_999, 128.0, 9_999_999, true)
        assertEquals(InterphoneSession.Phase.CONNECTING, s.state.phase)
        s.observeLegacy(attempt.generation, 13_000_000, 0.0, 0, false)
        assertEquals(InterphoneSession.Phase.CONNECTING, s.state.phase)
        s.observeLegacy(attempt.generation, 13_000_000, 128.0, 0, false)
        assertEquals(InterphoneSession.Phase.ACCEPTED, s.state.phase)
        assertFalse(s.state.playbackConfirmed)
    }
    @Test fun freshLegacyPlaybackConfirmsWithoutWaitingForTrafficWindow() {
        val s = InterphoneSession()
        val attempt = s.request("123", true, 10_000_000)!!
        s.observeLegacy(attempt.generation, 10_100_000, 0.0, 10_050_000, true)
        assertEquals(InterphoneSession.Phase.ACCEPTED, s.state.phase)
        assertTrue(s.state.playbackConfirmed)
    }
    @Test fun timeoutExplainsTheLastCompletedStageWithoutClaimingRouterAcceptance() {
        fun failure(captured: Boolean, encoded: Boolean, sent: Boolean, error: String = ""): String {
            val s = InterphoneSession()
            val attempt = s.request("123", true, 1_000)!!
            if(captured)s.captured(attempt.generation, 960)
            if(encoded)s.encoded(attempt.generation)
            if(sent)s.sent(attempt.generation)
            s.confirmationError(attempt.generation,error)
            assertTrue(s.expire(10_001_001))
            assertEquals(InterphoneSession.Phase.FAILED,s.state.phase)
            return s.state.message
        }
        assertTrue(failure(false,false,false).contains("aucune donnée"))
        assertTrue(failure(true,false,false).contains("aucun paquet Opus"))
        assertTrue(failure(true,true,false).contains("envoi au relais bloqué"))
        assertTrue(failure(true,true,true).contains("réception par le relais non confirmée"))
        assertTrue(failure(true,true,true,"HTTP 503").contains("confirmation inaccessible : HTTP 503"))
    }
    @Test fun lateProgressCannotDescribeANewConversation() {
        val s=InterphoneSession()
        val old=s.request("123",true,100)!!
        s.stop()
        s.request("456",true,200)
        s.captured(old.generation,960);s.encoded(old.generation);s.sent(old.generation)
        s.confirmationError(old.generation,"old HTTP failure")
        assertEquals(0L,s.state.capturedSamples)
        assertEquals(0L,s.state.encodedPackets)
        assertEquals(0L,s.state.sentPackets)
        assertEquals("",s.state.confirmationError)
    }
}
