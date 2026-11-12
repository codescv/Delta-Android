package com.codescv.delta.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFeedbackEmailSaveTest {
    private class FakeStore {
        val saved = mutableListOf<String>()

        fun persist(email: String): Boolean {
            saved += email
            return true
        }
    }

    @Test
    fun savingValidEmailUpdatesBaselineClearsErrorAndKeepsDialogOpen() {
        val store = FakeStore()

        val result =
            AgentFeedbackHelper.saveEmailFromDialog(
                emailInput = "  agent@example.com  ",
                lastSavedEmail = "old@example.com",
                persist = store::persist,
            )

        assertTrue(result.saved)
        assertEquals("agent@example.com", result.emailInput)
        assertEquals("agent@example.com", result.lastSavedEmail)
        assertFalse(result.emailError)
        assertFalse(result.shouldDismiss)
        assertEquals(listOf("agent@example.com"), store.saved)
        // The "save email" action is hidden again until the email is edited.
        assertFalse(AgentFeedbackHelper.isEmailChanged(result.emailInput, result.lastSavedEmail))
        assertTrue(AgentFeedbackHelper.isEmailChanged("other@example.com", result.lastSavedEmail))
    }

    @Test
    fun savingEmptyEmailClearsTheSavedEmailAndKeepsDialogOpen() {
        val store = FakeStore()

        val result =
            AgentFeedbackHelper.saveEmailFromDialog(
                emailInput = "   ",
                lastSavedEmail = "old@example.com",
                persist = store::persist,
            )

        assertTrue(result.saved)
        assertEquals("", result.emailInput)
        assertEquals("", result.lastSavedEmail)
        assertFalse(result.emailError)
        assertFalse(result.shouldDismiss)
        assertEquals(listOf(""), store.saved)
        assertFalse(AgentFeedbackHelper.isEmailChanged(result.emailInput, result.lastSavedEmail))
    }

    @Test
    fun invalidNonEmptyEmailIsRejectedWithoutSavingOrDismissing() {
        val store = FakeStore()

        val result =
            AgentFeedbackHelper.saveEmailFromDialog(
                emailInput = "agent@",
                lastSavedEmail = "old@example.com",
                persist = store::persist,
            )

        assertFalse(result.saved)
        assertEquals("agent@", result.emailInput)
        assertEquals("old@example.com", result.lastSavedEmail)
        assertTrue(result.emailError)
        assertFalse(result.shouldDismiss)
        assertTrue(store.saved.isEmpty())
        assertTrue(AgentFeedbackHelper.isEmailChanged(result.emailInput, result.lastSavedEmail))
    }

    @Test
    fun failedPersistenceKeepsPreviousBaselineAndReportsError() {
        val result =
            AgentFeedbackHelper.saveEmailFromDialog(
                emailInput = "agent@example.com",
                lastSavedEmail = "old@example.com",
                persist = { false },
            )

        assertFalse(result.saved)
        assertEquals("agent@example.com", result.emailInput)
        assertEquals("old@example.com", result.lastSavedEmail)
        assertTrue(result.emailError)
        assertFalse(result.shouldDismiss)
    }

    @Test
    fun emailChangeDetectionIgnoresSurroundingWhitespace() {
        assertFalse(AgentFeedbackHelper.isEmailChanged(" agent@example.com ", "agent@example.com"))
        assertFalse(AgentFeedbackHelper.isEmailChanged("", ""))
        assertTrue(AgentFeedbackHelper.isEmailChanged("agent@example.com", ""))
    }
}
