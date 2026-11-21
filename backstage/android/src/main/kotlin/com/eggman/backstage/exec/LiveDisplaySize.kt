package com.eggman.backstage.exec

/**
 * The current size of a display, read again on every use so rotation and fold/unfold are honoured
 * (screen clipping, coordinate checks and screenshot scaling all depend on it). A failed or
 * nonsensical read falls back to the last good value.
 */
class LiveDisplaySize(
    private val fallback: Pair<Int, Int> = 1080 to 2400,
    private val read: () -> Pair<Int, Int>?,
) {
    @Volatile private var last: Pair<Int, Int>? = null

    fun get(): Pair<Int, Int> {
        val now = runCatching(read).getOrNull()?.takeIf { it.first > 0 && it.second > 0 }
        if (now != null) last = now
        return now ?: last ?: fallback
    }
}
