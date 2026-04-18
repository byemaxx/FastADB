package com.byemaxx.fastadb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Locale

const val ADB_FASTBOOT_INTERFACE_SUBCLASS = 0x42
const val ADB_INTERFACE_PROTOCOL = 0x01
const val FASTBOOT_INTERFACE_PROTOCOL = 0x03

private const val BULK_TIMEOUT_MS = 5_000
private const val CONNECT_TIMEOUT_MS = 45_000
private const val ADB_VERSION = 0x01000000
private const val ADB_MAX_PAYLOAD = 4 * 1024
private const val ADB_TOKEN_SIZE = 20
private const val ANDROID_PUBKEY_MODULUS_SIZE = 256
private const val ANDROID_PUBKEY_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
private const val A_SYNC = 0x434e5953
private const val A_CNXN = 0x4e584e43
private const val A_OPEN = 0x4e45504f
private const val A_OKAY = 0x59414b4f
private const val A_CLSE = 0x45534c43
private const val A_WRTE = 0x45545257
private const val A_AUTH = 0x48545541
private const val ADB_AUTH_TOKEN = 1
private const val ADB_AUTH_SIGNATURE = 2
private const val ADB_AUTH_RSAPUBLICKEY = 3

enum class QuickAction {
    RebootBootloader,
    SetSelinuxPermissiveThenContinue
}

data class DeviceSnapshot(
    val title: String,
    val facts: List<DeviceFact>
)

typealias LogSink = (TerminalKind, String) -> Unit

interface DeviceSession : Closeable {
    val mode: DeviceMode

    fun initialize(logger: LogSink): DeviceSnapshot

    fun executeQuickAction(action: QuickAction, logger: LogSink)

    fun executeCustomCommand(command: String, logger: LogSink)
}

class UsbBulkTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inputEndpoint: UsbEndpoint,
    private val outputEndpoint: UsbEndpoint
) : Closeable {

    private val inputPacketSize = inputEndpoint.maxPacketSize.coerceAtLeast(4)

    fun readExactly(length: Int, timeoutMs: Int = BULK_TIMEOUT_MS): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = connection.bulkTransfer(
                inputEndpoint,
                result,
                offset,
                length - offset,
                timeoutMs
            )
            if (read <= 0) {
                throw IOException("USB read failed. The device may have disconnected.")
            }
            offset += read
        }
        return result
    }

    fun readChunk(maxLength: Int, timeoutMs: Int = BULK_TIMEOUT_MS): ByteArray {
        val buffer = ByteArray(maxLength)
        val read = connection.bulkTransfer(
            inputEndpoint,
            buffer,
            0,
            maxLength,
            timeoutMs
        )
        if (read <= 0) {
            throw IOException("USB read failed. No data was returned by the device.")
        }
        return buffer.copyOf(read)
    }

    fun readPacket(timeoutMs: Int = BULK_TIMEOUT_MS): ByteArray = readChunk(inputPacketSize, timeoutMs)

    fun writeAll(bytes: ByteArray, timeoutMs: Int = BULK_TIMEOUT_MS) {
        var offset = 0
        while (offset < bytes.size) {
            val written = connection.bulkTransfer(
                outputEndpoint,
                bytes,
                offset,
                bytes.size - offset,
                timeoutMs
            )
            if (written <= 0) {
                throw IOException("USB write failed. The command was not sent.")
            }
            offset += written
        }
    }

    override fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } finally {
            connection.close()
        }
    }

    companion object {
        fun open(
            device: UsbDevice,
            connection: UsbDeviceConnection,
            usbInterface: UsbInterface
        ): UsbBulkTransport {
            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                throw IOException("Unable to claim USB interface ${usbInterface.id}.")
            }
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (index in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(index)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> input = endpoint
                    UsbConstants.USB_DIR_OUT -> output = endpoint
                }
            }
            val inputEndpoint = input
                ?: throw IOException("No usable USB Bulk IN endpoint was found.")
            val outputEndpoint = output
                ?: throw IOException("No usable USB Bulk OUT endpoint was found.")
            return UsbBulkTransport(connection, usbInterface, inputEndpoint, outputEndpoint)
        }
    }
}

class FastbootDeviceSession(
    private val transport: UsbBulkTransport,
    private val strings: StringResolver
) : DeviceSession {
    override val mode: DeviceMode = DeviceMode.Fastboot

    override fun initialize(logger: LogSink): DeviceSnapshot {
        val facts = buildList {
            addFact(this, strings.get(R.string.fact_connection_mode), "Fastboot")
            addOptionalFact(this, strings.get(R.string.fact_product), safeGetVar("product", logger))
            addOptionalFact(this, strings.get(R.string.fact_serial_number), safeGetVar("serialno", logger))
            addOptionalFact(this, strings.get(R.string.fact_bootloader), safeGetVar("version-bootloader", logger))
            addOptionalFact(this, strings.get(R.string.fact_secure), safeGetVar("secure", logger))
            addOptionalFact(this, strings.get(R.string.fact_current_slot), safeGetVar("current-slot", logger))
            addOptionalFact(this, strings.get(R.string.fact_userspace_fastboot), safeGetVar("is-userspace", logger))
        }
        val title = facts.firstOrNull { it.label == strings.get(R.string.fact_product) }?.value
            ?: strings.get(R.string.title_fastboot_device)
        return DeviceSnapshot(title = title, facts = facts)
    }

    override fun executeQuickAction(action: QuickAction, logger: LogSink) {
        when (action) {
            QuickAction.RebootBootloader -> executeRawCommand(
                command = "reboot-bootloader",
                logger = logger,
                logCompletionMessage = true
            )
            QuickAction.SetSelinuxPermissiveThenContinue -> {
                try {
                    executeRawCommand(
                        command = "oem set-gpu-preemption 0 androidboot.selinux=permissive",
                        logger = logger,
                        logCompletionMessage = true
                    )
                } catch (error: IOException) {
                    logger(
                        TerminalKind.Error,
                        error.message?.takeIf { it.isNotBlank() }
                            ?: "SELinux permissive command failed."
                    )
                    logger(
                        TerminalKind.System,
                        "Skipped 'fastboot continue' because the previous command was rejected."
                    )
                    return
                }
                executeRawCommand(
                    command = "continue",
                    logger = logger,
                    logCompletionMessage = true
                )
            }
        }
    }

    override fun executeCustomCommand(command: String, logger: LogSink) {
        val normalized = normalizeFastbootCommand(command)
        executeRawCommand(normalized, logger, logCompletionMessage = true)
    }

    override fun close() {
        transport.close()
    }

    private fun safeGetVar(name: String, logger: LogSink): String? {
        return try {
            transact("getvar:$name", logger, logInfoLines = false).ifBlank { null }
        } catch (_: Throwable) {
            null
        }
    }

    private fun executeRawCommand(
        command: String,
        logger: LogSink,
        logCompletionMessage: Boolean
    ) {
        val result = transact(command, logger, logInfoLines = true)
        if (result.isNotBlank()) {
            logger(TerminalKind.Output, result)
        } else if (logCompletionMessage) {
            logger(TerminalKind.System, "Fastboot command accepted by the device.")
        }
    }

    private fun transact(
        command: String,
        logger: LogSink,
        logInfoLines: Boolean
    ): String {
        transport.writeAll(command.toByteArray(StandardCharsets.US_ASCII))
        val okayPayload = StringBuilder()
        while (true) {
            val response = transport.readPacket()
            if (response.size < 4) {
                throw IOException("Fastboot response packet was shorter than expected.")
            }
            val tag = response.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII)
            val body = response
                .copyOfRange(4, response.size)
                .toString(StandardCharsets.UTF_8)
                .trimEnd('\u0000', '\r', '\n', ' ', '\t')
            when (tag) {
                "INFO", "TEXT" -> if (logInfoLines && body.isNotBlank()) {
                    logger(TerminalKind.Output, body)
                }

                "OKAY" -> {
                    if (body.isNotBlank()) {
                        okayPayload.append(body)
                    }
                    return okayPayload.toString()
                }

                "FAIL" -> throw IOException(body.ifBlank { "Fastboot command failed." })
                "DATA" -> throw IOException("DATA-phase Fastboot commands are not supported yet.")
                else -> throw IOException("Unknown Fastboot response: $tag")
            }
        }
    }
}

class AdbDeviceSession(
    private val transport: UsbBulkTransport,
    private val keyManager: AdbKeyManager,
    private val strings: StringResolver
) : DeviceSession {
    override val mode: DeviceMode = DeviceMode.Adb

    private var nextLocalId = 1
    private var connected = false
    private var peerMaxPayload = ADB_MAX_PAYLOAD
    private var banner = ""

    override fun initialize(logger: LogSink): DeviceSnapshot {
        connect(logger)
        val bannerProperties = parseBannerProperties(banner)
        val manufacturer = resolveProperty("ro.product.manufacturer", bannerProperties, logger)
        val model = resolveProperty("ro.product.model", bannerProperties, logger)
        val deviceName = resolveProperty("ro.product.device", bannerProperties, logger)
        val buildId = resolveProperty("ro.build.display.id", bannerProperties, logger)
        val bootMode = resolveProperty("ro.bootmode", bannerProperties, logger)
        val serialFromBanner = parseSerialFromBanner(banner)
        val facts = buildList {
            addFact(this, strings.get(R.string.fact_connection_mode), "ADB")
            addOptionalFact(this, strings.get(R.string.fact_manufacturer), manufacturer)
            addOptionalFact(this, strings.get(R.string.fact_model), model)
            addOptionalFact(this, strings.get(R.string.fact_device_codename), deviceName)
            addOptionalFact(this, strings.get(R.string.fact_build_version), buildId)
            addOptionalFact(this, strings.get(R.string.fact_boot_mode), bootMode)
            addOptionalFact(this, strings.get(R.string.fact_serial_number), serialFromBanner)
        }
        val title = listOf(manufacturer, model).filter { !it.isNullOrBlank() }.joinToString(" ")
            .ifBlank { strings.get(R.string.title_adb_device) }
        return DeviceSnapshot(title = title, facts = facts)
    }

    override fun executeQuickAction(action: QuickAction, logger: LogSink) {
        when (action) {
            QuickAction.RebootBootloader -> rebootBootloader(logger, verbose = true)
            QuickAction.SetSelinuxPermissiveThenContinue ->
                throw IOException("This quick action is only available in Fastboot mode.")
        }
    }

    override fun executeCustomCommand(command: String, logger: LogSink) {
        when (val request = parseAdbCommand(command)) {
            is AdbRequest.Reboot -> {
                val target = request.target?.takeIf { it.isNotBlank() }
                sendOneWayService("reboot:${target.orEmpty()}", logger)
                logger(TerminalKind.System, "ADB reboot command sent. The device may disconnect and reconnect.")
            }

            is AdbRequest.RawService -> {
                val output = openService(request.service)
                if (output.isNotBlank()) {
                    logger(TerminalKind.Output, output)
                } else {
                    logger(TerminalKind.System, "ADB service command sent.")
                }
            }

            is AdbRequest.Shell -> {
                val output = openService("shell:${request.command}")
                if (output.isNotBlank()) {
                    logger(TerminalKind.Output, output)
                } else {
                    logger(TerminalKind.System, "Shell command completed with no output.")
                }
            }
        }
    }

    override fun close() {
        transport.close()
    }

    private fun connect(logger: LogSink) {
        val connectBanner = "host::features=shell_v2,cmd"
        sendPacket(
            command = A_CNXN,
            arg0 = ADB_VERSION,
            arg1 = ADB_MAX_PAYLOAD,
            payload = connectBanner.toByteArray(StandardCharsets.UTF_8)
        )
        var publicKeySent = false
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val packet = readPacket(timeoutMs = BULK_TIMEOUT_MS)
            when (packet.command) {
                A_AUTH -> {
                    if (packet.arg0 != ADB_AUTH_TOKEN) {
                        throw IOException("ADB returned an unknown AUTH packet type.")
                    }
                    if (!publicKeySent && packet.payload.size == ADB_TOKEN_SIZE) {
                        logger(TerminalKind.System, "ADB is validating the host key.")
                        sendPacket(
                            command = A_AUTH,
                            arg0 = ADB_AUTH_SIGNATURE,
                            arg1 = 0,
                            payload = keyManager.sign(packet.payload)
                        )
                        publicKeySent = true
                    } else {
                        logger(TerminalKind.System, "Confirm the USB debugging prompt on the target device.")
                        sendPacket(
                            command = A_AUTH,
                            arg0 = ADB_AUTH_RSAPUBLICKEY,
                            arg1 = 0,
                            payload = keyManager.publicKeyPayload()
                        )
                    }
                }

                A_CNXN -> {
                    connected = true
                    peerMaxPayload = minOf(packet.arg1, ADB_MAX_PAYLOAD)
                    banner = packet.payload.toString(StandardCharsets.UTF_8)
                    logger(TerminalKind.System, "ADB handshake completed.")
                    return
                }

                A_SYNC -> continue
                else -> throw IOException("Unknown packet during ADB handshake: ${packet.command.toHexString()}")
            }
        }
        throw IOException("ADB connection timed out. Check whether USB debugging is enabled and authorized.")
    }

    private fun rebootBootloader(logger: LogSink) {
        rebootBootloader(logger, verbose = true)
    }

    private fun rebootBootloader(logger: LogSink, verbose: Boolean) {
        try {
            sendOneWayService("reboot:bootloader", logger, verbose)
        } catch (_: Throwable) {
            sendOneWayService("shell:reboot bootloader", logger, verbose)
        }
        if (verbose) {
            logger(TerminalKind.System, "Device is rebooting to bootloader. The USB session may drop shortly.")
        }
    }

    private fun resolveProperty(
        name: String,
        bannerProperties: Map<String, String>,
        logger: LogSink
    ): String? {
        val fromBanner = bannerProperties[name]
        if (!fromBanner.isNullOrBlank()) {
            return fromBanner
        }
        return try {
            openService("shell:getprop $name").trim().ifBlank { null }
        } catch (_: Throwable) {
            logger(TerminalKind.System, "Failed to read property $name.")
            null
        }
    }

    private fun sendOneWayService(service: String, logger: LogSink, verbose: Boolean = true) {
        try {
            val output = openService(service)
            if (output.isNotBlank()) {
                logger(TerminalKind.Output, output)
            }
        } catch (error: IOException) {
            if (verbose) {
                logger(
                    TerminalKind.System,
                    error.message ?: "The device started rebooting and the connection is closing."
                )
            }
        }
    }

    private fun openService(service: String): String {
        checkConnected()
        val payload = (service + '\u0000').toByteArray(StandardCharsets.UTF_8)
        if (payload.size > peerMaxPayload) {
            throw IOException("ADB service string is longer than the payload size supported by the device.")
        }
        val localId = nextLocalId++
        sendPacket(A_OPEN, localId, 0, payload)
        var remoteId = 0
        val output = ByteArrayOutputStream()
        while (true) {
            val packet = readPacket(timeoutMs = BULK_TIMEOUT_MS)
            when (packet.command) {
                A_OKAY -> {
                    if (packet.arg1 == localId) {
                        remoteId = packet.arg0
                    }
                }

                A_WRTE -> {
                    if (packet.arg1 == localId) {
                        remoteId = packet.arg0
                        output.write(packet.payload)
                        sendPacket(A_OKAY, localId, remoteId, ByteArray(0))
                    }
                }

                A_CLSE -> {
                    if (packet.arg1 == localId || remoteId == 0) {
                        val closeRemoteId = if (remoteId == 0) packet.arg0 else remoteId
                        sendPacket(A_CLSE, localId, closeRemoteId, ByteArray(0))
                        return output.toString(StandardCharsets.UTF_8.name()).trim()
                    }
                }

                else -> {
                    if (packet.command == A_AUTH) {
                        throw IOException("ADB session became invalid. Reconnect and confirm USB debugging authorization.")
                    }
                }
            }
        }
    }

    private fun sendPacket(
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray
    ) {
        val header = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(payload.sumOf { it.toUByte().toInt() })
            .putInt(command xor -1)
            .array()
        transport.writeAll(header)
        if (payload.isNotEmpty()) {
            transport.writeAll(payload)
        }
    }

    private fun readPacket(timeoutMs: Int): AdbPacket {
        val header = transport.readExactly(24, timeoutMs)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val payloadLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        if (magic != (command xor -1)) {
            throw IOException("ADB header validation failed.")
        }
        val payload = if (payloadLength > 0) {
            transport.readExactly(payloadLength, timeoutMs)
        } else {
            ByteArray(0)
        }
        val payloadChecksum = payload.sumOf { it.toUByte().toInt() }
        if (checksum != payloadChecksum) {
            throw IOException("ADB payload checksum validation failed.")
        }
        return AdbPacket(command, arg0, arg1, payload)
    }

    private fun parseBannerProperties(banner: String): Map<String, String> {
        val propertySection = banner.substringAfterLast(':', "")
        if (propertySection.isBlank()) return emptyMap()
        return propertySection
            .split(';')
            .mapNotNull { entry ->
                val index = entry.indexOf('=')
                if (index <= 0 || index >= entry.lastIndex) {
                    null
                } else {
                    entry.substring(0, index) to entry.substring(index + 1)
                }
            }
            .toMap()
    }

    private fun parseSerialFromBanner(banner: String): String? {
        val sections = banner.split(':')
        return sections.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun checkConnected() {
        if (!connected) {
            throw IOException("ADB session has not been established yet.")
        }
    }
}

private data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray
)

sealed interface AdbRequest {
    data class Shell(val command: String) : AdbRequest
    data class Reboot(val target: String?) : AdbRequest
    data class RawService(val service: String) : AdbRequest
}

fun parseAdbCommand(command: String): AdbRequest {
    val trimmed = command.trim()
    val body = trimmed.removePrefix("adb").trim()
    return when {
        body.startsWith("reboot ") -> AdbRequest.Reboot(body.removePrefix("reboot").trim())
        body == "reboot" -> AdbRequest.Reboot(null)
        body.startsWith("shell ") -> AdbRequest.Shell(body.removePrefix("shell").trim())
        body.startsWith("service ") -> AdbRequest.RawService(body.removePrefix("service ").trim())
        trimmed.startsWith("shell:") || trimmed.startsWith("reboot:") -> AdbRequest.RawService(trimmed)
        else -> AdbRequest.Shell(body.ifBlank { trimmed })
    }
}

fun normalizeFastbootCommand(command: String): String {
    val trimmed = command.trim()
    val body = trimmed.removePrefix("fastboot").trim()
    return when {
        body.startsWith("getvar ") -> "getvar:${body.removePrefix("getvar ").trim()}"
        body.isNotBlank() -> body
        else -> trimmed
    }
}

class AdbKeyManager {
    private val keyAlias = "fastadb_otg_key"

    fun sign(token: ByteArray): ByteArray {
        val keyPair = getOrCreateKeyPair()
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(keyPair.private)
        signature.update(token)
        return signature.sign()
    }

    fun publicKeyPayload(): ByteArray {
        val publicKey = getOrCreateKeyPair().public as RSAPublicKey
        val encoded = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putInt(ANDROID_PUBKEY_WORDS)
                putInt(computeN0Inv(publicKey.modulus).toInt())
                put(bigIntegerToLittleEndian(publicKey.modulus, ANDROID_PUBKEY_WORDS))
                put(bigIntegerToLittleEndian(computeRr(publicKey.modulus), ANDROID_PUBKEY_WORDS))
                putInt(publicKey.publicExponent.toInt())
            }
            .array()
        val base64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
        return (base64 + " fastadb@android" + '\u0000').toByteArray(StandardCharsets.US_ASCII)
    }

    private fun getOrCreateKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) {
            return KeyPair(existing.certificate.publicKey, existing.privateKey)
        }
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .build()
        generator.initialize(spec)
        return generator.generateKeyPair()
    }

    private fun computeN0Inv(modulus: BigInteger): Long {
        val twoTo32 = BigInteger.ONE.shiftLeft(32)
        val modulus0 = modulus.mod(twoTo32)
        val inverse = modulus0.modInverse(twoTo32)
        return twoTo32.subtract(inverse).mod(twoTo32).toLong()
    }

    private fun computeRr(modulus: BigInteger): BigInteger {
        return BigInteger.ONE.shiftLeft(ANDROID_PUBKEY_MODULUS_SIZE * 16).mod(modulus)
    }

    private fun bigIntegerToLittleEndian(value: BigInteger, wordCount: Int): ByteArray {
        val output = ByteArray(wordCount * 4)
        var remaining = value
        val mask = BigInteger("ffffffff", 16)
        repeat(wordCount) { index ->
            val word = remaining.and(mask).toLong()
            val offset = index * 4
            output[offset] = (word and 0xFF).toByte()
            output[offset + 1] = ((word shr 8) and 0xFF).toByte()
            output[offset + 2] = ((word shr 16) and 0xFF).toByte()
            output[offset + 3] = ((word shr 24) and 0xFF).toByte()
            remaining = remaining.shiftRight(32)
        }
        return output
    }
}

private fun addFact(target: MutableList<DeviceFact>, label: String, value: String) {
    target.add(DeviceFact(label = label, value = value))
}

private fun addOptionalFact(target: MutableList<DeviceFact>, label: String, value: String?) {
    if (!value.isNullOrBlank()) {
        target.add(DeviceFact(label = label, value = value))
    }
}

private fun Int.toHexString(): String = String.format(Locale.US, "0x%08X", this)
