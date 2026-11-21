package com.eggman.backstage.shell

import com.eggman.backstage.host.DesktopHost.Presentation

/** Main-thread lease of HIDDEN for Home's preview. Explicit presentation requests always win. */
class HomePreviewLease {
    var owned = false
        private set
    var keepHiddenOnLeave = false
        private set
    private var changing = false

    fun onPresentationRequested() {
        if (!changing) { owned = false; keepHiddenOnLeave = false }
    }

    fun onPresentationChanged(presentation: Presentation) {
        if (!changing && ((owned && presentation != Presentation.IN_APP) ||
                (!owned && presentation != Presentation.HIDDEN))) onPresentationRequested()
    }

    fun show(current: Presentation, present: (Presentation) -> Boolean): Boolean {
        if (current != Presentation.HIDDEN) return false
        changing = true
        return try {
            present(Presentation.IN_APP).also { ok -> owned = ok; keepHiddenOnLeave = ok }
        } finally { changing = false }
    }

    fun restore(current: Presentation, present: (Presentation) -> Boolean): Boolean {
        if (!owned) return false
        owned = false
        if (current != Presentation.IN_APP) { keepHiddenOnLeave = false; return false }
        changing = true
        return try { present(Presentation.HIDDEN) } finally { changing = false }
    }
}
