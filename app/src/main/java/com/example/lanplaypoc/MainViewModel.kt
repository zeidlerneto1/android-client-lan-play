package com.example.lanplaypoc

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _status = MutableLiveData<String>("Status: Stopped")
    val status: LiveData<String> = _status

    private val _logs = MutableLiveData<String>("")
    val logs: LiveData<String> = _logs

    private val vpnLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(LanPlayVpnService.EXTRA_LOG_MESSAGE)
            if (message != null) {
                log(message)
            }
        }
    }

    init {
        val filter = IntentFilter(LanPlayVpnService.ACTION_VPN_LOG)
        LocalBroadcastManager.getInstance(application).registerReceiver(vpnLogReceiver, filter)
    }

    fun setStatus(newStatus: String) {
        _status.postValue(newStatus)
    }

    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val currentLogs = _logs.value ?: ""
        val newLog = "$currentLogs[$timestamp] $message\n"
        
        // Limit to ~1000 lines to prevent memory issues
        val lines = newLog.lines()
        val truncatedLogs = if (lines.size > 1000) {
            lines.takeLast(1000).joinToString("\n")
        } else {
            newLog
        }
        
        _logs.postValue(truncatedLogs)
    }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(vpnLogReceiver)
    }
}
