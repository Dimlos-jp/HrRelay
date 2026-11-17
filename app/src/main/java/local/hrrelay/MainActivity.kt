package local.hrrelay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var macEdit: EditText
    private lateinit var timeoutEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(HrService.PREFS, MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Watch MAC address"
        })
        macEdit = EditText(this).apply {
            hint = "AA:BB:CC:DD:EE:FF"
            setText(prefs.getString(HrService.KEY_MAC, ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        root.addView(macEdit)

        root.addView(TextView(this).apply {
            text = "Seconds without data before reporting \"lost\" (min 10)"
        })
        timeoutEdit = EditText(this).apply {
            setText(prefs.getInt(HrService.KEY_TIMEOUT, 60).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(timeoutEdit)

        root.addView(Button(this).apply {
            text = "Start"
            setOnClickListener { startIfPermitted() }
        })
        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                stopService(Intent(this@MainActivity, HrService::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "Disable battery optimization"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })

        root.addView(TextView(this).apply {
            setPadding(0, pad, 0, 0)
            text = "Broadcast intents\n" +
                "- local.hrrelay.UPDATE  (bpm, ts)\n" +
                "- local.hrrelay.STATUS  (state, ts)\n" +
                "  state: connected / disconnected / lost / recovered / no_hr_service / stopped"
        })

        setContentView(root)
    }

    private fun save(): Boolean {
        val mac = macEdit.text.toString().trim().uppercase()
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(mac)) {
            Toast.makeText(this, "Invalid MAC address", Toast.LENGTH_SHORT).show()
            return false
        }
        val timeout = timeoutEdit.text.toString().toIntOrNull()?.coerceAtLeast(10) ?: 60
        getSharedPreferences(HrService.PREFS, MODE_PRIVATE).edit()
            .putString(HrService.KEY_MAC, mac)
            .putInt(HrService.KEY_TIMEOUT, timeout)
            .apply()
        return true
    }

    private fun missingPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) list += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    private fun startIfPermitted() {
        if (!save()) return
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            requestPermissions(missing, 1)
            return
        }
        startForegroundService(Intent(this, HrService::class.java))
        Toast.makeText(this, "Started", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startIfPermitted()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }
}
