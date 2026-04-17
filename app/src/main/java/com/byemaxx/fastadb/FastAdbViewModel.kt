package com.byemaxx.fastadb

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val ACTION_USB_PERMISSION = "com.byemaxx.fastadb.USB_PERMISSION"

enum class DeviceMode(@param:StringRes val labelRes: Int) {
    Disconnected(R.string.mode_disconnected),
    PermissionRequired(R.string.mode_permission_required),
    Unsupported(R.string.mode_unsupported),
    Adb(R.string.mode_adb),
    Fastboot(R.string.mode_fastboot)
}

enum class TerminalKind {
    Command,
    Output,
    Error,
    System
}

data class DeviceFact(
    val label: String,
    val value: String
)

data class TerminalLine(
    val id: Long,
    val kind: TerminalKind,
    val text: String
) {
    companion object {
        fun bootstrap() = TerminalLine(
            id = 0L,
            kind = TerminalKind.System,
            text = "FASTADB is ready. Connect a device in ADB or Fastboot mode over OTG."
        )
    }
}

data class FastAdbUiState(
    val mode: DeviceMode = DeviceMode.Disconnected,
    val status: String = "",
    val deviceLabel: String = "",
    val deviceIdentifiers: String = "",
    val info: List<DeviceFact> = emptyList(),
    val commandInput: String = "",
    val terminal: List<TerminalLine> = listOf(TerminalLine.bootstrap()),
    val busy: Boolean = false,
    val permissionPending: Boolean = false
)

class FastAdbViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val usbManager: UsbManager = application.getSystemService(UsbManager::class.java)
    private val keyManager = AdbKeyManager()
    private val strings = StringResolver(application)
    private val permissionIntent: PendingIntent = PendingIntent.getBroadcast(
        application,
        0,
        Intent(ACTION_USB_PERMISSION).setPackage(application.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    private val _uiState = MutableStateFlow(
        FastAdbUiState(
            status = text(R.string.status_waiting_for_otg),
            deviceLabel = text(R.string.label_no_device),
            deviceIdentifiers = text(R.string.label_supported_interfaces)
        )
    )
    val uiState: StateFlow<FastAdbUiState> = _uiState.asStateFlow()

    private val sessionMutex = Mutex()
    private var currentSession: DeviceSession? = null
    private var currentDeviceId: Int? = null
    private var connectJob: Job? = null
    private var nextLineId = 1L

    fun onCommandInputChanged(value: String) {
        _uiState.update { it.copy(commandInput = value) }
    }

    fun clearTerminal() {
        _uiState.update {
            it.copy(
                terminal = listOf(
                    TerminalLine(
                        id = allocateLineId(),
                        kind = TerminalKind.System,
                        text = "Terminal cleared. New command output will appear here."
                    )
                )
            )
        }
    }

    fun refreshConnectedDevices() {
        val supported = findSupportedCandidate()
        when {
            supported == null && usbManager.deviceList.isEmpty() -> {
                disconnect(
                    mode = DeviceMode.Disconnected,
                    status = text(R.string.status_waiting_for_otg),
                    appendMessage = null
                )
            }

            supported == null -> {
                disconnect(
                    mode = DeviceMode.Unsupported,
                    status = text(R.string.status_no_supported_interface),
                    appendMessage = "The connected USB device does not expose an ADB or Fastboot interface."
                )
            }

            !usbManager.hasPermission(supported.device) -> {
                _uiState.update {
                    it.copy(
                        mode = DeviceMode.PermissionRequired,
                        status = text(
                            R.string.status_requesting_usb_permission,
                            modeLabel(supported.mode)
                        ),
                        deviceLabel = supported.friendlyName,
                        deviceIdentifiers = supported.identifiers,
                        permissionPending = true
                    )
                }
                appendLog(
                    TerminalKind.System,
                    "${modeLogLabel(supported.mode)} interface detected. Requesting USB permission."
                )
                usbManager.requestPermission(supported.device, permissionIntent)
            }

            currentDeviceId == supported.device.deviceId && _uiState.value.mode == supported.mode -> {
                _uiState.update {
                    it.copy(
                        status = text(R.string.status_terminal_ready),
                        permissionPending = false
                    )
                }
            }

            else -> connectToCandidate(supported)
        }
    }

    fun handleUsbIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_USB_PERMISSION -> {
                val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (device == null) {
                    appendLog(TerminalKind.Error, "USB permission callback did not include a device.")
                    refreshConnectedDevices()
                    return
                }
                if (granted) {
                    appendLog(TerminalKind.System, "USB permission granted. Opening device session.")
                } else {
                    appendLog(TerminalKind.Error, "USB permission denied. OTG device access was blocked.")
                }
            }

            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                appendLog(TerminalKind.System, "New OTG device attached. Detecting interface mode.")
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val detachedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (detachedDevice != null && detachedDevice.deviceId == currentDeviceId) {
                    disconnect(
                        mode = DeviceMode.Disconnected,
                        status = text(R.string.status_device_disconnected),
                        appendMessage = "The current OTG device was disconnected."
                    )
                }
            }
        }
        refreshConnectedDevices()
    }

    fun sendCustomCommand() {
        val command = uiState.value.commandInput.trim()
        if (command.isEmpty()) {
            appendLog(TerminalKind.Error, "Command is empty. Enter an ADB or Fastboot command first.")
            return
        }
        runWithSession {
            appendLog(TerminalKind.Command, command)
            it.executeCustomCommand(command) { kind, message ->
                appendLog(kind, message)
            }
            _uiState.update { state -> state.copy(commandInput = "") }
        }
    }

    fun sendQuickAction(action: QuickAction) {
        runWithSession {
            appendLog(TerminalKind.Command, describeQuickActionCommand(action, it.mode))
            it.executeQuickAction(action) { kind, message ->
                appendLog(kind, message)
            }
        }
    }

    private fun connectToCandidate(candidate: UsbCandidate) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                currentSession?.close()
                currentSession = null
                currentDeviceId = null
            }
            _uiState.update {
                it.copy(
                    mode = candidate.mode,
                    status = text(R.string.status_connecting_device, modeLabel(candidate.mode)),
                    deviceLabel = candidate.friendlyName,
                    deviceIdentifiers = candidate.identifiers,
                    busy = true,
                    permissionPending = false,
                    info = emptyList()
                )
            }
            appendLog(
                TerminalKind.System,
                "Opening ${modeLogLabel(candidate.mode)} channel and loading device details."
            )
            var session: DeviceSession? = null
            try {
                val connection = usbManager.openDevice(candidate.device)
                    ?: throw IOException("Unable to open a USB connection to the device.")
                val transport = UsbBulkTransport.open(
                    device = candidate.device,
                    connection = connection,
                    usbInterface = candidate.usbInterface
                )
                session = when (candidate.mode) {
                    DeviceMode.Adb -> AdbDeviceSession(transport, keyManager, strings)
                    DeviceMode.Fastboot -> FastbootDeviceSession(transport, strings)
                    else -> error("Unsupported mode ${candidate.mode}")
                }
                val snapshot = session.initialize { kind, message ->
                    appendLog(kind, message)
                }
                sessionMutex.withLock {
                    currentSession = session
                    currentDeviceId = candidate.device.deviceId
                }
                _uiState.update {
                    it.copy(
                        mode = candidate.mode,
                        status = text(R.string.status_device_synced),
                        deviceLabel = snapshot.title,
                        deviceIdentifiers = candidate.identifiers,
                        info = snapshot.facts,
                        busy = false,
                        permissionPending = false
                    )
                }
                appendLog(
                    TerminalKind.System,
                    "${modeLogLabel(candidate.mode)} session established. You can now use quick actions or send commands."
                )
            } catch (error: Throwable) {
                session?.close()
                disconnect(
                    mode = DeviceMode.Disconnected,
                    status = text(R.string.status_connection_failed),
                    appendMessage = error.userFacingMessage()
                )
            }
        }
    }

    private fun runWithSession(
        block: suspend (DeviceSession) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = sessionMutex.withLock { currentSession }
            if (session == null) {
                appendLog(TerminalKind.Error, "No active device session. Connect an OTG device first.")
                return@launch
            }
            _uiState.update { it.copy(busy = true) }
            try {
                block(session)
            } catch (error: Throwable) {
                appendLog(TerminalKind.Error, error.userFacingMessage())
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun disconnect(
        mode: DeviceMode,
        status: String,
        appendMessage: String?
    ) {
        connectJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                currentSession?.close()
                currentSession = null
                currentDeviceId = null
            }
            _uiState.update {
                it.copy(
                    mode = mode,
                    status = status,
                    deviceLabel = text(R.string.label_no_device),
                    deviceIdentifiers = text(R.string.label_supported_interfaces),
                    info = emptyList(),
                    busy = false,
                    permissionPending = false
                )
            }
            appendMessage?.let { appendLog(TerminalKind.System, it) }
        }
    }

    private fun appendLog(kind: TerminalKind, message: String) {
        val cleaned = message.trimEnd()
        if (cleaned.isEmpty()) return
        val lines = cleaned.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                terminal = state.terminal + lines.map { line ->
                    TerminalLine(
                        id = allocateLineId(),
                        kind = kind,
                        text = line
                    )
                }
            )
        }
    }

    private fun findSupportedCandidate(): UsbCandidate? {
        val devices = usbManager.deviceList.values.sortedBy { it.deviceId }
        return devices.firstNotNullOfOrNull { device ->
            findSupportedInterface(device)?.let { usbInterface ->
                val mode = when (usbInterface.interfaceProtocol) {
                    ADB_INTERFACE_PROTOCOL -> DeviceMode.Adb
                    FASTBOOT_INTERFACE_PROTOCOL -> DeviceMode.Fastboot
                    else -> null
                } ?: return@let null
                UsbCandidate(
                    device = device,
                    usbInterface = usbInterface,
                    mode = mode,
                    friendlyName = device.productName
                        ?.takeIf { it.isNotBlank() }
                        ?: text(R.string.label_usb_device, modeLabel(mode)),
                    identifiers = buildString {
                        append("VID ")
                        append(device.vendorId.toHex())
                        append(" / PID ")
                        append(device.productId.toHex())
                        append(" / IF ")
                        append(usbInterface.id)
                    }
                )
            }
        }
    }

    private fun findSupportedInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (
                usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                usbInterface.interfaceSubclass == ADB_FASTBOOT_INTERFACE_SUBCLASS &&
                (
                    usbInterface.interfaceProtocol == ADB_INTERFACE_PROTOCOL ||
                        usbInterface.interfaceProtocol == FASTBOOT_INTERFACE_PROTOCOL
                    )
            ) {
                return usbInterface
            }
        }
        return null
    }

    private fun Int.toHex(): String = String.format(Locale.US, "0x%04X", this and 0xFFFF)

    private fun modeLabel(mode: DeviceMode): String = text(mode.labelRes)

    private fun modeLogLabel(mode: DeviceMode): String = when (mode) {
        DeviceMode.Disconnected -> "Disconnected"
        DeviceMode.PermissionRequired -> "Permission"
        DeviceMode.Unsupported -> "Unsupported"
        DeviceMode.Adb -> "ADB"
        DeviceMode.Fastboot -> "Fastboot"
    }

    private fun text(@StringRes resId: Int, vararg args: Any): String = strings.get(resId, *args)

    private fun Throwable.userFacingMessage(): String {
        return message?.takeIf { it.isNotBlank() } ?: "An unknown error occurred. The command did not complete."
    }

    private fun allocateLineId(): Long = nextLineId++
}

internal fun describeQuickActionCommand(action: QuickAction, mode: DeviceMode): String = when (action) {
    QuickAction.RebootBootloader -> when (mode) {
        DeviceMode.Adb -> "adb reboot bootloader"
        DeviceMode.Fastboot -> "fastboot reboot-bootloader"
        else -> "reboot bootloader"
    }

    QuickAction.SetGpuPreemptionPermissive ->
        "fastboot oem set-gpu-preemption 0 androidboot.selinux=permissive"

    QuickAction.FastbootContinue -> "fastboot continue"
}

private data class UsbCandidate(
    val device: UsbDevice,
    val usbInterface: UsbInterface,
    val mode: DeviceMode,
    val friendlyName: String,
    val identifiers: String
)

class StringResolver(
    private val application: Application
) {
    fun get(@StringRes resId: Int, vararg args: Any): String = application.getString(resId, *args)
}
