package com.example.me340

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.me340.ui.theme.ME340Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

// Standard UUID for Serial Port Profile (SPP)
private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.entries.any { !it.value }) {
                Log.d("MainActivity", "Permissions denied.")
            }
        }

        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

        setContent {
            ME340Theme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val btAdapter = bluetoothAdapter
                    if (btAdapter != null) {
                        BluetoothScreen(btAdapter)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("This device does not support Bluetooth")
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun BluetoothScreen(bluetoothAdapter: BluetoothAdapter) {
    val context = LocalContext.current
    var discoveredDevices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
    var isScanning by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var connectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var bluetoothSocket by remember { mutableStateOf<BluetoothSocket?>(null) }
    var isConnecting by remember { mutableStateOf(false) } // NEW: State to prevent multiple connection attempts

    // Internal function to handle the actual connection logic
    fun doConnect(device: BluetoothDevice) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                launch(Dispatchers.Main) { connectionStatus = "Connecting..." }
                val socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                delay(500) // Give the system a moment
                socket.connect()
                launch(Dispatchers.Main) {
                    connectionStatus = "Connected!"
                    connectedDevice = device
                    bluetoothSocket = socket
                    isConnecting = false
                }
            } catch (e: IOException) {
                Log.e("BluetoothScreen", "Connection failed", e)
                launch(Dispatchers.Main) {
                    connectionStatus = "Connection failed."
                    isConnecting = false
                }
            }
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Log.d("BluetoothScreen", "Bluetooth enabling was denied.")
        }
    }

    val discoveryReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when(intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let {
                            if (!discoveredDevices.any { d -> d.address == it.address }) {
                                discoveredDevices = discoveredDevices + it
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> isScanning = false
                    
                    // UPDATED: More robust handling of pairing state changes
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val deviceFromIntent: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)

                        when (bondState) {
                            BluetoothDevice.BOND_BONDED -> {
                                deviceFromIntent?.let {
                                    Log.d("BluetoothScreen", "Pairing successful. Now connecting...")
                                    doConnect(it) // Automatically connect after pairing
                                }
                            }
                            BluetoothDevice.BOND_NONE -> {
                                Log.d("BluetoothScreen", "Pairing failed or removed.")
                                if (isConnecting) { // If we initiated the pairing
                                    coroutineScope.launch(Dispatchers.Main) {
                                        connectionStatus = "Pairing failed."
                                        isConnecting = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(key1 = context) {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED) // ADDED
        }
        context.registerReceiver(discoveryReceiver, filter)
        onDispose {
            context.unregisterReceiver(discoveryReceiver)
            bluetoothSocket?.close()
        }
    }

    fun startDiscovery() {
        if (isConnecting) return
        if (!bluetoothAdapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
        discoveredDevices = emptyList()
        bluetoothAdapter.startDiscovery()
        isScanning = true
    }

    fun connectToDevice(device: BluetoothDevice) {
        if (isConnecting) return // Prevent new attempts while one is active

        if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            Log.d("BluetoothScreen", "Device already paired. Connecting...")
            isConnecting = true
            doConnect(device)
        } else {
            Log.d("BluetoothScreen", "Device not paired. Starting pairing process...")
            isConnecting = true
            connectionStatus = "Pairing with ${device.name ?: "Unnamed"}..."
            device.createBond()
        }
    }

    // --- UI Layout ---
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (connectedDevice == null) {
            // --- Scanning UI ---
            Button(onClick = { startDiscovery() }, enabled = !isScanning && !isConnecting) {
                Text(if (isScanning) "Scanning..." else "Scan for Devices")
            }
            connectionStatus?.let { Text(it, modifier = Modifier.padding(vertical = 8.dp)) }
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(discoveredDevices) { device ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = !isConnecting) { 
                        connectToDevice(device)
                    }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(device.name ?: "Unnamed Device", style = MaterialTheme.typography.titleMedium)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            // --- Connected UI ---
            Text("Connected to:", style = MaterialTheme.typography.titleMedium)
            Text(connectedDevice?.name ?: "Unnamed Device", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                try {
                    bluetoothSocket?.close()
                } catch (e: IOException) {
                    Log.e("BluetoothScreen", "Failed to close socket", e)
                }
                connectedDevice = null
                connectionStatus = null
            }) {
                Text("Disconnect")
            }
        }
    }
}