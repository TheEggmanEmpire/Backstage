package com.eggman.backstage.companion.privilege

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class CompanionSkillScriptTest {
    private fun request(vararg entries: Pair<String, String>) = Bundle().apply {
        putString("path", "run.sh")
        putStringArrayList("args", arrayListOf("one argument", "\$(literal)"))
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, body) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        putByteArray("archive", output.toByteArray())
    }

    @Test fun scriptApprovalShowsTheContentAndExecutionUsesLiteralArguments() {
        val script = CompanionSkillScript.from(request("run.sh" to "cat text.txt", "text.txt" to "example"))
        assertTrue(script.approvalText().contains("cat text.txt"))
        assertTrue(script.approvalText().contains("SHA-256"))
        assertEquals(listOf("run.sh", "sh", "one argument", "\$(literal)"), script.argv("/data/local/tmp/stage").takeLast(4))
    }

    @Test fun archiveCannotWriteOutsideTheStagingDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            CompanionSkillScript.from(request("run.sh" to "id", "../outside" to "example"))
        }
    }

    @Test fun archiveMustIncludeTheSelectedScript() {
        assertThrows(IllegalArgumentException::class.java) {
            CompanionSkillScript.from(request("other.sh" to "id"))
        }
    }
}
