package com.eggman.backstage.omp.engine

import com.eggman.backstage.llm.Part
import java.util.Base64

/** TaskRequest attachments are strings: inline image data URLs become actual RPC image parts. */
object OmpAttachments {
    data class Parsed(val context: Map<String, String>, val images: List<Part.Image>)
    fun parse(attachments: Map<String, String>): Parsed {
        val context = LinkedHashMap<String, String>()
        val images = ArrayList<Part.Image>()
        attachments.forEach { (name, value) ->
            val header = value.substringBefore(',')
            if (header.matches(Regex("data:image/(png|jpeg|webp|gif);base64"))) {
                val data = value.substringAfter(',')
                require(data.isNotEmpty() && data.length <= OmpJsonl.MAX_FRAME_BYTES) { "Image attachment exceeds the OMP transport limit" }
                try { Base64.getDecoder().decode(data) } catch (_: IllegalArgumentException) { throw OmpProtocolException("Invalid image attachment base64") }
                images += Part.Image(data, header.removePrefix("data:").removeSuffix(";base64"))
                context[name] = "[image attached]"
            } else context[name] = value
        }
        return Parsed(context, images)
    }
}
