package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit

data class DeviceAssociationUiState(
    val isScanning: Boolean = false,
    val discoveredServers: List<String> = emptyList(),
    val showServerList: Boolean = false,
    val showCodeDialog: Boolean = false,
    val enteredCode: String = "",
    val isConfirming: Boolean = false,
    val token: String? = null,
    val pairedDeviceName: String? = null,
    val pairedDeviceId: String? = null,
    val isUnpairing: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null
)

class DeviceAssociationViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val _uiState = MutableStateFlow(DeviceAssociationUiState(
        token = SettingsPreferences.getPairedToken(context),
        pairedDeviceName = SettingsPreferences.getPairedDeviceName(context),
        pairedDeviceId = SettingsPreferences.getPairedDeviceId(context)
    ))
    val uiState: StateFlow<DeviceAssociationUiState> = _uiState.asStateFlow()

    fun startPairing() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, discoveredServers = emptyList(), errorMessage = null) }

            val servers = discoverServers()

            _uiState.update { it.copy(
                isScanning = false,
                discoveredServers = servers,
                showServerList = true,
                statusMessage = if (servers.isEmpty()) "未发现 ximed 服务" else null
            ) }
        }
    }

    private suspend fun discoverServers(): List<String> = withContext(Dispatchers.IO) {
        val subnets = getLocalSubnets() ?: return@withContext emptyList()
        val results = Collections.synchronizedList(mutableListOf<String>())
        val semaphore = Semaphore(20)

        subnets.forEach { subnet ->
            val jobs = (1..254).map { i ->
                async {
                    semaphore.withPermit {
                        val host = "$subnet$i"
                        try {
                            checkHealth(host)
                            results.add(host)
                        } catch (_: Exception) { }
                    }
                }
            }
            jobs.forEach { it.await() }
        }
        results.toList()
    }

    private fun checkHealth(host: String) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, 8370), 1000)
            val request = "GET /health HTTP/1.1\r\nHost: $host:8370\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var inBody = false
            val body = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (inBody) {
                    body.append(line)
                } else if (line?.isBlank() == true) {
                    inBody = true
                }
            }

            val json = JSONObject(body.toString())
            if ("ok" != json.optString("status")) {
                throw Exception("not ok")
            }
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun getLocalSubnets(): Set<String>? {
        val subnets = mutableSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val name = ni.name.lowercase()
                if ("wlan" !in name && "eth" !in name) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress ?: continue
                        if (hostAddress.startsWith("192.168.") || hostAddress.startsWith("10.") ||
                            hostAddress.startsWith("172.")) {
                            subnets.add(hostAddress.substringBeforeLast('.') + ".")
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return subnets.ifEmpty { null }
    }

    fun selectServer(host: String) {
        val serverUrl = "http://$host:8370"
        SettingsPreferences.setPairedServer(context, serverUrl)
        _uiState.update { it.copy(
            showServerList = false,
            showCodeDialog = true,
            enteredCode = "",
            errorMessage = null
        ) }
    }

    fun dismissServerList() {
        _uiState.update { it.copy(showServerList = false) }
    }

    fun updateEnteredCode(code: String) {
        _uiState.update { it.copy(enteredCode = code) }
    }

    fun confirmPairing() {
        val code = _uiState.value.enteredCode.trim()
        if (code.length != 6) {
            _uiState.update { it.copy(errorMessage = "请输入 6 位配对码") }
            return
        }
        val serverUrl = SettingsPreferences.getPairedServer(context) ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isConfirming = true, errorMessage = null) }

            val result = withContext(Dispatchers.IO) {
                try {
                    val json = JSONObject().apply {
                        put("code", code)
                        put("approve", true)
                    }
                    val body = json.toString().toRequestBody(jsonMediaType)
                    val request = Request.Builder()
                        .url("$serverUrl/pair/confirm")
                        .post(body)
                        .build()
                    val response = client.newCall(request).execute()
                    response.use { resp ->
                        if (resp.isSuccessful) {
                            val respBody = resp.body?.string() ?: ""
                            JSONObject(respBody).optBoolean("success", false)
                        } else if (resp.code == 404) {
                            throw Exception("配对码无效或已过期")
                        } else if (resp.code == 409) {
                            throw Exception("配对码已被确认")
                        } else {
                            throw Exception("确认失败: ${resp.code}")
                        }
                    }
                } catch (e: Exception) {
                    throw e
                }
            }

            try {
                if (result) {
                    SettingsPreferences.setPairedToken(context, code)
                    _uiState.update { it.copy(
                        isConfirming = false,
                        showCodeDialog = false,
                        enteredCode = "",
                        token = code,
                        pairedDeviceName = "PC",
                        errorMessage = null,
                        statusMessage = "关联成功"
                    ) }
                } else {
                    throw Exception("确认失败")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isConfirming = false,
                    errorMessage = e.message ?: "配对失败"
                ) }
            }
        }
    }

    fun dismissCodeDialog() {
        if (!_uiState.value.isConfirming) {
            _uiState.update { it.copy(showCodeDialog = false, enteredCode = "") }
        }
    }

    fun unpair() {
        val serverUrl = SettingsPreferences.getPairedServer(context) ?: return
        val pairedDeviceId = _uiState.value.pairedDeviceId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isUnpairing = true, errorMessage = null) }

            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url("$serverUrl/pair/remove/$pairedDeviceId")
                        .post("".toRequestBody(null))
                        .build()
                    client.newCall(request).execute().use { }
                } catch (_: Exception) { }
            }

            SettingsPreferences.clearPairedData(context)
            _uiState.update { it.copy(
                isUnpairing = false,
                token = null,
                pairedDeviceName = null,
                pairedDeviceId = null,
                errorMessage = null,
                statusMessage = "已解除关联"
            ) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
