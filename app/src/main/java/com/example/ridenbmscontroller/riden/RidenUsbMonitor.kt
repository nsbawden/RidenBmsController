package com.example.ridenbmscontroller.riden

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Riden-specific hardware adapter.
 *
 * This class is intentionally the place where Riden USB/Modbus details live:
 * USB serial discovery, CH340 preference, register addresses, register scaling,
 * and the exact commands for VSET/ISET/output state.
 *
 * The higher-level controller should not know those details. If adapting this app
 * to a real programmable MPPT controller, replace this package with an adapter that
 * publishes charger telemetry and implements equivalent setOutput/setVoltage/setCurrent
 * operations for that hardware.
 */
class RidenUsbMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val onStateChanged: (RidenUsbState) -> Unit
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private var pollJob: Job? = null
    private var receiverRegistered = false
    private var state = RidenUsbState()
    private var permissionRequestInFlight = false
    private var currentIo: ModbusIo? = null
    private val ioMutex = Mutex()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            if (granted && device != null) {
                permissionRequestInFlight = false
                publish(state.copy(permissionNeeded = false, status = "USB permission granted"))
                start()
            } else {
                permissionRequestInFlight = false
                publish(state.copy(permissionNeeded = true, status = "USB permission denied"))
            }
        }
    }

    fun start() {
        ensureReceiver()
        if (pollJob?.isActive == true) return

        pollJob = scope.launch(Dispatchers.IO) {
            var preferredVid: Int? = null
            var preferredPid: Int? = null

            while (isActive) {
                val driver = findDriver(preferredVid, preferredPid)
                if (driver == null) {
                    preferredVid = null
                    preferredPid = null
                    publishOnMain(RidenUsbState(status = "No USB serial device found"))
                    delay(1_500)
                    continue
                }

                val device = driver.device
                preferredVid = device.vendorId
                preferredPid = device.productId

                if (!usbManager.hasPermission(device)) {
                    publishOnMain(
                        RidenUsbState(
                            permissionNeeded = true,
                            status = "USB permission needed",
                            deviceName = device.productName ?: "USB serial",
                            vendorId = device.vendorId,
                            productId = device.productId
                        )
                    )
                    if (!permissionRequestInFlight) {
                        requestPermission(device)
                    }
                    delay(1_500)
                    continue
                }

                permissionRequestInFlight = false

                var connection: UsbDeviceConnection? = null
                var port: UsbSerialPort? = null
                try {
                    connection = usbManager.openDevice(device)
                    if (connection == null) {
                        publishOnMain(state.copy(connected = false, status = "Unable to open USB device"))
                        delay(1_500)
                        continue
                    }

                    port = driver.ports.firstOrNull()
                    if (port == null) {
                        publishOnMain(state.copy(connected = false, status = "USB serial port not found"))
                        delay(1_500)
                        continue
                    }

                    port.open(connection)
                    port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    runCatching { port.dtr = true }
                    runCatching { port.rts = true }
                    delay(200)

                    val io = ModbusRtu(port)
                    currentIo = io
                    publishOnMain(
                        RidenUsbState(
                            connected = true,
                            status = "Riden connected",
                            deviceName = device.productName ?: "USB serial",
                            vendorId = device.vendorId,
                            productId = device.productId
                        )
                    )

                    while (isActive) {
                        val telemetry = ioMutex.withLock { readTelemetry(io) }
                        publishOnMain(
                            state.copy(
                                connected = true,
                                permissionNeeded = false,
                                status = "Riden live",
                                telemetry = telemetry
                            )
                        )
                        delay(POLL_MS)
                    }
                } catch (e: Exception) {
                    publishOnMain(
                        state.copy(
                            connected = false,
                            status = "Riden USB error: ${e.message ?: e.javaClass.simpleName}"
                        )
                    )
                    delay(1_500)
                } finally {
                    currentIo = null
                    closeQuietly(port, connection)
                }
            }
        }
    }

    fun setOutput(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            val io = currentIo ?: return@launch
            runCatching {
                ioMutex.withLock {
                    val value = if (enabled) 1 else 0
                    io.writeSingleReg(SLAVE_ID, REG_ON1, value)
                    io.writeSingleReg(SLAVE_ID, REG_ON2, value)
                }
            }.onFailure {
                publishOnMain(state.copy(status = "Riden output command failed: ${it.message ?: it.javaClass.simpleName}"))
            }
        }
    }

    fun setVset(volts: Double) {
        scope.launch(Dispatchers.IO) {
            val io = currentIo ?: return@launch
            runCatching {
                ioMutex.withLock {
                    io.writeSingleReg(SLAVE_ID, REG_VSET, (volts.coerceIn(0.0, 65.0) * 100.0).toInt())
                }
            }.onFailure {
                publishOnMain(state.copy(status = "Riden VSET command failed: ${it.message ?: it.javaClass.simpleName}"))
            }
        }
    }

    fun setIset(amps: Double) {
        scope.launch(Dispatchers.IO) {
            val io = currentIo ?: return@launch
            runCatching {
                ioMutex.withLock {
                    io.writeSingleReg(SLAVE_ID, REG_ISET, (amps.coerceIn(0.0, 60.0) * 100.0).toInt())
                }
            }.onFailure {
                publishOnMain(state.copy(status = "Riden ISET command failed: ${it.message ?: it.javaClass.simpleName}"))
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(permissionReceiver) }
            receiverRegistered = false
        }
    }

    private suspend fun readTelemetry(io: ModbusIo): RidenTelemetry {
        // Riden-specific register block. Values are centi-units in this model.
        val r = io.readHoldingRegs(SLAVE_ID, REG_BASE, 8)
        val outputOn = r[7] != 0
        return RidenTelemetry(
            vset = r[0] / 100.0,
            iset = r[1] / 100.0,
            vout = r[2] / 100.0,
            iout = r[3] / 100.0,
            watts = r[5] / 100.0,
            vin = r[6] / 100.0,
            outputOn = outputOn
        )
    }

    private fun findDriver(preferredVid: Int?, preferredPid: Int?): UsbSerialDriver? {
        val found = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (found.isEmpty()) return null
        val preferred = if (preferredVid != null && preferredPid != null) {
            found.firstOrNull { it.device.vendorId == preferredVid && it.device.productId == preferredPid }
        } else {
            null
        }
        return preferred
            ?: found.firstOrNull { it.device.vendorId == VID_WCH && it.device.productId == PID_CH340 }
            ?: found.first()
    }

    private fun requestPermission(device: UsbDevice) {
        permissionRequestInFlight = true
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val pendingIntent = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        runCatching {
            usbManager.requestPermission(device, pendingIntent)
        }.onFailure {
            permissionRequestInFlight = false
            publish(state.copy(permissionNeeded = true, status = "USB permission request failed: ${it.message ?: it.javaClass.simpleName}"))
        }
    }

    private fun ensureReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private suspend fun publishOnMain(next: RidenUsbState) {
        withContext(Dispatchers.Main) {
            publish(next)
        }
    }

    private fun publish(next: RidenUsbState) {
        state = next
        onStateChanged(next)
    }

    private fun closeQuietly(port: UsbSerialPort?, connection: UsbDeviceConnection?) {
        runCatching { port?.close() }
        runCatching { connection?.close() }
    }

    companion object {
        // Riden / CH340 USB and Modbus constants. These are not part of the generic
        // BMS/SOC controller model; they belong to this hardware adapter only.
        private const val ACTION_USB_PERMISSION = "com.example.ridenbmscontroller.USB_PERMISSION"
        private const val VID_WCH = 0x1A86
        private const val PID_CH340 = 0x7523
        private const val SLAVE_ID = 1
        private const val REG_BASE = 0x0008
        private const val REG_VSET = 0x0008
        private const val REG_ISET = 0x0009
        private const val REG_ON1 = 0x0011
        private const val REG_ON2 = 0x0012
        private const val POLL_MS = 250L
    }
}
