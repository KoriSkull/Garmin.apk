package iMel9i.garminhud.lite

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class HudGalleryActivity : AppCompatActivity() {

    private lateinit var hud: GarminHudLite

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hud_gallery)

        hud = GarminHudLite(this)

        val listView = findViewById<ListView>(R.id.listHudIcons)
        val icons = HudIcon.values()

        val adapter = HudIconAdapter(this, icons)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val icon = icons[position]
            previewIcon(icon)
        }
    }

    private fun previewIcon(icon: HudIcon) {
        if (!hud.isConnected()) {
             Toast.makeText(this, "HUD not connected", Toast.LENGTH_SHORT).show()
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
