package com.eggman.backstage.companion

import android.os.Parcel
import android.os.Parcelable

/** Small event summary; never send AccessibilityEvent/Notification objects or their potentially huge extras. */
data class CompanionEvent(
    val type: Int,
    val displayId: Int,
    val packageName: String,
    val appLabel: String = "",
    val text: String = "",
    val timeMs: Long = 0,
) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeInt(type); out.writeInt(displayId); out.writeString(packageName)
        out.writeString(appLabel); out.writeString(text); out.writeLong(timeMs)
    }
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<CompanionEvent> {
            override fun createFromParcel(p: Parcel) = CompanionEvent(p.readInt(), p.readInt(), p.readString().orEmpty(),
                p.readString().orEmpty(), p.readString().orEmpty(), p.readLong())
            override fun newArray(size: Int): Array<CompanionEvent?> = arrayOfNulls(size)
        }
    }
}
