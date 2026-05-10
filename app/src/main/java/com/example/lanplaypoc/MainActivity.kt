package com.example.lanplaypoc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var editServer: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtLogs: TextView

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            viewModel.log("VPN Permission denied")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            startAll()
        } else {
            viewModel.log("Permissions denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editServer = findViewById(R.id.editServer)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        txtStatus = findViewById(R.id.txtStatus)
        txtLogs = findViewById(R.id.txtLogs)

        val prefs = getSharedPreferences("lanplay", Context.MODE_PRIVATE)
        editServer.setText(prefs.getString("server_addr", "lan-play.com:11451"))

        // Observe ViewModel state
        viewModel.status.observe(this) { status ->
            txtStatus.text = status
        }

        viewModel.logs.observe(this) { logs ->
            txtLogs.text = logs
            // Simple scroll to bottom
            val scrollAmount = txtLogs.layout?.let { it.getLineTop(txtLogs.lineCount) - txtLogs.height } ?: 0
            if (scrollAmount > 0) {
                txtLogs.scrollTo(0, scrollAmount)
            }
        }

        btnStart.setOnClickListener {
            if (hasPermissions()) {
                startAll()
            } else {
                requestPermissions()
            }
        }

        btnStop.setOnClickListener {
            stopAll()
        }
    }

    private fun startAll() {
        viewModel.log("Starting All...")
        viewModel.startHotspot()
        prepareVpn()
    }

    private fun stopAll() {
        viewModel.log("Stopping All...")
        viewModel.stopHotspot()
        val intent = Intent(this, LanPlayVpnService::class.java)
        stopService(intent)
    }

    private fun prepareVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        viewModel.log("Starting VPN Service...")
        val serverAddr = editServer.text.toString()
        val prefs = getSharedPreferences("lanplay", Context.MODE_PRIVATE)
        prefs.edit().putString("server_addr", serverAddr).apply()

        val intent = Intent(this, LanPlayVpnService::class.java).apply {
            putExtra("server_addr", serverAddr)
        }
        startService(intent)
    }

    private fun hasPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissionLauncher.launch(permissions)
    }
}
