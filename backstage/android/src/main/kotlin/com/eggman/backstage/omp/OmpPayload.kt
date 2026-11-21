package com.eggman.backstage.omp

import android.content.res.AssetManager
import org.json.JSONObject
import java.io.InputStream

class OmpPayload(private val assets: AssetManager) {
    data class FileInfo(val sha256: String, val size: Long)
    data class Manifest(val ompVersion: String, val bunVersion: String, val bootstrapTag: String, val files: Map<String, FileInfo>)
    val manifest: Manifest? by lazy {
        runCatching {
            val j = assets.open("omp/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            require(j.getInt("payload") == 1)
            val files = j.getJSONObject("files")
            Manifest(j.getString("ompVersion"), j.getString("bunVersion"), j.getString("bootstrapTag"),
                files.keys().asSequence().associateWith { name ->
                    require(name.matches(Regex("[a-zA-Z0-9._/-]+")) && !name.contains(".."))
                    files.getJSONObject(name).let { f -> FileInfo(f.getString("sha256"), f.getLong("size")) }
                })
        }.getOrNull()
    }
    val available: Boolean get() = manifest != null
    fun open(name: String): InputStream {
        require(name == "manifest.json" || manifest?.files?.containsKey(name) == true) { "Unknown payload file" }
        return assets.open("omp/$name")
    }
    fun size(name: String): Long = if (name == "manifest.json") open(name).use { it.readBytes().size.toLong() } else manifest!!.files.getValue(name).size
}
