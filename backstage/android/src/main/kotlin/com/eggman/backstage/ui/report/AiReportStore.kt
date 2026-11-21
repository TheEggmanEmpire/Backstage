package com.eggman.backstage.ui.report

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One atomic private file per flag. The Android UI supplies noBackupFilesDir/ai-reports so response copies
 * cannot accidentally join an Android backup or the settings export. All methods are blocking: call on IO.
 * A process-wide lock also serializes independent chat rows/store instances; nothing silently evicts reports.
 */
class AiReportStore(private val directory: File) {
    fun save(report: AiContentReport) = synchronized(lock) {
        val target = file(report.id)
        check(target.exists() || reportFiles().size < MAX_REPORTS) { "Report storage is full. Delete a saved report first." }
        write(target, AiContentReportCodec.encode(report).toString())
    }

    fun list(): List<AiContentReport> = synchronized(lock) {
        reportFiles().mapNotNull { file ->
            // A damaged or newer file must not hide the remaining reports, nor be deleted on read.
            runCatching {
                require(file.length() <= MAX_FILE_BYTES) { "Report file is too large" }
                AiContentReportCodec.decode(JSONObject(file.readText(Charsets.UTF_8))).also {
                    require(file.name == "${it.id}.json") { "Mismatched report ID" }
                }
            }.getOrNull()
        }.sortedWith(compareByDescending<AiContentReport> { it.createdAtMs }.thenBy { it.id })
    }

    fun delete(id: String) = synchronized(lock) {
        val file = file(id)
        if (file.exists() && !file.delete()) throw IOException("Could not delete report")
    }

    fun destinations(): ReportDestinations = synchronized(lock) {
        val file = File(directory, "destinations.json")
        if (!file.exists()) return@synchronized ReportDestinations()
        require(file.length() <= 4096) { "Invalid report destinations" }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        ReportDestinations(json.getString("maintainer"), json.getString("provider"))
    }

    fun saveDestinations(destinations: ReportDestinations) = synchronized(lock) {
        write(File(directory, "destinations.json"), JSONObject()
            .put("maintainer", destinations.maintainer).put("provider", destinations.provider).toString())
    }

    private fun reportFiles(): List<File> = directory.listFiles()?.filter {
        it.isFile && it.extension == "json" && AiContentReport.validId(it.nameWithoutExtension)
    }.orEmpty()

    private fun file(id: String): File {
        require(AiContentReport.validId(id)) { "Invalid report ID" }
        return File(directory, "$id.json")
    }

    private fun write(target: File, text: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create report storage" }
        val temporary = File.createTempFile("report-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val MAX_REPORTS = 100
        private const val MAX_FILE_BYTES = 512_000
        private val lock = Any()
    }
}

/** Addresses are local user configuration, never inferred from an API URL or sent as part of the report. */
data class ReportDestinations(val maintainer: String = "", val provider: String = "") {
    init {
        require(validAddress(maintainer) && validAddress(provider)) { "Enter a single email address, or leave it blank." }
    }

    companion object {
        // Exclude URI/header delimiters and whitespace; only a single mailbox is accepted. Blank means share.
        fun validAddress(address: String): Boolean = address.isEmpty() || (address.length <= 254 &&
            Regex("[A-Za-z0-9.!#$%&'*+/=_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+").matches(address))
    }
}
