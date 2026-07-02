package com.aladdin.app.bluetooth

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BluetoothManager — Item 67: Scan, pair, connect, disconnect, status monitoring.
 */
@Singleton
class AladdinBluetoothManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object { private const val TAG = "AladdinBT" }

    enum class BtStatus { IDLE, SCANNING, CONNECTED, DISCONNECTED, ERROR }
    data class BtDevice(val name: String, val address: String, val bondState: Int, val type: Int)

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothAdapter?)
    private val _status = MutableStateFlow(BtStatus.IDLE)
    val status: StateFlow<BtStatus> = _status.asStateFlow()
    private val _discovered = MutableSharedFlow<BtDevice>(extraBufferCapacity = 32)
    val discovered: SharedFlow<BtDevice> = _discovered.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun hasPermissions(): Boolean {
        val perms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun getPairedDevices(): List<BtDevice> {
        if (!hasPermissions() || adapter == null) return emptyList()
        return try {
            adapter.bondedDevices?.map { BtDevice(it.name ?: "Unknown", it.address, it.bondState, it.type) } ?: emptyList()
        } catch (e: SecurityException) { Log.w(TAG, "Permission denied: ${e.message}"); emptyList() }
    }

    fun startScan(durationMs: Long = 10_000L) {
        if (!hasPermissions() || adapter == null) { Log.w(TAG, "No BT permissions or adapter"); return }
        _status.value = BtStatus.SCANNING
        val scanner = adapter.bluetoothLeScanner ?: run { _status.value = BtStatus.IDLE; return }
        try {
            val callback = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                    val dev = result.device
                    scope.launch { _discovered.emit(BtDevice(dev.name ?: "Unknown", dev.address, dev.bondState, dev.type)) }
                }
                override fun onScanFailed(errorCode: Int) { Log.e(TAG, "Scan failed: $errorCode"); _status.value = BtStatus.ERROR }
            }
            scanner.startScan(callback)
            scope.launch { delay(durationMs); scanner.stopScan(callback); _status.value = BtStatus.IDLE }
        } catch (e: SecurityException) { Log.w(TAG, "Scan permission denied"); _status.value = BtStatus.ERROR }
    }

    fun connect(address: String) {
        if (!hasPermissions() || adapter == null) return
        try {
            val device = adapter.getRemoteDevice(address)
            gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    _status.value = if (newState == BluetoothProfile.STATE_CONNECTED) BtStatus.CONNECTED else BtStatus.DISCONNECTED
                    if (newState == BluetoothProfile.STATE_CONNECTED) { try { gatt.discoverServices() } catch (_: SecurityException) {} }
                    Log.i(TAG, "BT connection state: $newState device=$address")
                }
            })
        } catch (e: SecurityException) { Log.w(TAG, "Connect permission denied"); _status.value = BtStatus.ERROR }
          catch (e: Exception) { Log.e(TAG, "Connect error: ${e.message}"); _status.value = BtStatus.ERROR }
    }

    fun disconnect() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
        gatt = null; _status.value = BtStatus.DISCONNECTED
    }
}
