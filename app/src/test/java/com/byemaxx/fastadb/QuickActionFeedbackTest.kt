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
            "fastboot oem set-gpu-preemption 0 androidboot.selinux=permissive",
            describeQuickActionCommand(QuickAction.SetGpuPreemptionPermissive, DeviceMode.Fastboot)
        )
        assertEquals(
            "fastboot continue",
            describeQuickActionCommand(QuickAction.FastbootContinue, DeviceMode.Fastboot)
        )
    }

    @Test
    fun describesQuickActionSuccessMessages() {
        assertEquals(
            "Reboot command sent. The device may disconnect and reconnect in bootloader mode.",
            describeQuickActionSuccess(QuickAction.RebootBootloader)
        )
        assertEquals(
            "The set-gpu-preemption command was accepted by the device.",
            describeQuickActionSuccess(QuickAction.SetGpuPreemptionPermissive)
        )
        assertEquals(
            "Fastboot continue command sent. The device may leave Fastboot mode.",
            describeQuickActionSuccess(QuickAction.FastbootContinue)
        )
    }
}
