package com.eggman.backstage.privileged

import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PrivilegedDeadlineContractTest {
    private fun host(shell: (Array<out Any?>) -> DesktopHost.ShellResult): DesktopHost =
        Proxy.newProxyInstance(DesktopHost::class.java.classLoader, arrayOf(DesktopHost::class.java)) { _, method, args ->
            when (method.name) {
                "privilegedAvailable" -> true
                "shell" -> shell(args ?: emptyArray())
                "toString" -> "DeadlineContractHost"
                else -> error("Unexpected host call ${method.name}")
            }
        } as DesktopHost

    @Test fun preferredDesktopRouteReceivesTimeoutAndOutputCap() = runBlocking {
        val previous = DesktopHosts.current
        var calls = 0
        DesktopHosts.current = host { args ->
            calls++
            assertEquals("Legacy shell overload dropped bounds", 4, args.size)
            @Suppress("UNCHECKED_CAST") val argv = args[0] as Array<String>
            assertArrayEquals(arrayOf("sh", "-c", "sleep 600"), argv)
            assertEquals(123L, args[1])
            assertEquals(BoundedShell.DEFAULT_MAX_OUTPUT_BYTES, args[2])
            DesktopHost.ShellResult(124, "bounded", "deadline")
        }
        try {
            val r = Privileged.exec("sh", "-c", "sleep 600", timeoutMs = 123)
            assertEquals(124, r.code)
            assertEquals("bounded", r.out)
            assertEquals(1, calls)
        } finally { DesktopHosts.current = previous }
    }

    @Test fun hostCancellationPropagatesWithoutTryingAnotherTransport() {
        val previous = DesktopHosts.current
        val cancelled = CancellationException("Stop")
        DesktopHosts.current = host { throw cancelled }
        try {
            assertSame(cancelled, assertThrows(CancellationException::class.java) {
                runBlocking { Privileged.exec("sleep", "600", timeoutMs = 100) }
            })
        } finally { DesktopHosts.current = previous }
    }
}
