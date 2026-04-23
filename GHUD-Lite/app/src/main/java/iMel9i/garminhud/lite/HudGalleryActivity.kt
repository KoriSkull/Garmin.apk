package iMel9i.garminhud.lite

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity

class HudGalleryActivity : AppCompatActivity() {

    private lateinit var hud: HudEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hud_gallery)

        hud = HudEngineFactory.create(this)
        hud.onConnectionStateChanged = { connected, deviceName ->
            runOnUiThread {
                if (connected) {
                    Toast.makeText(this, "HUD connected: ${deviceName ?: "Unknown"}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val listView = findViewById<ListView>(R.id.listHudIcons)
        val icons = HudIcon.values()

        val adapter = HudIconAdapter(this, icons)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val icon = icons[position]
            previewIcon(icon)
        }
    }


    override fun onResume() {
        super.onResume()
        tryConnectSavedHud(showToastOnFail = false)
    }

    private fun previewIcon(icon: HudIcon) {
        if (!hud.isConnected()) {
            val started = tryConnectSavedHud(showToastOnFail = true)
            if (started) {
                Toast.makeText(this, "Connecting to HUD... tap again in a moment", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Send to HUD
        // Reset current state to ensure valid drawing
        hud.setDirection(icon.type, icon.angle)
        
        // Special handling for Camera (needs setSpeedWithLimit to show/hide generic camera icon if extended logic isn't used)
        if (icon.isCamera) {
            // Force speed limit update with camera flag
             hud.setSpeedWithLimit(HudState.currentSpeed, HudState.speedLimit, false, true)
        } else {
             // Clear camera if selecting a non-camera icon to see it clearly (optional)
             hud.setSpeedWithLimit(HudState.currentSpeed, HudState.speedLimit, false, false)
        }

        Toast.makeText(this, "Sent: ${icon.displayName}", Toast.LENGTH_SHORT).show()
    }


    private fun tryConnectSavedHud(showToastOnFail: Boolean): Boolean {
        if (hud.isConnected()) return true

        if (Build.VERSION.SDK_INT >= 31) {
            val hasBtConnect = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasBtConnect) {
                if (showToastOnFail) {
                    Toast.makeText(this, "Bluetooth permission is missing", Toast.LENGTH_SHORT).show()
                }
                return false
            }
        }

        val prefs = getSharedPreferences("HudPrefs", MODE_PRIVATE)
        val address = prefs.getString("device_address", null)
        if (address.isNullOrBlank()) {
            if (showToastOnFail) {
                Toast.makeText(this, "Select HUD device in Main screen first", Toast.LENGTH_SHORT).show()
            }
            return false
        }

        hud.connectToDevice(address)
        return true
    }

    private class HudIconAdapter(context: Context, icons: Array<HudIcon>) : 
        ArrayAdapter<HudIcon>(context, android.R.layout.simple_list_item_2, android.R.id.text1, icons) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)
            
            val icon = getItem(position)
            
            text1.text = icon?.displayName
            text2.text = "Type: 0x${Integer.toHexString(icon?.type ?: 0)}, Angle: 0x${Integer.toHexString(icon?.angle ?: 0)}"
            
            return view
        }
    }
}
