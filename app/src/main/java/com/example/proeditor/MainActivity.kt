package com.example.proeditor

import android.net.Uri
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.example.proeditor.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedUri: Uri? = null
    private var session: FFmpegSession? = null

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedUri = it
            binding.btnSelectFile.text = "✅ Archivo Cargado"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
    }

    private fun setupUI() {
        binding.btnSelectFile.setOnClickListener { getContent.launch("*/*") }
        
        binding.sbCompression.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val quality = when {
                    progress < 18 -> "Sin Pérdida"
                    progress < 23 -> "Alta Calidad"
                    progress < 28 -> "Estándar"
                    else -> "Ligero"
                }
                binding.tvCompressionVal.text = "CRF: $progress ($quality)"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.btnProcess.setOnClickListener {
            if (selectedUri == null) {
                Toast.makeText(this, "⚠️ Selecciona un archivo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startProcessing()
        }
    }

    private fun startProcessing() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.btnProcess.isEnabled = false
        binding.tvStatus.text = "Procesando..."

        val inputPath = selectedUri.toString()
        val outputPath = File(cacheDir, "EDITADO_${System.currentTimeMillis()}.mp4").absolutePath
        val command = buildCommand(inputPath, outputPath)

        session = FFmpegKit.executeAsync(command, { session ->
            runOnUiThread {
                binding.progressBar.visibility = android.view.View.GONE
                binding.btnProcess.isEnabled = true
                if (ReturnCode.isSuccess(session.returnCode)) {
                    binding.tvStatus.text = "✅ ¡Completado!"
                    Toast.makeText(this, "Archivo en caché", Toast.LENGTH_LONG).show()
                } else {
                    binding.tvStatus.text = "❌ Error"
                    Toast.makeText(this, "Fallo: ${session.failStackTrace}", Toast.LENGTH_LONG).show()
                }
            }
        }, null, null)
    }

    private fun buildCommand(input: String, output: String): String {
        val sb = StringBuilder()
        sb.append("-i \"$input\"")
        val filters = mutableListOf<String>()

        val start = binding.etStartTime.text.toString().ifEmpty { "0" }
        val end = binding.etEndTime.text.toString()
        if (end.isNotEmpty()) {
            val duration = end.toDouble() - start.toDouble()
            if (duration > 0) filters.add("trim=start=$start:duration=$duration,setpts=PTS-STARTPTS")
        }

        val w = binding.etScaleWidth.text.toString()
        val h = binding.etScaleHeight.text.toString()
        if (w.isNotEmpty() || h.isNotEmpty()) {
            filters.add("scale=${if (w.isEmpty()) "-2" else w}:${if (h.isEmpty()) "-2" else h}")        }

        val speed = binding.etSpeedFactor.text.toString().ifEmpty { "1.0" }.toDouble()
        if (speed != 1.0) {
            filters.add("setpts=${1.0 / speed}*PTS")
        }

        if (binding.cbInterpolate.isChecked) {
            val fps = binding.etTargetFps.text.toString().ifEmpty { "60" }
            filters.add("minterpolate=fps=$fps:mi_mode=mci:mc_mode=aobmc:vsbmc=1")
        }

        if (filters.isNotEmpty()) sb.append(" -vf \"${filters.joinToString(",")}\"")

        if (speed != 1.0) {
            val safeSpeed = speed.coerceIn(0.5, 2.0)
            sb.append(" -af \"atempo=$safeSpeed\"")
        }

        val crf = binding.sbCompression.progress
        sb.append(" -c:v libx264 -preset medium -crf $crf -c:a aac -b:a 192k -y \"$output\"")

        return sb.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.cancel()
    }
}