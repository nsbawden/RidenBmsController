package com.example.ridenbmscontroller.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID

class BmsBleScanner(
    context: Context,
    private val onStateChanged: (BmsBleUiState) -> Unit
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val devicesByAddress = linkedMapOf<String, BmsBleDevice>()
    private var gatt: BluetoothGatt? = null
    private var connecting = false
    private var connectedDeviceName: String? = null
    private var connectedDeviceAddress: String? = null
    private var gattServices = emptyList<BmsGattServiceInfo>()
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val rawPackets = mutableListOf<String>()
    private val rxFrameBuffer = mutableListOf<Byte>()
    private var telemetry = BmsDecodedTelemetry()
    private var autoConnectAddress: String? = prefs.getString(PREF_BMS_ADDRESS, null)
    private var scanning = false
    private var status = "Ready"

    private val scanTimeoutRunnable = Runnable { stopScan("Scan complete") }
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (connectedDeviceAddress != null && writeCharacteristic != null) {
                requestBmsSnapshot()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runCatching {
                val device = result.device
                val name = readDeviceName(result)
                val address = runCatching { device.address }.getOrNull() ?: "Unknown address"

                devicesByAddress[address] = BmsBleDevice(
                    name = name.ifBlank { "Unknown BLE device" },
                    address = address,
                    rssi = result.rssi,
                    lastSeenMs = System.currentTimeMillis()
                )
                publish("Scanning: ${devicesByAddress.size} device(s) found")
                if (address == autoConnectAddress && connectedDeviceAddress == null && !connecting) {
                    connect(devicesByAddress.getValue(address), remember = false)
                }
            }.onFailure {
                publish("Scan result blocked: ${it.message ?: it.javaClass.simpleName}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            publish("Scan failed: code $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connecting = false
                        publish("Connected, discovering services...")
                        runCatching { gatt.discoverServices() }
                            .onFailure { publish("Service discovery failed: ${it.message ?: it.javaClass.simpleName}") }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connecting = false
                        connectedDeviceName = null
                        connectedDeviceAddress = null
                        gattServices = emptyList()
                        stopPolling()
                        publish(if (status == BluetoothGatt.GATT_SUCCESS) "Disconnected" else "Disconnected: GATT $status")
                        closeGatt()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    publish("Service discovery error: GATT $status")
                    return@post
                }

                gattServices = gatt.services.map { service ->
                    BmsGattServiceInfo(
                        uuid = service.uuid.toString(),
                        characteristics = service.characteristics.map { characteristic ->
                            BmsGattCharacteristicInfo(
                                uuid = characteristic.uuid.toString(),
                                properties = characteristic.properties.toPropertyText()
                            )
                        }
                    )
                }
                findJbdCharacteristics(gatt)
                publish("Services discovered: ${gattServices.size}")
                enableJbdNotifications()
                startPolling()
            }
        }

        @Deprecated("Android framework still calls this overload on older API levels")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handler.post {
                recordPacket("RX ${characteristic.uuid}: ${characteristic.value.toHexString()}")
                acceptJbdBytes(characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handler.post {
                recordPacket("RX ${characteristic.uuid}: ${value.toHexString()}")
                acceptJbdBytes(value)
            }
        }
    }

    fun refresh() {
        publish(status)
        if (hasPermissions() && autoConnectAddress != null && connectedDeviceAddress == null && !connecting && !scanning) {
            startScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions()) {
            scanning = false
            publish("Bluetooth permission needed")
            return
        }

        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            scanning = false
            publish("Bluetooth LE not supported")
            return
        }
        if (!adapter.isEnabled) {
            scanning = false
            publish("Bluetooth is off")
            return
        }
        if (!isLocationEnabled()) {
            scanning = false
            publish("Phone Location must be on for BLE scanning")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            scanning = false
            publish("BLE scanner unavailable")
            return
        }

        devicesByAddress.clear()
        scanning = true
        publish("Scanning for BMS...")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        scanner.startScan(null, settings, scanCallback)
        handler.removeCallbacks(scanTimeoutRunnable)
        handler.postDelayed(scanTimeoutRunnable, SCAN_WINDOW_MS)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BmsBleDevice, remember: Boolean = true) {
        if (!hasPermissions()) {
            publish("Bluetooth permission needed")
            return
        }

        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            publish("Bluetooth is off")
            return
        }

        stopScan("Connecting to ${device.name}")
        closeGatt()
        if (remember) {
            autoConnectAddress = device.address
            prefs.edit()
                .putString(PREF_BMS_ADDRESS, device.address)
                .putString(PREF_BMS_NAME, device.name)
                .apply()
        }
        connecting = true
        connectedDeviceName = device.name
        connectedDeviceAddress = device.address
        gattServices = emptyList()
        rawPackets.clear()
        rxFrameBuffer.clear()
        telemetry = BmsDecodedTelemetry()
        notifyCharacteristic = null
        writeCharacteristic = null
        stopPolling()
        publish("Connecting to ${device.name}...")

        runCatching {
            adapter.getRemoteDevice(device.address)
                .connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.onSuccess {
            gatt = it
        }.onFailure {
            connecting = false
            connectedDeviceName = null
            connectedDeviceAddress = null
            publish("Connect failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun disconnect() {
        publish("Disconnecting...")
        gatt?.disconnect()
        closeGatt()
        connecting = false
        connectedDeviceName = null
        connectedDeviceAddress = null
        gattServices = emptyList()
        rawPackets.clear()
        rxFrameBuffer.clear()
        telemetry = BmsDecodedTelemetry()
        notifyCharacteristic = null
        writeCharacteristic = null
        stopPolling()
        publish("Disconnected")
    }

    @SuppressLint("MissingPermission")
    fun requestBmsSnapshot() {
        if (!hasPermissions()) {
            publish("Bluetooth permission needed")
            return
        }
        val gatt = gatt
        val write = writeCharacteristic
        if (gatt == null || write == null) {
            publish("JBD write characteristic not ready")
            return
        }

        writeJbdFrame(gatt, write, BASIC_INFO_REQUEST, "basic info")
        handler.postDelayed({
            writeJbdFrame(gatt, write, CELL_INFO_REQUEST, "cell info")
        }, 350L)
    }

    @SuppressLint("MissingPermission")
    fun stopScan(doneStatus: String = "Scan stopped") {
        val adapter = bluetoothManager?.adapter
        if (scanning && hasPermissions()) {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        handler.removeCallbacks(scanTimeoutRunnable)
        scanning = false
        publish(doneStatus)
    }

    fun hasPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun publish(message: String) {
        status = message
        val adapter = bluetoothManager?.adapter
        onStateChanged(
            BmsBleUiState(
                supported = adapter != null,
                bluetoothEnabled = adapter?.isEnabled == true,
                locationEnabled = isLocationEnabled(),
                hasPermissions = hasPermissions(),
                scanning = scanning,
                connecting = connecting,
                connectedDeviceName = connectedDeviceName,
                connectedDeviceAddress = connectedDeviceAddress,
                status = status,
                devices = devicesByAddress.values
                    .sortedWith(
                        compareByDescending<BmsBleDevice> { it.looksLikeBattery }
                            .thenByDescending { it.rssi }
                    ),
                gattServices = gattServices,
                rawPackets = rawPackets.toList(),
                telemetry = telemetry
            )
        )
    }

    private fun readDeviceName(result: ScanResult): String {
        return result.scanRecord?.deviceName
            ?: runCatching { result.device.name }.getOrNull()
            ?: ""
    }

    private fun isLocationEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager?.isLocationEnabled == true
        } else {
            val gps = runCatching { locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull() == true
            val network = runCatching { locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrNull() == true
            gps || network
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        runCatching { gatt?.close() }
        gatt = null
    }

    private fun startPolling() {
        stopPolling()
        handler.postDelayed(pollRunnable, 500L)
    }

    private fun stopPolling() {
        handler.removeCallbacks(pollRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun enableJbdNotifications() {
        val gatt = gatt ?: return
        val characteristic = notifyCharacteristic ?: return
        if (!hasPermissions()) return

        val enabled = gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (enabled && descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
            recordPacket("Notifications enabled on ${characteristic.uuid}")
        } else {
            recordPacket("Notify setup incomplete on ${characteristic.uuid}")
        }
    }

    private fun findJbdCharacteristics(gatt: BluetoothGatt) {
        val service = gatt.getService(JBD_SERVICE_UUID)
        notifyCharacteristic = service?.getCharacteristic(JBD_NOTIFY_UUID)
        writeCharacteristic = service?.getCharacteristic(JBD_WRITE_UUID)

        if (notifyCharacteristic == null || writeCharacteristic == null) {
            recordPacket("JBD ff00/ff01/ff02 characteristics not found")
        } else {
            recordPacket("JBD characteristics found: notify ff01, write ff02")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeJbdFrame(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        frame: ByteArray,
        label: String
    ) {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = frame
        val ok = gatt.writeCharacteristic(characteristic)
        recordPacket("TX $label: ${frame.toHexString()} ${if (ok) "queued" else "failed"}")
    }

    private fun recordPacket(text: String) {
        rawPackets.add(0, text)
        while (rawPackets.size > MAX_RAW_PACKETS) {
            rawPackets.removeAt(rawPackets.size - 1)
        }
        publish(status)
    }

    private fun acceptJbdBytes(bytes: ByteArray) {
        rxFrameBuffer.addAll(bytes.toList())

        while (rxFrameBuffer.isNotEmpty()) {
            val start = rxFrameBuffer.indexOfFirst { it == 0xDD.toByte() }
            if (start < 0) {
                rxFrameBuffer.clear()
                return
            }
            if (start > 0) {
                repeat(start) { rxFrameBuffer.removeAt(0) }
            }
            if (rxFrameBuffer.size < 4) return

            val payloadLength = rxFrameBuffer[3].toUByte().toInt()
            val frameLength = 7 + payloadLength
            if (rxFrameBuffer.size < frameLength) return

            val frame = rxFrameBuffer.take(frameLength).toByteArray()
            repeat(frameLength) { rxFrameBuffer.removeAt(0) }

            if (frame.last() != 0x77.toByte()) {
                recordPacket("Decoded frame rejected: missing end byte ${frame.toHexString()}")
                continue
            }
            decodeJbdFrame(frame)
        }
    }

    private fun decodeJbdFrame(frame: ByteArray) {
        val command = frame[1].toUByte().toInt()
        val statusByte = frame[2].toUByte().toInt()
        val payloadLength = frame[3].toUByte().toInt()
        val payload = frame.copyOfRange(4, 4 + payloadLength)

        if (statusByte != 0) {
            recordPacket("Decoded command 0x${command.toString(16)} returned status 0x${statusByte.toString(16)}")
            return
        }

        when (command) {
            0x03 -> decodeBasicInfo(payload)
            0x04 -> decodeCellInfo(payload)
            else -> recordPacket("Decoded unsupported command 0x${command.toString(16)}")
        }
    }

    private fun decodeBasicInfo(payload: ByteArray) {
        if (payload.size < 25) {
            recordPacket("Basic info too short: ${payload.size} byte(s)")
            return
        }

        val voltage = payload.u16(0) / 100.0
        val current = payload.s16(2) / 100.0
        val remainingAh = payload.u16(4) / 100.0
        val nominalAh = payload.u16(6) / 100.0
        val cycles = payload.u16(8)
        val balanceLow = payload.u16(12)
        val balanceHigh = payload.u16(14)
        val protection = payload.u16(16)
        val soc = payload[19].toUByte().toInt()
        val fet = payload[20].toUByte().toInt()
        val cellCount = payload[21].toUByte().toInt()
        val ntcCount = payload[22].toUByte().toInt()
        val tempF = if (ntcCount > 0 && payload.size >= 25) {
            val kelvinTenths = payload.u16(23)
            ((kelvinTenths / 10.0) - 273.15) * 9.0 / 5.0 + 32.0
        } else {
            telemetry.temperatureF
        }

        telemetry = telemetry.copy(
            packVoltage = voltage,
            packCurrent = current,
            socPercent = soc,
            remainingAh = remainingAh,
            nominalAh = nominalAh,
            cycleCount = cycles,
            cellCount = cellCount,
            temperatureF = tempF,
            fetStatus = fet.toFetStatus(),
            protectionStatus = protection,
            balancingActive = (balanceLow != 0 || balanceHigh != 0)
        )
        recordPacket(
            "Decoded basic: ${"%.2f".format(voltage)} V, ${"%.2f".format(current)} A, SOC $soc%, bal ${if (balanceLow != 0 || balanceHigh != 0) "on" else "off"}"
        )
    }

    private fun decodeCellInfo(payload: ByteArray) {
        if (payload.size < 2) {
            recordPacket("Cell info too short: ${payload.size} byte(s)")
            return
        }

        val cells = payload.toList()
            .chunked(2)
            .filter { it.size == 2 }
            .mapIndexed { index, _ -> payload.u16(index * 2) / 1000.0 }

        telemetry = telemetry.copy(cellVoltages = cells)
        val delta = telemetry.cellDeltaMv?.let { "$it mV" } ?: "-"
        recordPacket("Decoded cells: ${cells.size} cell(s), delta $delta")
    }

    private fun Int.toPropertyText(): String {
        val labels = buildList {
            if (this@toPropertyText and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
            if (this@toPropertyText and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
            if (this@toPropertyText and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-no-response")
            if (this@toPropertyText and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
            if (this@toPropertyText and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
        }
        return labels.ifEmpty { listOf("none") }.joinToString(", ")
    }

    companion object {
        private const val SCAN_WINDOW_MS = 20_000L
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_RAW_PACKETS = 40
        private const val PREFS_NAME = "bms_ble"
        private const val PREF_BMS_ADDRESS = "preferred_bms_address"
        private const val PREF_BMS_NAME = "preferred_bms_name"
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val JBD_SERVICE_UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        private val JBD_NOTIFY_UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        private val JBD_WRITE_UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        private val BASIC_INFO_REQUEST = byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 0x03, 0x00, 0xFF.toByte(), 0xFD.toByte(), 0x77)
        private val CELL_INFO_REQUEST = byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 0x04, 0x00, 0xFF.toByte(), 0xFC.toByte(), 0x77)
    }
}

private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }

private fun ByteArray.u16(offset: Int): Int {
    return (this[offset].toUByte().toInt() shl 8) or this[offset + 1].toUByte().toInt()
}

private fun ByteArray.s16(offset: Int): Int {
    val raw = u16(offset)
    return if (raw and 0x8000 != 0) raw - 0x10000 else raw
}

private fun Int.toFetStatus(): String {
    val charge = this and 0x01 != 0
    val discharge = this and 0x02 != 0
    return when {
        charge && discharge -> "Charge + discharge enabled"
        charge -> "Charge enabled"
        discharge -> "Discharge enabled"
        else -> "FETs off"
    }
}
