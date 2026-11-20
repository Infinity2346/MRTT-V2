// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.mrtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerCountPolicyTest {

    @Test
    fun requestedWorkerPreferenceIsNotCappedByTransportLimits() {
        assertEquals(100, WorkerCountPolicy.requested(100))
        assertEquals(MrttConstants.Tunnel.DEFAULT_WORKERS, WorkerCountPolicy.requested(null))
        assertEquals(30, WorkerCountPolicy.maximumForHashes(1))
        assertEquals(100, WorkerCountPolicy.requested(100))
    }

    @Test
    fun everySupportedTotalRemainsAnExactMultipleOfTen() {
        val supported = (1..MrttConstants.Tunnel.MAX_WORKERS / MrttConstants.Tunnel.WORKERS_PER_GROUP)
            .map { it * MrttConstants.Tunnel.WORKERS_PER_GROUP }
        for ((groups, workers) in supported.withIndex()) {
            assertEquals(workers, WorkerCountPolicy.normalize(workers))
            assertEquals(groups + 1, workers / MrttConstants.Tunnel.WORKERS_PER_GROUP)
        }
        assertEquals(100, supported.last())
        assertEquals(100, WorkerCountPolicy.normalize(MrttConstants.Tunnel.MAX_WORKERS))
    }

    @Test
    fun hashCapacityHasNoHiddenExtraGroup() {
        assertEquals(30, WorkerCountPolicy.maximumForHashes(1))
        assertEquals(60, WorkerCountPolicy.maximumForHashes(2))
        assertEquals(90, WorkerCountPolicy.maximumForHashes(3))
        assertEquals(100, WorkerCountPolicy.maximumForHashes(4))
        assertEquals(100, WorkerCountPolicy.maximumForHashes(5))
        assertEquals(100, WorkerCountPolicy.maximumForHashes(6))
    }

    @Test
    fun maxAndJitsiAreNotCappedByVkHashes() {
        assertEquals(
            100,
            WorkerCountPolicy.maximumForTransport(
                maxMode = true,
                jitsiMode = false,
                linkMode = false,
                linkHashCount = 0,
                autoHashMode = false,
                manualHashCount = 1,
            ),
        )
        assertEquals(
            100,
            WorkerCountPolicy.maximumForTransport(
                maxMode = false,
                jitsiMode = true,
                linkMode = false,
                linkHashCount = 0,
                autoHashMode = false,
                manualHashCount = 1,
            ),
        )
        // VK по-прежнему ограничен числом хешей.
        assertEquals(
            30,
            WorkerCountPolicy.maximumForTransport(
                maxMode = false,
                jitsiMode = false,
                linkMode = false,
                linkHashCount = 0,
                autoHashMode = false,
                manualHashCount = 1,
            ),
        )
    }

    @Test
    fun participantCapacityComesFromLinkBeforeLocalProfileState() {
        assertEquals(
            100,
            WorkerCountPolicy.maximumForSources(
                linkMode = true,
                linkHashCount = 4,
                autoHashMode = false,
                manualHashCount = 0,
            ),
        )
        assertEquals(
            30,
            WorkerCountPolicy.maximumForSources(
                linkMode = false,
                linkHashCount = 4,
                autoHashMode = false,
                manualHashCount = 1,
            ),
        )
        assertEquals(
            100,
            WorkerCountPolicy.maximumForSources(
                linkMode = false,
                linkHashCount = 0,
                autoHashMode = true,
                manualHashCount = 1,
            ),
        )
    }

    @Test
    fun invalidPersistedCountsAreBoundedAndNeverCreatePartialCredentialGroup() {
        for (requested in -1_000..1_000) {
            val normalized = WorkerCountPolicy.normalize(requested)
            assertTrue(normalized in 10..MrttConstants.Tunnel.MAX_WORKERS)
            assertEquals(0, normalized % 10)
            assertTrue(normalized <= requested.coerceAtLeast(10) || normalized == 100)
        }
    }

    @Test
    fun stalePersistedMaximumIsRecappedByCurrentHashCountAtRuntime() {
        assertEquals(30, WorkerCountPolicy.normalizeForHashes(162, 1))
        assertEquals(60, WorkerCountPolicy.normalizeForHashes(162, 2))
        assertEquals(90, WorkerCountPolicy.normalizeForHashes(162, 3))
        assertEquals(100, WorkerCountPolicy.normalizeForHashes(162, 4))
        assertEquals(100, WorkerCountPolicy.normalizeForHashes(162, 5))
        assertEquals(100, WorkerCountPolicy.normalizeForHashes(162, 6))
    }

    @Test
    fun automaticHashModeAllowsTheFullWorkerRange() {
        for (requested in -100..300) {
            val admitted = WorkerCountPolicy.normalize(requested)
            assertTrue(admitted in 10..MrttConstants.Tunnel.MAX_WORKERS)
            assertEquals(0, admitted % 10)
        }
        assertEquals(100, WorkerCountPolicy.normalize(162))
    }

    @Test
    fun craftedIntentCountsCannotBypassHashAwareRuntimeAdmission() {
        for (requested in listOf(Int.MIN_VALUE, -1, 0, 8, 28, 109, Int.MAX_VALUE)) {
            for (hashCount in 1..MrttConstants.Tunnel.MAX_VK_HASHES) {
                val admitted = WorkerCountPolicy.normalizeForHashes(requested, hashCount)
                assertTrue(admitted >= MrttConstants.Tunnel.WORKERS_PER_GROUP)
                assertEquals(0, admitted % MrttConstants.Tunnel.WORKERS_PER_GROUP)
                assertTrue(admitted <= WorkerCountPolicy.maximumForHashes(hashCount))
            }
        }
        assertEquals(
            30,
            WorkerCountPolicy.normalizeForHashValues(
                Int.MAX_VALUE,
                listOf("same", " same ", "same", ""),
            ),
        )
        assertEquals(
            100,
            WorkerCountPolicy.normalizeForHashValues(
                Int.MAX_VALUE,
                listOf("one", "two", "three", "four", "five", "six", "ignored-seventh"),
            ),
        )
    }

    @Test
    fun automaticCallCountScalesToSixHashes() {
        assertEquals(1, VkAutoCallsManager.callCountForWorkers(30))
        assertEquals(2, VkAutoCallsManager.callCountForWorkers(60))
        assertEquals(3, VkAutoCallsManager.callCountForWorkers(90))
        assertEquals(4, VkAutoCallsManager.callCountForWorkers(100))
        assertEquals(
            MrttConstants.VkAutoHash.AUTO_CALL_SMALL_DELAY_MS,
            VkAutoCallsManager.requestDelayForCallCount(1),
        )
        assertEquals(
            MrttConstants.VkAutoHash.AUTO_CALL_SMALL_DELAY_MS,
            VkAutoCallsManager.requestDelayForCallCount(4),
        )
        assertEquals(
            MrttConstants.VkAutoHash.AUTO_CALL_LARGE_DELAY_MS,
            VkAutoCallsManager.requestDelayForCallCount(5),
        )
        assertEquals(
            MrttConstants.VkAutoHash.AUTO_CALL_LARGE_DELAY_MS,
            VkAutoCallsManager.requestDelayForCallCount(6),
        )
    }

    @Test
    fun automaticCallFailureCanRedistributeWholeGroupsAcrossCreatedHashes() {
        assertEquals(
            100,
            WorkerCountPolicy.normalizeForHashValues(
                162,
                listOf("one", "two", "three", "four", "five"),
                allowRedistribution = true,
            ),
        )
        assertEquals(
            50,
            WorkerCountPolicy.normalizeForHashValues(
                50,
                listOf("one"),
                allowRedistribution = true,
            ),
        )
    }
}
