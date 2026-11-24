package com.eggman.backstage.ui.components

import com.eggman.backstage.privileged.PrivilegeProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeManagerButtonsTest {
    @Test fun offersStellarThenShizukuPlusWithTheirDownloadPages() {
        assertEquals(listOf("Stellar", "Shizuku Plus"), PrivilegeManager.ALL.map { it.label })
        assertEquals(PrivilegeProviders.STELLAR_DOWNLOAD, PrivilegeManager.STELLAR.download)
        assertEquals(PrivilegeProviders.SHIZUKU_PLUS_DOWNLOAD, PrivilegeManager.SHIZUKU_PLUS.download)
    }

    @Test fun iconsComeFromEachRepositoryOverHttps() = PrivilegeManager.ALL.forEach {
        assertTrue(it.iconUrl, it.iconUrl.startsWith("https://raw.githubusercontent.com/"))
    }

    @Test fun eachManagerHasItsOwnIconCacheFile() =
        assertEquals(2, PrivilegeManager.ALL.map { it.cacheName }.toSet().size)
}
