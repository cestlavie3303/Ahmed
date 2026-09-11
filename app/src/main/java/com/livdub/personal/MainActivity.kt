package com.livdub.personal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.livdub.personal.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRunning = false
    private var langCodes: Array<String> = arrayOf()

    private val requestPermissions = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            proceedStart()
        }
    }

    private val requestProjection = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            launchService(mode = "system", projResultCode = result.resultCode, projData = result.data)
        } else {
            binding.textStatus.text = "تم رفض تصريح التقاط صوت النظام"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        langCodes = resources.getStringArray(R.array.lang_codes)
        val langNames = resources.getStringArray(R.array.lang_names)
        binding.spinnerLang.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, langNames
        )

        binding.editApiKey.setText(Prefs.getApiKey(this))
        val savedLang = Prefs.getTargetLang(this)
        val idx = langCodes.indexOf(savedLang).let { if (it >= 0) it else 0 }
        binding.spinnerLang.setSelection(idx)

        binding.btnToggle.setOnClickListener {
            if (isRunning) {
                stopDubbing()
            } else {
                startDubbing()
            }
        }
    }

    private fun startDubbing() {
        val apiKey = binding.editApiKey.text.toString().trim()
        if (apiKey.isEmpty()) {
            binding.textStatus.text = "لازم تدخل مفتاح Gemini API أولاً"
            return
        }
        Prefs.saveApiKey(this, apiKey)
        Prefs.saveTargetLang(this, langCodes[binding.spinnerLang.selectedItemPosition])

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions.launch(missing.toTypedArray())
        } else {
            proceedStart()
        }
    }

    private fun proceedStart() {
        val useSystem = binding.radioSystem.isChecked
        if (useSystem) {
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            requestProjection.launch(pm.createScreenCaptureIntent())
        } else {
            launchService(mode = "mic", projResultCode = null, projData = null)
        }
    }

    private fun launchService(mode: String, projResultCode: Int?, projData: Intent?) {
        val apiKey = binding.editApiKey.text.toString().trim()
        val lang = langCodes[binding.spinnerLang.selectedItemPosition]

        val intent = Intent(this, DubbingService::class.java).apply {
            putExtra(DubbingService.EXTRA_API_KEY, apiKey)
            putExtra(DubbingService.EXTRA_TARGET_LANG, lang)
            putExtra(DubbingService.EXTRA_MODE, mode)
            if (projResultCode != null) putExtra(DubbingService.EXTRA_PROJECTION_RESULT_CODE, projResultCode)
            if (projData != null) putExtra(DubbingService.EXTRA_PROJECTION_DATA, projData)
        }
        ContextCompat.startForegroundService(this, intent)

        isRunning = true
        binding.btnToggle.text = getString(R.string.btn_stop)
        binding.textStatus.text = "جاري الاتصال…"
    }

    private fun stopDubbing() {
        val intent = Intent(this, DubbingService::class.java).apply {
            action = DubbingService.ACTION_STOP
        }
        startService(intent)
        isRunning = false
        binding.btnToggle.text = getString(R.string.btn_start)
        binding.textStatus.text = getString(R.string.status_idle)
    }
}
