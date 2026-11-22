package com.eggman.backstage.a11y.companion

import android.graphics.Path
import android.graphics.PathMeasure

/** A bounded fraction/x/y polyline preserves curves without parceling a platform Path. */
object GesturePoints {
    const val MAX_FLOATS = 12_288
    fun sample(path: Path): FloatArray {
        require(!PathMeasure(path, false).nextContour()) { "One gesture stroke is required" }
        return path.approximate(0.5f).also { validate(it) }
    }
    fun validate(points: FloatArray) {
        require(points.size in 3..MAX_FLOATS && points.size % 3 == 0)
        var previous = -1f
        for (i in points.indices step 3) {
            val fraction = points[i]
            require(fraction.isFinite() && fraction in 0f..1f && fraction >= previous)
            require(points[i + 1].isFinite() && points[i + 2].isFinite())
            // Android's StrokeDescription rejects paths whose bounds extend into negative coordinates.
            // Validate before dispatch, rather than throwing from the companion's asynchronous Main coroutine.
            require(points[i + 1] >= 0f && points[i + 2] >= 0f)
            previous = fraction
        }
    }
    fun path(points: FloatArray): Path {
        validate(points)
        return Path().apply {
            moveTo(points[1], points[2])
            for (i in 3 until points.size step 3) lineTo(points[i + 1], points[i + 2])
        }
    }
}
