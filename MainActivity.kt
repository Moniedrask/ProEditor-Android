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

    // Selector de archivos
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
        
        // Control visual de la barra de compresión
        binding.sbCompression.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val quality = when {
                    progress < 18 -> "Sin Pérdida (Gigante)"
                    progress < 23 -> "Alta Calidad"
                    progress < 28 -> "Estándar"
                    else -> "Baja Calidad (Ligero)"
                }
                binding.tvCompressionVal.text = "Nivel: $progress ($quality)"
            }            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnProcess.setOnClickListener {
            if (selectedUri == null) {
                Toast.makeText(this, "⚠️ Selecciona un archivo primero", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startProcessing()
        }
    }

    private fun startProcessing() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.btnProcess.isEnabled = false
        binding.tvStatus.text = "Procesando... (Esto puede tardar)"

        val inputPath = selectedUri.toString() // FFmpegKit maneja content:// URI
        val outputPath = File(cacheDir, "PRO_EDITADO_${System.currentTimeMillis()}.mp4").absolutePath

        val command = buildProCommand(inputPath, outputPath)

        session = FFmpegKit.executeAsync(command, { session ->
            runOnUiThread {
                binding.progressBar.visibility = android.view.View.GONE
                binding.btnProcess.isEnabled = true
                
                if (ReturnCode.isSuccess(session.returnCode)) {
                    binding.tvStatus.text = "✅ ¡Éxito! Archivo en caché."
                    Toast.makeText(this, "Proceso completado", Toast.LENGTH_LONG).show()
                } else {
                    binding.tvStatus.text = "❌ Error en el proceso."
                    Toast.makeText(this, "Fallo: ${session.failStackTrace}", Toast.LENGTH_LONG).show()
                }
            }
        }, { log -> 
            // Logs en tiempo real
        }, { statistics ->
            // Actualizar barra de progreso basada en tiempo
            val time = statistics.getTime()
            // Aquí podrías actualizar una barra de progreso real si calculas la duración total
        })
    }

    /**
     * GENERADOR DE COMANDOS PROFESIONALES
     * Escalado, Recorte, Velocidad, Interpolación y Compresión
     */
    private fun buildProCommand(input: String, output: String): String {        val sb = StringBuilder()
        sb.append("-i \"$input\"")

        val filters = mutableListOf<String>()

        // 1. RECORTE DE TIEMPO PRECISO (Timeline Start/End)
        val start = binding.etStartTime.text.toString().ifEmpty { "0" }
        val end = binding.etEndTime.text.toString()
        
        if (end.isNotEmpty()) {
            val duration = end.toDouble() - start.toDouble()
            if (duration > 0) {
                filters.add("trim=start=$start:duration=$duration,setpts=PTS-STARTPTS")
            }
        } else if (start.toDouble() > 0) {
             filters.add("trim=start=$start,setpts=PTS-STARTPTS")
        }

        // 2. ESCALADO (Resolución hasta x16 o manual)
        val w = binding.etScaleWidth.text.toString()
        val h = binding.etScaleHeight.text.toString()
        if (w.isNotEmpty() || h.isNotEmpty()) {
            // -2 mantiene la proporción si solo das un lado
            val scaleW = if (w.isEmpty()) "-2" else w
            val scaleH = if (h.isEmpty()) "-2" else h
            filters.add("scale=$scaleW:$scaleH")
        }

        // 3. VELOCIDAD (Cámara Lenta/Rápida Precisa)
        val speed = binding.etSpeedFactor.text.toString().ifEmpty { "1.0" }.toDouble()
        if (speed != 1.0) {
            // Ajuste de timestamps de video
            val ptsFactor = 1.0 / speed
            filters.add("setpts=${ptsFactor}*PTS")
        }

        // 4. INTERPOLACIÓN DE CUADROS (FPS hasta x4)
        // Nota: minterpolate es pesado. Usamos modo MCI para mejor calidad.
        if (binding.cbInterpolate.isChecked) {
            val fps = binding.etTargetFps.text.toString().ifEmpty { "60" }
            filters.add("minterpolate=fps=$fps:mi_mode=mci:mc_mode=aobmc:vsbmc=1")
        }

        // Unir filtros de video
        var videoFilterString = ""
        if (filters.isNotEmpty()) {
            videoFilterString = "-vf \"${filters.joinToString(",")}\""
        }

        // 5. AUDIO (Ajuste de tono/velocidad)        var audioFilterString = ""
        if (speed != 1.0) {
            // atempo soporta 0.5 a 2.0. Si es más extremo, se requiere cadena compleja.
            // Limitamos para estabilidad en este ejemplo básico.
            val safeSpeed = speed.coerceIn(0.5, 2.0)
            audioFilterString = "-af \"atempo=$safeSpeed\""
        }

        // 6. COMPRESIÓN (Codec H.264 + CRF)
        val crf = binding.sbCompression.progress
        
        sb.append(" $videoFilterString")
        if (audioFilterString.isNotEmpty()) sb.append(" $audioFilterString")
        
        // Codificación final
        sb.append(" -c:v libx264 -preset medium -crf $crf -c:a aac -b:a 192k -movflags +faststart -y \"$output\"")

        return sb.toString()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        session?.cancel()
    }
}