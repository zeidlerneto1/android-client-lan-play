package com.example.lanplaypoc

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var editServer: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtLogs: TextView
    private lateinit var txtHotspotIp: TextView
    private lateinit var txtSwitchIp: TextView
    private var networkScanner: NetworkScanner? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            viewModel.log("[ERROR] VPN Permission denied")
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
        txtHotspotIp = findViewById(R.id.txtHotspotIp)
        txtSwitchIp = findViewById(R.id.txtSwitchIp)

        val prefs = getSharedPreferences("lanplay", Context.MODE_PRIVATE)
        editServer.setText(prefs.getString("server_addr", "lan-play.com:11451"))

        viewModel.status.observe(this) { status -> txtStatus.text = status }
        viewModel.logs.observe(this) { logs ->
            txtLogs.text = logs
            val scrollAmount = txtLogs.layout?.let { it.getLineTop(txtLogs.lineCount) - txtLogs.height } ?: 0
            if (scrollAmount > 0) txtLogs.scrollTo(0, scrollAmount)
        }

        btnStart.setOnClickListener { prepareVpn() }
        btnStop.setOnClickListener { stopAll() }

        networkScanner = NetworkScanner(
            onDeviceFound = { ip, _ ->
                runOnUiThread { txtSwitchIp.text = "Switch detectado em $ip" }
            },
            onLog = { msg -> viewModel.log(msg) }
        )
    }

    private fun prepareVpn() {
        networkScanner?.start()
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        viewModel.log("[INFO] Iniciando Relay...")
        viewModel.setStatus("Status: Running")
        val serverAddr = editServer.text.toString()
        val prefs = getSharedPreferences("lanplay", Context.MODE_PRIVATE)
        prefs.edit().putString("server_addr", serverAddr).apply()

        val intent = Intent(this, LanPlayVpnService::class.java).apply {
            putExtra("server_addr", serverAddr)
        }
        startService(intent)
    }

    private fun stopAll() {
        viewModel.log("[INFO] Parando Relay...")
        viewModel.setStatus("Status: Stopped")
        networkScanner?.stop()
        txtSwitchIp.text = "Switch: Aguardando tráfego..."
        val intent = Intent(this, LanPlayVpnService::class.java)
        stopService(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        networkScanner?.stop()
    }
}
