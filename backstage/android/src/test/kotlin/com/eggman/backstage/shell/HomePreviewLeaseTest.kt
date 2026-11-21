package com.eggman.backstage.shell

import com.eggman.backstage.host.DesktopHost.Presentation
import org.junit.Assert.*
import org.junit.Test

class HomePreviewLeaseTest {
    @Test fun automaticPreviewReturnsToHiddenWithoutAPlayerOrPopup() {
        val lease = HomePreviewLease()
        var current = Presentation.HIDDEN
        val present: (Presentation) -> Boolean = { lease.onPresentationRequested(); current = it; lease.onPresentationChanged(it); true }
        assertTrue(lease.show(current, present))
        assertTrue(lease.owned)
        assertEquals(Presentation.IN_APP, current)
        assertTrue(lease.restore(current, present))
        assertEquals(Presentation.HIDDEN, current)
        assertFalse(lease.owned)
        assertTrue(lease.keepHiddenOnLeave)
    }
    @Test fun userChoiceCancelsTheLeaseEvenIfItChoosesInAppAgain() {
        val lease = HomePreviewLease()
        assertTrue(lease.show(Presentation.HIDDEN) { true })
        lease.onPresentationRequested()
        assertFalse(lease.restore(Presentation.IN_APP) { fail("must preserve user choice"); false })
        assertFalse(lease.keepHiddenOnLeave)
    }
    @Test fun neverTakesMiniFullOrFailedPresentation() {
        val lease = HomePreviewLease()
        for (p in listOf(Presentation.MINI, Presentation.FULL, Presentation.NONE, Presentation.IN_APP)) {
            assertFalse(lease.show(p) { fail("not hidden"); false })
        }
        assertFalse(lease.show(Presentation.HIDDEN) { false })
        assertFalse(lease.owned)
        assertFalse(lease.keepHiddenOnLeave)
    }
    @Test fun externalChangeCancelsRestoration() {
        val lease = HomePreviewLease()
        lease.show(Presentation.HIDDEN) { true }
        lease.onPresentationChanged(Presentation.MINI)
        assertFalse(lease.restore(Presentation.MINI) { fail("must not hide popup"); false })
        assertFalse(lease.keepHiddenOnLeave)
    }
}
