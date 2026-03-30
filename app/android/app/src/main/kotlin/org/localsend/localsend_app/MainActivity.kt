package cn.luyii.localsend_pro

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID


private const val CHANNEL = "cn.luyii.localsend_pro/localsend"
private val BLUETOOTH_FILE_TRANSFER_UUID: UUID = UUID.fromString("bd7d6f8d-98d3-44fd-a4f6-a8084fd3d6db")
private const val REQUEST_CODE_PICK_DIRECTORY = 1
private const val REQUEST_CODE_PICK_DIRECTORY_PATH = 2
private const val REQUEST_CODE_PICK_FILE = 3
private const val REQUEST_CODE_BLUETOOTH_PERMISSIONS = 4

class MainActivity : FlutterActivity() {
    private var pendingResult: MethodChannel.Result? = null
    private var pendingBluetoothPermissionResult: MethodChannel.Result? = null
    private var bluetoothReceiverRegistered = false
    private var bluetoothFileServerRunning = false
    private var bluetoothServerSocket: BluetoothServerSocket? = null
    private var bluetoothServerThread: Thread? = null
    private val discoveredBluetoothDevices: LinkedHashMap<String, Map<String, String?>> = linkedMapOf()
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    addBluetoothDevice(device)
                }
            }
        }
    }

    // Overriding the static methods we need from the Java class, as described
    // in the documentation of `FlutterActivity.NewEngineIntentBuilder`
    companion object {
        fun withNewEngine(): NewEngineIntentBuilder {
            return NewEngineIntentBuilder(MainActivity::class.java)
        }

        fun createDefaultIntent(launchContext: Context): Intent {
            return withNewEngine().build(launchContext)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "pickDirectory" -> {
                    pendingResult = result
                    openDirectoryPicker(onlyPath = false)
                }

                "pickFiles" -> {
                    pendingResult = result
                    openFilePicker()
                }

                "pickDirectoryPath" -> {
                    pendingResult = result
                    openDirectoryPicker(onlyPath = true)
                }

                "createDirectory" -> handleCreateDirectory(call, result)

                "openContentUri" -> {
                    openUri(context, call.argument<String>("uri")!!)
                    result.success(null)
                }

                "openGallery" -> {
                    openGallery()
                    result.success(null)
                }

                "isAnimationsEnabled" -> {
                    result.success(isAnimationsEnabled())
                }

                "scanBluetoothDevices" -> {
                    result.success(scanBluetoothDevices())
                }

                "getBluetoothSignalInfo" -> {
                    result.success(getBluetoothSignalInfo())
                }

                "startBluetoothFileServer" -> {
                    result.success(startBluetoothFileServer())
                }

                "sendBluetoothFile" -> {
                    val address = call.argument<String>("address")
                    val fileName = call.argument<String>("fileName")
                    val data = call.argument<ByteArray>("data")
                    if (address == null || fileName == null || data == null) {
                        result.success(false)
                    } else {
                        result.success(sendBluetoothFile(address, fileName, data))
                    }
                }

                "requestBluetoothPermissions" -> {
                    requestBluetoothPermissions(result)
                }

                else -> result.notImplemented()
            }
        }
    }

    override fun onDestroy() {
        unregisterBluetoothReceiver()
        stopBluetoothFileServer()
        super.onDestroy()
    }

    private fun isAnimationsEnabled() : Boolean {
        return Settings.Global.getFloat(this.getContentResolver(),
            Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) != 0.0f;
    }

    private fun hasPermission(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun getRequiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
    }

    private fun requestBluetoothPermissions(result: MethodChannel.Result) {
        val permissions = getRequiredBluetoothPermissions()
        val allGranted = permissions.all { hasPermission(it) }
        if (allGranted) {
            result.success(true)
            return
        }

        pendingBluetoothPermissionResult = result
        ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_BLUETOOTH_PERMISSIONS)
    }

    private fun canScanBluetooth(adapter: BluetoothAdapter): Boolean {
        if (!adapter.isEnabled) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return hasPermission(android.Manifest.permission.BLUETOOTH_SCAN)
        }
        return hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun canReadBluetoothNames(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return hasPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        return true
    }

    private fun addBluetoothDevice(device: BluetoothDevice?) {
        if (device == null) {
            return
        }
        val address = device.address ?: return
        val canReadName = canReadBluetoothNames()
        discoveredBluetoothDevices[address] = mapOf(
            "address" to address,
            "name" to if (canReadName) device.name else null,
            "bondState" to device.bondState.toString(),
        )
    }

    private fun registerBluetoothReceiverIfNeeded() {
        if (bluetoothReceiverRegistered) {
            return
        }
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        bluetoothReceiverRegistered = true
    }

    private fun unregisterBluetoothReceiver() {
        if (!bluetoothReceiverRegistered) {
            return
        }
        unregisterReceiver(bluetoothReceiver)
        bluetoothReceiverRegistered = false
    }

    private fun scanBluetoothDevices(): List<Map<String, String?>> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        if (!canScanBluetooth(adapter)) {
            return emptyList()
        }

        registerBluetoothReceiverIfNeeded()
        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }

        val canReadName = canReadBluetoothNames()
        val bondedDevices = adapter.bondedDevices ?: emptySet()
        for (device in bondedDevices) {
            discoveredBluetoothDevices[device.address] = mapOf(
                "address" to device.address,
                "name" to if (canReadName) device.name else null,
                "bondState" to device.bondState.toString(),
            )
        }

        adapter.startDiscovery()
        return discoveredBluetoothDevices.values.toList()
    }

    private fun getBluetoothSignalInfo(): Map<String, String> {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val alias = Build.MODEL ?: "Android"
        val address = adapter?.address ?: "unknown"
        return mapOf(
            "alias" to alias,
            "id" to address,
            "transport" to "bluetooth",
        )
    }

    private fun startBluetoothFileServer(): Boolean {
        if (bluetoothFileServerRunning) {
            return true
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (!adapter.isEnabled || !canReadBluetoothNames()) {
            return false
        }

        bluetoothFileServerRunning = true
        bluetoothServerThread = Thread {
            try {
                bluetoothServerSocket = adapter.listenUsingRfcommWithServiceRecord(
                    "LocalSendProFileTransfer",
                    BLUETOOTH_FILE_TRANSFER_UUID
                )
                while (bluetoothFileServerRunning) {
                    val socket = bluetoothServerSocket?.accept() ?: break
                    handleIncomingBluetoothFile(socket)
                }
            } catch (_: Exception) {
            } finally {
                bluetoothFileServerRunning = false
                try {
                    bluetoothServerSocket?.close()
                } catch (_: Exception) {
                }
                bluetoothServerSocket = null
            }
        }.apply { start() }

        return true
    }

    private fun stopBluetoothFileServer() {
        bluetoothFileServerRunning = false
        try {
            bluetoothServerSocket?.close()
        } catch (_: Exception) {
        }
        bluetoothServerSocket = null
        bluetoothServerThread = null
    }

    private fun handleIncomingBluetoothFile(socket: BluetoothSocket) {
        try {
            val input = DataInputStream(socket.inputStream)
            val fileName = input.readUTF()
            val size = input.readLong().toInt()
            val buffer = ByteArray(size)
            input.readFully(buffer)

            val outputDir = getExternalFilesDir(null) ?: filesDir
            val file = File(outputDir, fileName)
            file.writeBytes(buffer)

            DataOutputStream(socket.outputStream).use { out ->
                out.writeUTF("OK")
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun sendBluetoothFile(address: String, fileName: String, data: ByteArray): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (!adapter.isEnabled || !canReadBluetoothNames()) {
            return false
        }

        return try {
            val device = adapter.getRemoteDevice(address)
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            val socket = device.createRfcommSocketToServiceRecord(BLUETOOTH_FILE_TRANSFER_UUID)
            socket.connect()
            DataOutputStream(socket.outputStream).use { out ->
                out.writeUTF(fileName)
                out.writeLong(data.size.toLong())
                out.write(data)
                out.flush()
            }
            val ack = DataInputStream(socket.inputStream).readUTF()
            socket.close()
            ack == "OK"
        } catch (_: Exception) {
            false
        }
    }

    private fun openDirectoryPicker(onlyPath: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(
            intent,
            if (onlyPath) REQUEST_CODE_PICK_DIRECTORY_PATH else REQUEST_CODE_PICK_DIRECTORY
        )
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra("multi-pick", true)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_CODE_PICK_FILE)
    }

    @SuppressLint("WrongConstant")
    @Override
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_CANCELED) {
            pendingResult?.error("CANCELED", "Canceled", null)
            pendingResult = null
            return
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            pendingResult?.error("Error $resultCode", "Failed to access directory or file", null)
            pendingResult = null
            return
        }

        when (requestCode) {
            REQUEST_CODE_PICK_DIRECTORY -> {
                val uri: Uri? = data.data
                val takeFlags: Int =
                    data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val files = mutableListOf<FileInfo>()
                    listFiles(uri, files)
                    val resultData = PickDirectoryResult(uri.toString(), files)
                    pendingResult?.success(resultData.toMap())
                    pendingResult = null
                } else {
                    pendingResult?.error("Error", "Failed to access directory", null)
                    pendingResult = null
                }
            }

            REQUEST_CODE_PICK_DIRECTORY_PATH -> {
                val uri: Uri? = data.data
                val takeFlags: Int =
                    data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                    pendingResult?.success(uri.toString())
                    pendingResult = null
                } else {
                    pendingResult?.error("Error", "Failed to access directory", null)
                    pendingResult = null
                }
            }

            REQUEST_CODE_PICK_FILE -> {
                val uriList: List<Uri> = when {
                    data.clipData != null -> {
                        val clipData = data.clipData
                        val uris = mutableListOf<Uri>()
                        for (i in 0 until clipData!!.itemCount) {
                            uris.add(clipData.getItemAt(i).uri)
                        }
                        uris
                    }

                    data.data != null -> listOf(data.data!!)
                    else -> {
                        pendingResult?.error("Error", "Failed to access file", null)
                        return
                    }
                }

                val takeFlags: Int =
                    data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                val resultList = mutableListOf<FileInfo>()
                for (uri in uriList) {
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                    val documentFile = FastDocumentFile.fromDocumentUri(this, uri)
                    if (documentFile == null) {
                        pendingResult?.error("Error", "Failed to access file", null)
                        return
                    }
                    resultList.add(
                        FileInfo(
                            name = documentFile.name,
                            size = documentFile.size,
                            uri = uri.toString(),
                            lastModified = documentFile.lastModified,
                        )
                    )
                }

                pendingResult?.success(resultList.map { it.toMap() })
                pendingResult = null
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_BLUETOOTH_PERMISSIONS) {
            return
        }

        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        pendingBluetoothPermissionResult?.success(granted)
        pendingBluetoothPermissionResult = null
    }

    private fun listFiles(uri: Uri, files: MutableList<FileInfo>) {
        val pickedDir: FastDocumentFile = FastDocumentFile.fromTreeUri(this, uri)

        for (file in pickedDir.listFiles()) {
            if (file.isDirectory) {
                // Recursive call
                listFiles(file.uri, files)
            } else if (file.isFile) {
                files.add(
                    FileInfo(
                        name = file.name,
                        size = file.size,
                        uri = file.uri.toString(),
                        lastModified = file.lastModified,
                    ),
                )
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun handleCreateDirectory(call: MethodCall, result: MethodChannel.Result) {
        val documentUri = Uri.parse(call.argument<String>("documentUri")!!)
        val directoryName = call.argument<String>("directoryName")!!

        if (folderExists(documentUri, directoryName)) {
            result.success(null)
            return
        }

        DocumentsContract.createDocument(
            context.contentResolver, documentUri, DocumentsContract.Document.MIME_TYPE_DIR,
            directoryName
        )

        result.success(null)
    }

    private fun folderExists(documentUri: Uri, folderName: String): Boolean {
        var cursor: Cursor? = null
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(documentUri, DocumentsContract.getDocumentId(documentUri))
            cursor = contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null,
            )

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(0)
                    val mimeType = cursor.getString(1)

                    if (folderName == displayName && DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
                        return true
                    }
                }
            }
        } finally {
            cursor?.close()
        }
        return false
    }

    private fun openGallery() {
        val intent = Intent()
        intent.action = Intent.ACTION_VIEW
        intent.type = "image/*"
        startActivity(intent)
    }
}

data class PickDirectoryResult(
    val directoryUri: String,
    val files: List<FileInfo>,
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "directoryUri" to directoryUri,
            "files" to files.map { it.toMap() }
        )
    }
}

data class FileInfo(
    val name: String,
    val size: Long,
    val uri: String,
    val lastModified: Long
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "name" to name,
            "size" to size,
            "uri" to uri,
            "lastModified" to lastModified
        )
    }
}
