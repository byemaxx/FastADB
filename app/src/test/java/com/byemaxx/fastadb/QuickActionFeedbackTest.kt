package com.byemaxx.fastadb

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickActionFeedbackTest {

    @Test
    fun describesRebootBootloaderForAdbMode() {
        assertEquals(
            "adb reboot bootloader",
            describeQuickActionCommand(QuickAction.RebootBootloader, DeviceMode.Adb)
        )
    }

    @Test
    fun describesRebootBootloaderForFastbootMode() {
        assertEquals(
            "fastboot reboot-bootloader",
            describeQuickActionCommand(QuickAction.RebootBootloader, DeviceMode.Fastboot)
        )
    }

    @Test
    fun describesFastbootQuickActions() {
        assertEquals(
            "fastboot oem set-gpu-preemption 0 androidboot.selinux=permissive && fastboot continue",
            describeQuickActionCommand(QuickAction.SetSelinuxPermissiveThenContinue, DeviceMode.Fastboot)
        )
    }
}
