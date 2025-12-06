package iMel9i.garminhud.lite

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Date

class IconMappingActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: IconAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple Vertical Layout
        val layout = androidx.appcompat.widget.LinearLayoutCompat(this)
        layout.orientation = androidx.appcompat.widget.LinearLayoutCompat.VERTICAL
        setContentView(layout)
        
        // Toolbar/Title
        val title = TextView(this)
        title.text = "Icon Mappings"
        title.textSize = 24f
        title.setPadding(32, 32, 32, 32)
        layout.addView(title)
        
        // List
        recyclerView = RecyclerView(this)
        recyclerView.layoutManager = LinearLayoutManager(this)
        layout.addView(recyclerView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        
        // Empty View
        val emptyView = TextView(this)
        emptyView.id = View.generateViewId()
        emptyView.text = "Список пуст.\n\n1. Убедитесь, что сервис 'Анализ экрана' включен.\n2. Включите навигацию (Яндекс/Google).\n3. Найденные стрелки появятся здесь автоматически."
        emptyView.gravity = android.view.Gravity.CENTER
        emptyView.textSize = 18f
        emptyView.setPadding(64, 64, 64, 64)
        emptyView.visibility = View.GONE
        layout.addView(emptyView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        
        loadData(emptyView)
    }
    
    private fun loadData(emptyView: TextView) {
        CapturedIconRepository.init(this)
        
        // Debug info
        val count = CapturedIconRepository.getIcons().size
        val dir = java.io.File(filesDir, "captured_icons")
        val fileCount = dir.listFiles()?.size ?: 0
        Toast.makeText(this, "Loaded $count icons ($fileCount files in ${dir.name})", Toast.LENGTH_LONG).show()
        
        val icons = CapturedIconRepository.getIcons()
        
        if (icons.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            emptyView.text = "${emptyView.text}\n\n(Debug: Files found: $fileCount)"
            recyclerView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter = IconAdapter(icons)
            recyclerView.adapter = adapter
        }
    }

    inner class IconAdapter(private val items: List<CapturedIconRepository.CapturedIcon>) : 
        RecyclerView.Adapter<IconAdapter.IconViewHolder>() {

        private val hudOptions = HudIcon.values()

        inner class IconViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val img: ImageView = itemView.findViewById(R.id.img_captured)
            val hash: TextView = itemView.findViewById(R.id.text_hash)
            val spinner: Spinner = itemView.findViewById(R.id.spinner_mapping)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_captured_icon, parent, false)
            return IconViewHolder(view)
        }

        override fun onBindViewHolder(holder: IconViewHolder, position: Int) {
            val item = items[position]
            
            // Set Image
            // Note: In production, consider a thread/coroutine for bitmap loading
            val bitmap = BitmapFactory.decodeFile(item.bitmapPath)
            holder.img.setImageBitmap(bitmap)
            
            // Set Text
            val date = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(Date(item.timestamp))
            holder.hash.text = "$date\nHash: ${item.hash}"
            
            // Set Spinner
            val labels = hudOptions.map { it.displayName }
            val adapter = ArrayAdapter(holder.itemView.context, android.R.layout.simple_spinner_item, labels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            holder.spinner.adapter = adapter
            
            // Set Selection
            if (item.mappedIcon != null) {
                holder.spinner.setSelection(hudOptions.indexOf(item.mappedIcon))
            } else {
                holder.spinner.setSelection(hudOptions.indexOf(HudIcon.NONE))
            }
            
            // Listener
            holder.spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val selected = hudOptions[pos]
                    if (selected != item.mappedIcon) {
                        CapturedIconRepository.updateMapping(holder.itemView.context, item.hash, selected)
                        item.mappedIcon = selected
                        // Toast.makeText(holder.itemView.context, "Mapped to ${selected.displayName}", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
