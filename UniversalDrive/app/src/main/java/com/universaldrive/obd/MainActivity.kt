package com.universaldrive.obd

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private lateinit var status: TextView
    private lateinit var rpm: TextView
    private lateinit var speed: TextView
    private lateinit var coolant: TextView
    private lateinit var voltage: TextView
    private lateinit var results: TextView
    private val cyan = 0xFF00D4FF.toInt()
    private val white = 0xFFF2F6FC.toInt()
    private val muted = 0xFF9BA9BA.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestBtPermission()
    }

    private fun requestBtPermission() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 100)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(0xFF090E16.toInt())
        }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        body.addView(label("UNIVERSAL DRIVE", 25, cyan))
        body.addView(label("OBD-II  •  BLUETOOTH  •  CAN DIAGNOSTICS", 11, muted))
        status = label("DISCONNECTED  |  Pair an ELM327 adapter first", 13, 0xFFFFC857.toInt())
        body.addView(status)
        body.addView(button("CONNECT BLUETOOTH ELM327") { chooseAdapter() })

        body.addView(label("LIVE DASHBOARD", 17, white))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        rpm = metric("ENGINE RPM", "-- rpm")
        speed = metric("VEHICLE SPEED", "-- km/h")
        coolant = metric("COOLANT", "-- °C")
        voltage = metric("CONTROL MODULE VOLTAGE", "-- V")
        left.addView(rpm); left.addView(coolant); right.addView(speed); right.addView(voltage)
        grid.addView(left); grid.addView(right); body.addView(grid)

        body.addView(label("DIAGNOSTICS", 17, white))
        body.addView(button("READ STORED TROUBLE CODES") { runElm("03", "Reading stored DTCs…") { parseDtcs(it) } })
        body.addView(button("CLEAR EMISSIONS DTCs") {
            AlertDialog.Builder(this).setTitle("Clear diagnostic codes?")
                .setMessage("This sends OBD Mode 04. It may reset readiness monitors and does not repair the fault.")
                .setNegativeButton("Cancel", null).setPositiveButton("CLEAR") { _, _ ->
                    runElm("04", "Clearing DTCs…") { "Clear command response:\n$it\nRe-read codes and repair the underlying fault." }
                }.show()
        })
        body.addView(button("REFRESH LIVE DATA") { refreshLive() })
        body.addView(button("READINESS MONITORS") { runElm("0101", "Reading readiness…") { it } })

        body.addView(label("CAN & VEHICLE SUPPORT", 17, white))
        body.addView(label("ELM327 CAN support depends on adapter firmware and vehicle protocol. Standard OBD-II covers supported powertrain data; manufacturer modules, gateways and enhanced PIDs vary by vehicle.", 13, muted))
        body.addView(button("READ-ONLY CAN MONITOR (ADAPTER SUPPORT REQUIRED)") {
            runElm("ATMA", "Starting read-only CAN monitor…") { it }
        })

        body.addView(label("REMOTE START", 17, white))
        body.addView(label("No universal CAN start command exists. Remote start requires a vehicle-approved authenticated controller and verified Park/Neutral, brake, bonnet and immobiliser interlocks.", 13, muted))
        body.addView(button("REMOTE START — COMPATIBILITY REQUIRED") {
            AlertDialog.Builder(this).setTitle("Remote start unavailable")
                .setMessage("Connect a compatible authenticated remote-start controller. This app does not inject unverified ignition, starter or immobiliser-bypass CAN frames.")
                .setPositiveButton("OK", null).show()
        })
        results = label("OUTPUT\nNo commands sent yet.", 13, white)
        body.addView(results)
        body.addView(button("DISCONNECT") { disconnect() })
        body.addView(label("Use a quality correctly configured adapter. Do not operate the app while driving. Clearing codes can affect emissions readiness.", 11, muted))
    }

    private fun label(text: String, size: Int, color: Int): TextView = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color); setPadding(4, 10, 4, 10)
    }
    private fun metric(title: String, value: String): TextView = TextView(this).apply {
        text = "$title\n$value"; textSize = 18f; setTextColor(white); setPadding(12, 18, 8, 18)
        setBackgroundColor(0xFF141F2D.toInt())
    }
    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text; setTextColor(white)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1B3348.toInt()))
        setOnClickListener { action() }
    }

    private fun chooseAdapter() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestBtPermission(); status.text = "Grant Bluetooth permission, then connect."; return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { status.text = "Bluetooth not supported on this device."; return }
        if (!adapter.isEnabled) { status.text = "Enable Bluetooth and pair your ELM327 in Android Settings."; return }
        val devices = try { adapter.bondedDevices.toList() } catch (_: SecurityException) { emptyList() }
        if (devices.isEmpty()) { status.text = "No paired devices. Pair ELM327 in Android Bluetooth settings."; return }
        val names = devices.map { "${it.name ?: "Bluetooth device"}  (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Select paired adapter").setItems(names) { _, which -> connect(devices[which]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun connect(device: BluetoothDevice) {
        status.text = "CONNECTING…"
        thread {
            try {
                val s = device.createRfcommSocketToServiceRecord(spp)
                s.connect()
                socket = s; input = s.inputStream; output = s.outputStream
                listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach { command(it) }
                runOnUiThread { status.text = "CONNECTED  |  ${device.name ?: device.address}"; results.text = "Adapter initialised.\nProtocol: automatic (ATSP0)." }
            } catch (e: Exception) {
                disconnect()
                runOnUiThread { status.text = "CONNECTION FAILED: ${e.message ?: "Check adapter and pairing"}" }
            }
        }
    }

    @Synchronized private fun command(cmd: String): String {
        val out = output ?: throw IllegalStateException("Not connected")
        val inp = input ?: throw IllegalStateException("Not connected")
        out.write((cmd + "\r").toByteArray()); out.flush()
        val bytes = ArrayList<Byte>(); val end = System.currentTimeMillis() + 3500
        while (System.currentTimeMillis() < end) {
            while (inp.available() > 0) {
                val b = inp.read()
                if (b >= 0) bytes.add(b.toByte())
                if (b == '>'.code) return String(bytes.toByteArray()).replace(">", "").trim()
            }
            Thread.sleep(20)
        }
        return String(bytes.toByteArray()).trim().ifEmpty { "No response (timeout)" }
    }

    private fun runElm(cmd: String, message: String, transform: (String) -> String) {
        if (socket?.isConnected != true) { status.text = "Connect an ELM327 first."; return }
        results.text = message
        thread {
            try {
                val raw = command(cmd)
                val rendered = transform(raw)
                runOnUiThread { results.text = "$message\n$rendered" }
            } catch (e: Exception) {
                runOnUiThread { results.text = "ERROR: ${e.message}" }
            }
        }
    }

    private fun refreshLive() {
        if (socket?.isConnected != true) { status.text = "Connect an ELM327 first."; return }
        results.text = "Refreshing live PIDs…"
        thread {
            try {
                val r = command("010C"); val s = command("010D"); val c = command("0105"); val v = command("0142")
                val rpmValue = parseHexPid(r, listOf("41", "0C"))?.let { ((it[0] * 256 + it[1]) / 4.0).roundToInt().toString() + " rpm" } ?: "-- rpm"
                val speedValue = parseHexPid(s, listOf("41", "0D"))?.get(0)?.toString()?.plus(" km/h") ?: "-- km/h"
                val coolantValue = parseHexPid(c, listOf("41", "05"))?.get(0)?.let { (it - 40).toString() + " °C" } ?: "-- °C"
                val voltValue = parseHexPid(v, listOf("41", "42"))?.let { "%.2f V".format((it[0] * 256 + it[1]) / 1000.0) } ?: "-- V"
                runOnUiThread {
                    rpm.text = "ENGINE RPM\n$rpmValue"; speed.text = "VEHICLE SPEED\n$speedValue"
                    coolant.text = "COOLANT\n$coolantValue"; voltage.text = "CONTROL MODULE VOLTAGE\n$voltValue"
                    results.text = "Live data refreshed.\nRPM: $r\nSpeed: $s\nCoolant: $c\nVoltage: $v"
                }
            } catch (e: Exception) { runOnUiThread { results.text = "Live data error: ${e.message}" } }
        }
    }

    private fun parseHexPid(raw: String, prefix: List<String>): List<Int>? {
        val tokens = raw.uppercase().replace("\r", " ").replace("\n", " ").split(Regex("\\s+"))
            .map { it.trim() }.filter { it.matches(Regex("[0-9A-F]{2}")) }
        val idx = tokens.indices.firstOrNull { i -> i + prefix.size < tokens.size && prefix.indices.all { tokens[i + it] == prefix[it] } } ?: return null
        return tokens.drop(idx + prefix.size).take(2).mapNotNull { it.toIntOrNull(16) }.takeIf { it.isNotEmpty() }
    }

    private fun parseDtcs(raw: String): String {
        val clean = raw.uppercase().replace(Regex("[^0-9A-F]"), "")
        val marker = clean.indexOf("43")
        if (marker < 0) return "No decoded DTC response.\nRaw: $raw"
        val data = clean.substring(marker + 2)
        val out = mutableListOf<String>()
        for (i in data.indices step 4) {
            if (i + 4 > data.length) break
            val a = data.substring(i, i + 2).toIntOrNull(16) ?: continue
            val b = data.substring(i + 2, i + 4).toIntOrNull(16) ?: continue
            if (a == 0 && b == 0) continue
            val type = listOf("P", "C", "B", "U")[(a shr 6) and 3]
            val digit = ((a shr 4) and 3).toString()
            out.add(type + digit + "%02X%02X".format(a and 15, b))
        }
        return if (out.isEmpty()) "No stored powertrain DTCs reported." else out.joinToString("\n")
    }

    private fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
        if (::status.isInitialized) runOnUiThread { status.text = "DISCONNECTED" }
    }

    override fun onDestroy() { disconnect(); super.onDestroy() }
}
