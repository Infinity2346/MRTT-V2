package com.mrtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerRecoveryPolicyTest {
    @Test
    fun startupZeroDoesNotArmRecovery() {
        val policy = WorkerRecoveryPolicy()
        assertNull(policy.armAtZero())
        assertFalse(policy.shouldRecover(0, 9))
    }

    @Test
    fun zeroAfterHealthyCohortArmsOneRecovery() {
        val policy = WorkerRecoveryPolicy()
        policy.observe(30)
        assertEquals(30, policy.armAtZero())
        assertEquals(30, policy.armAtZero())
        assertTrue(policy.shouldRecover(0, 30))
    }

    @Test
    fun partialRecoveryDoesNotHideFullOutage() {
        val policy = WorkerRecoveryPolicy()
        policy.observe(30)
        val target = policy.armAtZero()!!
        policy.observe(6)
        assertTrue(policy.shouldRecover(6, target))
    }

    @Test
    fun completeRecoveryDisarmsRestart() {
        val policy = WorkerRecoveryPolicy()
        policy.observe(30)
        val target = policy.armAtZero()!!
        assertTrue(policy.observe(30))
        assertFalse(policy.shouldRecover(30, target))
    }

    @Test
    fun resetRequiresAHealthyCohortAgain() {
        val policy = WorkerRecoveryPolicy()
        policy.observe(60)
        assertEquals(60, policy.armAtZero())
        policy.reset()
        assertNull(policy.armAtZero())
    }
}
