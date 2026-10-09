package org.camylla.proof

import org.junit.Assert.*
import org.junit.Test

class FocusFeedbackTest {
    @Test fun acceptanceAndOldFramesCannotClaimAcquisition() {
        val feedback = FocusFeedback()
        feedback.begin(10, 80.0, 100.0, 300, 400, 0)
        feedback.result(10, 100, true, true, 20)
        assertEquals(FocusFeedback.Phase.ACQUIRING, feedback.visible(20)!!.phase)
        feedback.triggered(10, 101)
        feedback.result(10, 100, true, true, 30)
        feedback.result(9, 102, true, true, 30)
        feedback.result(10, 102, false, true, 30)
        feedback.result(10, 102, true, null, 30)
        assertEquals(FocusFeedback.Phase.ACQUIRING, feedback.visible(30)!!.phase)
        feedback.result(10, 102, true, true, 40)
        assertEquals(FocusFeedback.Phase.SUCCEEDED, feedback.visible(40)!!.phase)
        assertNotNull(feedback.visible(1039)); assertNull(feedback.visible(1040))
    }
    @Test fun repeatedTapAtSamePointRequiresItsOwnTrigger() {
        val feedback = FocusFeedback()
        feedback.begin(10, 80.0, 100.0, 300, 400, 0); feedback.triggered(10, 100)
        feedback.begin(11, 80.0, 100.0, 300, 400, 50)
        feedback.triggered(10, 101); feedback.result(10, 102, true, true, 60)
        assertEquals(FocusFeedback.Phase.ACQUIRING, feedback.visible(60)!!.phase)
        feedback.triggered(11, 103); feedback.result(11, 103, true, false, 70)
        assertEquals(FocusFeedback.Phase.FAILED, feedback.visible(70)!!.phase)
        assertNotNull(feedback.visible(2069)); assertNull(feedback.visible(2070))
    }
    @Test fun latestTargetReplacesPositionAndIgnoresObsoleteResult() {
        val feedback = FocusFeedback()
        feedback.begin(10, 80.0, 100.0, 300, 400, 0); feedback.triggered(10, 100)
        feedback.begin(11, 150.0, 200.0, 300, 400, 20)
        feedback.result(10, 110, true, false, 30)
        assertEquals(150f, feedback.visible(30)!!.x, 0f)
        assertEquals(FocusFeedback.Phase.ACQUIRING, feedback.visible(30)!!.phase)
    }
    @Test fun timedOutAcquisitionCannotLaterTurnIntoSuccess() {
        val feedback = FocusFeedback()
        feedback.begin(10, 80.0, 100.0, 300, 400, 0); feedback.triggered(10, 100)
        feedback.fail(5000); feedback.result(10, 101, true, true, 5010)
        assertEquals(FocusFeedback.Phase.FAILED, feedback.visible(5010)!!.phase)
        assertNull(feedback.visible(7000))
    }
    @Test fun lateSuccessCannotBeatTheAcquisitionTimeoutTick() {
        val feedback = FocusFeedback()
        feedback.begin(10, 80.0, 100.0, 300, 400, 0); feedback.triggered(10, 100)
        feedback.result(10, 101, true, true, 5000)
        assertEquals(FocusFeedback.Phase.FAILED, feedback.visible(5000)!!.phase)
    }
    @Test fun lockPersistsButClearPreventsLateResultsFromRestoringTarget() {
        val feedback = FocusFeedback()
        feedback.begin(10, 80.0, 100.0, 300, 400, 0); feedback.triggered(10, 100)
        feedback.result(10, 101, true, true, 30); feedback.locked(40)
        assertEquals(FocusFeedback.Phase.LOCKED, feedback.visible(20000)!!.phase)
        feedback.clear(); feedback.triggered(10, 102); feedback.result(10, 102, true, true, 20010)
        assertNull(feedback.visible(20010))
    }
}
