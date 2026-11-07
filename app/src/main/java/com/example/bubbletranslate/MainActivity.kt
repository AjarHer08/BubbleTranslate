package com.example.bubbletranslate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val sources = listOf(
        "Otomatis (deteksi)" to "auto", "Jepang (manga)" to "ja",
        "Mandarin (manhua)" to "zh", "Korea (manhwa)" to "ko", "Inggris" to "en"
    )
    private val targets = listOf(
        "Indonesia" to "id", "Inggris" to "en", "Melayu" to "ms", "Spanyol" to "es",
        "Prancis" to "fr", "Jerman" to "de", "Portugis" to "pt", "Rusia" to "ru",
        "Thai" to "th", "Vietnam" to "vi", "Arab" to "ar", "Hindi" to "hi"
    )

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                val i = Intent(this, BubbleService::class.java)
                    .putExtra("code", res.resultCode).putExtra("data", res.data)
                ContextCompat.startForegroundService(this, i)
                Toast.makeText(this, "Bubble aktif. Buka aplikasi manga Anda.", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else toast("Izin rekam layar ditolak")
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askProjection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 15f; setPadding(0, pad / 2, 0, 4) }
        fun spinner(items: List<Pair<String, String>>, key: String, def: String) = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items.map { it.first })
            setSelection(items.indexOfFirst { it.second == prefs.getString(key, def) }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    prefs.edit().putString(key, items[pos].second).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        fun seek(title: String, key: String, def: Int, max: Int): LinearLayout {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val tv = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, 4) }
            val sb = SeekBar(this).apply { this.max = max; progress = prefs.getInt(key, def) }
            tv.text = "$title: ${sb.progress}%"
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = "$title: $p%"; prefs.edit().putInt(key, p).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            col.addView(tv); col.addView(sb); return col
        }

        root.addView(TextView(this).apply { text = "Bubble Translate"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Ketuk bubble sekali = mode otomatis ON (hijau). Halaman/chapter baru diterjemahkan sendiri.\nKetuk lagi = OFF.\nTekan lama = terjemahkan ulang halaman ini.\nSeret bubble untuk memindahkan."
            setPadding(0, pad / 2, 0, pad / 2)
        })
        root.addView(label("Bahasa sumber")); root.addView(spinner(sources, "src", "auto"))
        root.addView(label("Terjemahkan ke")); root.addView(spinner(targets, "tgt", "id"))
        root.addView(seek("Abaikan bagian atas layar (bar status/alamat browser)", "cropTop", 10, 30))
        root.addView(seek("Abaikan bagian bawah layar", "cropBottom", 0, 20))
        root.addView(Button(this).apply { text = "Mulai Bubble"; setOnClickListener { start() } })
        root.addView(Button(this).apply {
            text = "Hentikan Bubble"
            setOnClickListener { startService(Intent(this@MainActivity, BubbleService::class.java).setAction(BubbleService.ACTION_STOP)) }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Aktifkan 'Tampil di atas aplikasi lain', lalu kembali dan tekan Mulai")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else askProjection()
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
