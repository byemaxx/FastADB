package com.byemaxx.fastadb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    @Test
    fun normalizesFastbootGetvarSyntax() {
        assertEquals("getvar:product", normalizeFastbootCommand("fastboot getvar product"))
        assertEquals("continue", normalizeFastbootCommand("fastboot continue"))
    }

    @Test
    fun parsesAdbShellCommand() {
        val request = parseAdbCommand("adb shell getprop ro.product.model")
        assertTrue(request is AdbRequest.Shell)
        assertEquals("getprop ro.product.model", (request as AdbRequest.Shell).command)
    }

    @Test
    fun parsesAdbRebootBootloader() {
        val request = parseAdbCommand("adb reboot bootloader")
        assertTrue(request is AdbRequest.Reboot)
        assertEquals("bootloader", (request as AdbRequest.Reboot).target)
    }
}
