package org.example.memosm.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class LocalNetworkAccessTest {
    @Test
    fun privateIpv4ServersRequirePermission() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.10", "169.254.1.2",
            "100.64.0.1", "100.127.255.254").forEach {
            assertTrue(it, serverUsesLocalNetwork("http://$it:5230/"))
        }
    }

    @Test
    fun localIpv6ServersRequirePermission() {
        listOf("fc00::1", "fd12:3456::1", "fe80::1", "::ffff:192.168.1.2").forEach {
            assertTrue(it, serverUsesLocalNetwork("http://[$it]:5230/"))
        }
    }

    @Test
    fun publicAndLoopbackAddressesDoNotRequirePermission() {
        listOf("8.8.8.8", "172.15.255.255", "172.32.0.1", "192.169.1.1", "127.0.0.1",
            "100.63.255.255", "100.128.0.1").forEach {
            assertFalse(it, serverUsesLocalNetwork("https://$it/"))
        }
        listOf("::1", "2606:4700:4700::1111").forEach {
            assertFalse(it, serverUsesLocalNetwork("https://[$it]/"))
        }
    }

    @Test
    fun localNamesAreRecognizedBeforeAttemptingRestrictedResolution() {
        listOf("http://memos:5230", "https://MEMOS.LOCAL./").forEach {
            assertTrue(serverUsesLocalNetwork(it) { error("Must not resolve a local name before permission") })
        }
        assertFalse(serverUsesLocalNetwork("http://localhost:5230") { error("No DNS needed") })
    }

    @Test
    fun ordinaryDomainsResolvingToLocalAddressesRequirePermission() {
        assertTrue(serverUsesLocalNetwork("https://notes.example.com") {
            listOf(InetAddress.getByName("192.168.1.5"))
        })
        assertFalse(serverUsesLocalNetwork("https://notes.example.com") {
            listOf(InetAddress.getByName("8.8.8.8"))
        })
    }

    @Test
    fun mixedDnsAnswersRequirePermissionIfAnyAddressIsLocal() {
        assertTrue(serverUsesLocalNetwork("https://notes.example.com") {
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("fd00::1"))
        })
    }

    @Test
    fun directlyConnectedIpv6ServersRequirePermissionEvenWithGlobalAddresses() {
        val address = InetAddress.getByName("2001:db8:1::5")
        assertTrue(serverUsesLocalNetwork("https://notes.example.com",
            isOnLinkAddress = { it == address }, resolve = { listOf(address) }))
    }

    @Test
    fun dnsFailuresAndInvalidUrlsDoNotPromptForUnrelatedPermission() {
        assertFalse(serverUsesLocalNetwork("https://missing.example.com") { throw UnknownHostException() })
        assertFalse(serverUsesLocalNetwork("not a url") { error("Invalid URL must not be resolved") })
    }
}
