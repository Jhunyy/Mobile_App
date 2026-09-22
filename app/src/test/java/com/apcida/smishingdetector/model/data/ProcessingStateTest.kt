package com.apcida.smishingdetector.model.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingStateTest {

    @Test
    fun normalLifecycleTransitionsAreAllowed() {
        assertTrue(ProcessingState.PENDING.canTransitionTo(ProcessingState.RULE_ANALYZED))
        assertTrue(ProcessingState.RULE_ANALYZED.canTransitionTo(ProcessingState.AI_QUEUED))
        assertTrue(ProcessingState.AI_QUEUED.canTransitionTo(ProcessingState.AI_ANALYZING))
        assertTrue(ProcessingState.AI_ANALYZING.canTransitionTo(ProcessingState.COMPLETED))
        assertTrue(ProcessingState.AI_ANALYZING.canTransitionTo(ProcessingState.AI_FAILED))
        assertTrue(ProcessingState.AI_FAILED.canTransitionTo(ProcessingState.AI_ANALYZING))
    }

    @Test
    fun completedMessagesCannotReenterProcessing() {
        assertFalse(ProcessingState.COMPLETED.canTransitionTo(ProcessingState.AI_ANALYZING))
    }
}
