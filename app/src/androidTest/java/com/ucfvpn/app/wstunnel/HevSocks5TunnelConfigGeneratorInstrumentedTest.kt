package com.ucfvpn.app.wstunnel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for [HevSocks5TunnelConfigGenerator.generate], which
 * writes the dynamic hev-socks5-tunnel YAML into the app's filesDir.
 *
 * The pure [HevSocks5TunnelConfigGenerator.buildYaml] function is already
 * covered by the JVM test `HevSocks5TunnelConfigGeneratorTest`; these tests
 * cover the Android-Context side: file location and on-disk content.
 *
 * Requires an Android device or emulator with API 26+.
 */
@RunWith(AndroidJUnit4::class)
class HevSocks5TunnelConfigGeneratorInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        // Remove any generated config so tests never leak state.
        File(context.filesDir, CONFIG_FILE_NAME).delete()
    }

    @Test
    fun generateWritesYAMLToFilesDirWithDefaultContent() {
        val result = HevSocks5TunnelConfigGenerator.generate(context)

        assertTrue("generate should succeed", result.isSuccess)

        val expectedPath = File(context.filesDir, CONFIG_FILE_NAME).absolutePath
        assertEquals("Generated config must live in filesDir", expectedPath, result.getOrNull())

        val file = File(expectedPath)
        assertTrue("Config file should exist", file.exists())

        val yaml = file.readText()
        // listen section
        assertTrue("YAML should contain listen section", yaml.contains("listen:"))
        assertTrue("YAML should contain default listen port 5080", yaml.contains("  port: 5080"))
        // socks5 section
        assertTrue("YAML should contain socks5 section", yaml.contains("socks5:"))
        assertTrue("YAML should contain socks5 host 127.0.0.1", yaml.contains("  host: 127.0.0.1"))
        assertTrue("YAML should contain socks5 port 1080", yaml.contains("  port: 1080"))
        // dns section
        assertTrue("YAML should contain dns section", yaml.contains("dns:"))
        assertTrue("YAML should contain upstream 1.1.1.1", yaml.contains("    - 1.1.1.1"))
        assertTrue("YAML should contain upstream 8.8.8.8", yaml.contains("    - 8.8.8.8"))
        assertTrue("YAML should force DNS over SOCKS5 (tcp: true)", yaml.contains("  tcp: true"))
        // log section
        assertTrue("YAML should contain log section", yaml.contains("log:"))
        assertTrue("YAML should contain log level info", yaml.contains("  level: info"))
    }

    @Test
    fun generateWritesCustomSocks5PortAndDnsViaSocks5False() {
        val result = HevSocks5TunnelConfigGenerator.generate(
            context,
            socks5Host = "127.0.0.1",
            socks5Port = 9999,
            dnsUpstreams = listOf("9.9.9.9"),
            dnsViaSocks5 = false,
            listenPort = 7000
        )

        assertTrue("generate should succeed", result.isSuccess)

        val yaml = File(result.getOrNull()!!).readText()
        assertTrue("YAML should contain custom listen port 7000", yaml.contains("  port: 7000"))
        assertTrue("YAML should contain custom socks5 port 9999", yaml.contains("  port: 9999"))
        assertTrue("YAML should contain custom upstream 9.9.9.9", yaml.contains("    - 9.9.9.9"))
        assertTrue("YAML should not force DNS over SOCKS5 (tcp: false)", yaml.contains("  tcp: false"))
    }

    @Test
    fun generateReturnsFailureForInvalidSocks5Port() {
        val result = HevSocks5TunnelConfigGenerator.generate(context, socks5Port = 0)

        assertTrue("Invalid port should produce a failure", result.isFailure)
        assertTrue(
            "Failure cause should be IllegalArgumentException",
            result.exceptionOrNull() is IllegalArgumentException
        )
        assertFalse(
            "No config file should be written on failure",
            File(context.filesDir, CONFIG_FILE_NAME).exists()
        )
    }

    companion object {
        private const val CONFIG_FILE_NAME = "hev_socks5_tunnel_dynamic.yaml"
    }
}