package com.example.ridenbmscontroller.riden

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

interface ModbusIo {
    suspend fun readHoldingRegs(slave: Int, start: Int, qty: Int): IntArray
    suspend fun writeSingleReg(slave: Int, addr: Int, value: Int)
}

class ModbusRtu(
    private val port: UsbSerialPort,
    private val emit: ((String) -> Unit)? = null,
) : ModbusIo {
    private fun crc16(data: ByteArray, len: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else (crc ushr 1)
            }
        }
        return crc and 0xFFFF
    }

    private fun crcOk(frame: ByteArray, len: Int): Boolean {
        if (len < 4) return false
        val crcRx = (frame[len - 2].toInt() and 0xFF) or ((frame[len - 1].toInt() and 0xFF) shl 8)
        return crcRx == crc16(frame, len - 2)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    private fun slice(src: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArray(len)
        System.arraycopy(src, off, out, 0, len)
        return out
    }

    private suspend fun readSome(max: Int, timeoutMs: Int): ByteArray = withContext(Dispatchers.IO) {
        val buf = ByteArray(max)
        val r = try { port.read(buf, timeoutMs) } catch (_: Exception) { 0 }
        if (r <= 0) ByteArray(0) else buf.copyOf(r)
    }

    private suspend fun writeFrame(frame: ByteArray) = withContext(Dispatchers.IO) {
        try {
            port.write(frame, 250)
        } catch (e: Exception) {
            throw RuntimeException("writeFrame failed: ${e.message}")
        }
    }

    private suspend fun drainInput() {
        withContext(Dispatchers.IO) {
            val tmp = ByteArray(256)
            repeat(2) {
                try { port.read(tmp, 20) } catch (_: Exception) { }
            }
        }
    }

    private suspend fun readBurst(totalTimeoutMs: Int, maxBytes: Int): ByteArray {
        val out = ByteArray(maxBytes)
        var off = 0
        var remainingMs = totalTimeoutMs

        while (remainingMs > 0 && off < maxBytes) {
            val t = min(80, remainingMs)
            val chunk = readSome(min(256, maxBytes - off), t)
            if (chunk.isNotEmpty()) {
                System.arraycopy(chunk, 0, out, off, chunk.size)
                off += chunk.size
                remainingMs = min(200, remainingMs)
            } else {
                remainingMs -= t
            }
        }

        return if (off == 0) ByteArray(0) else out.copyOf(off)
    }

    private fun findFrameFc03OrExc(buf: ByteArray, slave: Int, func: Int, expectedByteCount: Int): ByteArray? {
        val s = slave and 0xFF
        val f = func and 0xFF
        val fe = f or 0x80

        var i = 0
        while (i + 5 <= buf.size) {
            val b0 = buf[i].toInt() and 0xFF
            if (b0 == s) {
                val b1 = buf[i + 1].toInt() and 0xFF
                if (b1 == f) {
                    val bc = buf[i + 2].toInt() and 0xFF
                    if (bc == expectedByteCount) {
                        val frameLen = 3 + bc + 2
                        if (i + frameLen <= buf.size) {
                            val fr = slice(buf, i, frameLen)
                            if (crcOk(fr, fr.size)) return fr
                        }
                    }
                }
                if (b1 == fe && i + 5 <= buf.size) {
                    val fr = slice(buf, i, 5)
                    if (crcOk(fr, fr.size)) {
                        val exc = fr[2].toInt() and 0xFF
                        throw RuntimeException("Modbus exception code=0x${exc.toString(16)}")
                    }
                }
            }
            i += 1
        }
        return null
    }

    private fun findFrameFc06OrExc(buf: ByteArray, slave: Int, func: Int): ByteArray? {
        val s = slave and 0xFF
        val f = func and 0xFF
        val fe = f or 0x80

        var i = 0
        while (i + 5 <= buf.size) {
            val b0 = buf[i].toInt() and 0xFF
            if (b0 == s) {
                val b1 = buf[i + 1].toInt() and 0xFF
                if (b1 == f && i + 8 <= buf.size) {
                    val fr = slice(buf, i, 8)
                    if (crcOk(fr, fr.size)) return fr
                }
                if (b1 == fe && i + 5 <= buf.size) {
                    val fr = slice(buf, i, 5)
                    if (crcOk(fr, fr.size)) {
                        val exc = fr[2].toInt() and 0xFF
                        throw RuntimeException("Modbus exception code=0x${exc.toString(16)}")
                    }
                }
            }
            i += 1
        }

        return null
    }

    override suspend fun readHoldingRegs(slave: Int, start: Int, qty: Int): IntArray {
        val func = 0x03
        val pdu = byteArrayOf(
            slave.toByte(),
            func.toByte(),
            ((start ushr 8) and 0xFF).toByte(),
            (start and 0xFF).toByte(),
            ((qty ushr 8) and 0xFF).toByte(),
            (qty and 0xFF).toByte(),
        )
        val crc = crc16(pdu, pdu.size)
        val frame = pdu + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
        val expectedByteCount = qty * 2

        repeat(6) { attempt ->
            drainInput()
            writeFrame(frame)

            val raw = readBurst(totalTimeoutMs = 600, maxBytes = 256)
            if (raw.isNotEmpty()) {
                val fr = try {
                    findFrameFc03OrExc(raw, slave, func, expectedByteCount)
                } catch (e: Exception) {
                    emit?.invoke("RX EXC a=${attempt + 1}/6 raw(${raw.size}): ${hex(raw)}")
                    throw e
                }

                if (fr != null) {
                    return IntArray(qty) { i ->
                        val hi = fr[3 + 2 * i].toInt() and 0xFF
                        val lo = fr[3 + 2 * i + 1].toInt() and 0xFF
                        (hi shl 8) or lo
                    }
                }
            }

            emit?.invoke("RX fail a=${attempt + 1}/6 raw(${raw.size}): ${hex(raw)}")
        }

        throw RuntimeException("Modbus read failed start=0x${start.toString(16)} qty=$qty")
    }

    override suspend fun writeSingleReg(slave: Int, addr: Int, value: Int) {
        val func = 0x06
        val v = value and 0xFFFF
        val pdu = byteArrayOf(
            slave.toByte(),
            func.toByte(),
            ((addr ushr 8) and 0xFF).toByte(),
            (addr and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte(),
        )
        val crc = crc16(pdu, pdu.size)
        val frame = pdu + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())

        repeat(6) { attempt ->
            drainInput()
            writeFrame(frame)

            val raw = readBurst(totalTimeoutMs = 600, maxBytes = 256)
            if (raw.isNotEmpty()) {
                val fr = try {
                    findFrameFc06OrExc(raw, slave, func)
                } catch (e: Exception) {
                    emit?.invoke("RX EXC FC06 a=${attempt + 1}/6 raw(${raw.size}): ${hex(raw)}")
                    throw e
                }

                if (fr != null && (0 until 6).all { i -> (fr[i].toInt() and 0xFF) == (pdu[i].toInt() and 0xFF) }) {
                    return
                }
            }

            emit?.invoke("RX fail FC06 a=${attempt + 1}/6 raw(${raw.size}): ${hex(raw)}")
        }

        throw RuntimeException("Modbus write failed addr=0x${addr.toString(16)}")
    }
}
