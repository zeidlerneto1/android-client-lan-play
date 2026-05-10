package com.example.lanplaypoc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
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

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        txtStatus = findViewById(R.id.txtStatus)
        txtLogs = findViewById(R.id.txtLogs)

        // Observe ViewModel state
        viewModel.status.observe(this) { status ->
            txtStatus.text = status
        }

        viewModel.logs.observe(this) { logs ->
            txtLogs.text = logs
            // Auto-scroll to bottom could be added here
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
        val intent = Intent(this, LanPlayVpnService::class.java)
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
