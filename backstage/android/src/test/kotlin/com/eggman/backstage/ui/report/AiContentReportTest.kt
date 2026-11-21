package com.eggman.backstage.ui.report

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiContentReportTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun report() = AiContentReport.create(
        response = "A response with \"quotes\",\n日本語 and 🐈 <script>alert(1)</script>\u0000\u2028\u202e",
        taskId = "task-1", category = ReportCategory.HARMFUL, note = "Please investigate.\nThanks.",
        nowMs = 1234,
    )

    @Test fun roundTripPreservesTextAndMetadataWithoutAddingConversationOrCredentials() {
        val report = report()
        val json = AiContentReportCodec.encode(report)
        assertEquals(report, AiContentReportCodec.decode(JSONObject(json.toString())))
        assertEquals(setOf("version", "id", "createdAtMs", "taskId", "category", "response", "responseTruncated", "note"),
            json.keys().asSequence().toSet())
    }

    @Test fun missingTaskAndEmptyNoteRoundTrip() {
        val report = AiContentReport.create("hello", null, ReportCategory.OTHER, "", nowMs = 0)
        assertEquals(report, AiContentReportCodec.decode(AiContentReportCodec.encode(report)))
        assertFalse(AiContentReportCodec.encode(report).has("taskId"))
    }

    @Test fun rejectsUnknownVersionCategoryAndWrongTypes() {
        for ((key, value) in listOf("version" to 2, "version" to "1", "version" to 1.5,
            "createdAtMs" to -1, "createdAtMs" to "1234", "category" to "UNKNOWN", "note" to JSONObject.NULL,
            "responseTruncated" to "false")) {
            assertThrows("$key=$value", IllegalArgumentException::class.java) {
                AiContentReportCodec.decode(AiContentReportCodec.encode(report()).put(key, value))
            }
        }
    }

    @Test fun boundsResponseWithoutSplittingAnEmojiAndMarksTruncation() {
        val response = "x".repeat(AiContentReport.MAX_RESPONSE_CHARS - 1) + "🐈tail"
        val report = AiContentReport.create(response, null, ReportCategory.OTHER, "")
        assertTrue(report.responseTruncated)
        assertEquals("x".repeat(AiContentReport.MAX_RESPONSE_CHARS - 1), report.response)
        assertFalse(report.response.last().isHighSurrogate())
        assertThrows(IllegalArgumentException::class.java) {
            AiContentReport.create("response", null, ReportCategory.OTHER, "x".repeat(AiContentReport.MAX_NOTE_CHARS + 1))
        }
    }

    @Test fun persistsAcrossStoreInstancesAndCanBeDeleted() {
        val dir = temporary.newFolder()
        val first = report()
        val second = report().copy(createdAtMs = 9999)
        AiReportStore(dir).save(first)
        AiReportStore(dir).save(second)
        assertEquals(listOf(second, first), AiReportStore(dir).list())
        AiReportStore(dir).delete(first.id)
        assertEquals(listOf(second), AiReportStore(dir).list())
    }

    @Test fun corruptRecordsDoNotHideValidReportsAndPathsCannotEscapeStore() {
        val dir = temporary.newFolder()
        val valid = report()
        AiReportStore(dir).save(valid)
        dir.resolve("b025a566-62e6-45f3-85d7-10365b3e2115.json").writeText("broken JSON")
        assertEquals(listOf(valid), AiReportStore(dir).list())
        assertThrows(IllegalArgumentException::class.java) { AiReportStore(dir).delete("../outside") }
        assertThrows(IllegalArgumentException::class.java) { report().copy(id = "../outside") }
    }

    @Test fun exportIsExplicitlyLocalAndContainsOnlySelectedResponse() {
        val body = report().shareText()
        assertTrue(body.contains("Harmful or dangerous advice"))
        assertTrue(body.contains(report().response))
        assertTrue(body.contains("Please investigate."))
        assertTrue(body.contains("Saved locally"))
        assertFalse(body.contains("Authorization"))
    }

    @Test fun destinationsDefaultBlankPersistLocallyAndRejectMailHeaderInjection() {
        val dir = temporary.newFolder()
        val store = AiReportStore(dir)
        assertEquals(ReportDestinations(), store.destinations())
        val recipients = ReportDestinations("maintainer@example.org", "provider@example.org")
        store.saveDestinations(recipients)
        assertEquals(recipients, AiReportStore(dir).destinations())
        assertTrue(store.list().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { ReportDestinations("x@example.org\r\nBcc:other@example.org") }
        assertThrows(IllegalArgumentException::class.java) { ReportDestinations(provider = "https://provider.example") }
    }

    @Test fun fullStoreRefusesNewReportsWithoutEvictingExistingFlags() {
        val dir = temporary.newFolder()
        val store = AiReportStore(dir)
        val records = (1..AiReportStore.MAX_REPORTS).map { report() }
        records.forEach(store::save)
        assertThrows(IllegalStateException::class.java) { store.save(report()) }
        assertEquals(records.toSet(), store.list().toSet())
        assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
        store.delete(records.first().id)
        store.save(report())
        assertEquals(AiReportStore.MAX_REPORTS, store.list().size)
    }

    @Test fun storageFailureIsSurfacedInsteadOfClaimingTheFlagWasSaved() {
        val notADirectory = temporary.newFile()
        assertThrows(IllegalStateException::class.java) { AiReportStore(notADirectory).save(report()) }
    }
}
