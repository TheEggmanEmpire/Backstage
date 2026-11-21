package com.eggman.backstage.skills

import java.net.URI

/** Where to download a skill archive from, and which folder of it to use. */
data class SkillDownload(
    /** The archive to fetch (https). */
    val archiveUrl: String,
    /** Folder inside the repository (below the archive's top folder), or null for the whole archive. */
    val subPath: String? = null,
    /** What the user entered; stored with the skill as its source. */
    val source: String,
    /** True when [archiveUrl] is a GitHub repository archive. */
    val github: Boolean = false,
)

/**
 * Maps what a user pastes to a download: a direct link to a.zip/.skill file, or a GitHub repository, folder or
 * SKILL.md page. GitHub links become the repository's zip archive from GitHub's REST API (`/zipball/<ref>`, the
 * default branch without a ref) plus the folder to take from it. A ref containing "/" cannot be told apart from the
 * folder path in a /tree/ link; the first segment is taken as the ref.
 */
object SkillSources {
    class BadSource(message: String) : IllegalArgumentException(message)

    fun resolve(input: String): SkillDownload {
        val text = input.trim()
        if (text.isEmpty()) throw BadSource("Enter a link to a .zip file or a GitHub repository or folder.")
        val withScheme = if (!text.contains("://") && (text.startsWith("github.com/") || text.startsWith("www.github.com/"))) "https://$text" else text
        val uri = try { URI(withScheme) } catch (_: Exception) { throw BadSource("This is not a valid link.") }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") throw BadSource(if (scheme == "http") "Only https links are accepted." else "This is not a web link.")
        val host = uri.host?.lowercase() ?: throw BadSource("This link has no host.")
        val segs = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        return when (host) {
            "github.com", "www.github.com" -> github(segs, text, withScheme)
            "raw.githubusercontent.com" -> {
                if (segs.size < 4) throw BadSource("This raw GitHub link does not name a file.")
                val (owner, repo, ref) = segs
                val path = segs.drop(3)
                val folder = if (path.last().equals(SkillArchive.SKILL_FILE, true)) path.dropLast(1) else path
                zipball(owner, repo, ref, folder.joinToString("/").ifEmpty { null }, text)
            }
            else -> SkillDownload(withScheme, null, text)
        }
    }

    private fun github(segs: List<String>, original: String, url: String): SkillDownload {
        if (segs.size < 2) throw BadSource("A GitHub link needs an owner and a repository: github.com/<owner>/<repo>.")
        val owner = segs[0]
        val repo = segs[1].removeSuffix(".git")
        val rest = segs.drop(2)
        if (!validPart(owner) || !validPart(repo)) throw BadSource("This GitHub link does not name a repository.")
        return when (rest.firstOrNull()) {
            null -> zipball(owner, repo, null, null, original)
            "tree", "blob" -> {
                val ref = rest.getOrNull(1) ?: throw BadSource("This GitHub link names no branch.")
                var path = rest.drop(2)
                if (rest[0] == "blob" && path.isNotEmpty()) {
                    // A file page: SKILL.md (or any file) means its folder.
                    path = path.dropLast(1)
                }
                zipball(owner, repo, ref, path.joinToString("/").ifEmpty { null }, original)
            }
            // Direct archive and release downloads are fetched as they are.
            "archive", "releases", "raw" -> SkillDownload(url, null, original)
            else -> throw BadSource("Use a repository, folder (/tree/…) or file (/blob/…) link from GitHub.")
        }
    }

    private fun validPart(s: String) = s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "-_." } && s != "." && s != ".."

    private fun zipball(owner: String, repo: String, ref: String?, path: String?, original: String): SkillDownload {
        val r = ref?.let { "/" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val sub = path?.let { SkillPaths.normalize(it) ?: throw BadSource("The folder in this link is not a valid path.") }
        return SkillDownload("https://api.github.com/repos/$owner/$repo/zipball$r", sub, original, github = true)
    }
}
