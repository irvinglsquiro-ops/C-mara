package com.tucamara.retro

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var modoActual = 0 // 0: Puro, 1: Frio, 2: Calido
    private val nombresModos = arrayOf("MODO: PURO", "MODO: FRIO", "MODO: CALIDO")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (todosLosPermisosConcedidos()) {
            iniciarCamara()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        findViewById<TextView>(R.id.btnModo).setOnClickListener {
            modoActual = (modoActual + 1) % 3
            (it as TextView).text = nombresModos[modoActual]
        }

        findViewById<TextView>(R.id.btnTomar).setOnClickListener {
            tomarFoto()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun iniciarCamara() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
            }

            // Forzar resolución 640x480 (VGA)
            imageCapture = ImageCapture.Builder()
                .setTargetResolution(Size(640, 480))
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Toast.makeText(this, "Error al iniciar cámara", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun tomarFoto() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(ContextCompat.getMainExecutor(this), object :
            ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                procesarYGuardarFoto(image)
            }
            override fun onError(exc: ImageCaptureException) {
                Toast.makeText(baseContext, "Error al tomar foto", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun procesarYGuardarFoto(image: ImageProxy) {
        // Convertir imagen a Bitmap
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Rotar si es necesario
        val matrix = Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        // Aplicar filtros inmediatos según el modo
        val bitmapFinal = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
        val canvas = Canvas(bitmapFinal)
        val paint = Paint()

        when (modoActual) {
            0 -> {
                // MODO PURO: Sin alteraciones
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
            1 -> {
                // MODO FRIO: Contraste aumentado y tonos azules
                val colorMatrix = ColorMatrix(floatArrayOf(
                    1.1f, 0f, 0f, 0f, -10f, // Rojo
                    0f, 1.1f, 0f, 0f, -10f, // Verde
                    0f, 0f, 1.3f, 0f, 20f,  // Azul
                    0f, 0f, 0f, 1f, 0f
                ))
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                agregarRuido(bitmapFinal)
            }
            2 -> {
                // MODO CALIDO: Contraste aumentado y tonos naranjas
                val colorMatrix = ColorMatrix(floatArrayOf(
                    1.3f, 0f, 0f, 0f, 20f,  // Rojo
                    0f, 1.2f, 0f, 0f, 10f,  // Verde
                    0f, 0f, 0.9f, 0f, -20f, // Azul
                    0f, 0f, 0f, 1f, 0f
                ))
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                agregarRuido(bitmapFinal)
            }
        }

        guardarEnGaleria(bitmapFinal)
        image.close()
        Toast.makeText(this, "¡Guardada!", Toast.LENGTH_SHORT).show()
    }

    private fun agregarRuido(bitmap: Bitmap) {
        // Ruido digital ligero y rápido
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val random = Random()
        for (i in pixels.indices step 7) { // Step 7 para hacerlo inmediato
            val color = pixels[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val noise = random.nextInt(30) - 15
            pixels[i] = Color.rgb(
                (r + noise).coerceIn(0, 255),
                (g + noise).coerceIn(0, 255),
                (b + noise).coerceIn(0, 255)
            )
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun guardarEnGaleria(bitmap: Bitmap) {
        // Lógica para DDMMAAAADIA#
        val prefs = getSharedPreferences("CamRetroPrefs", Context.MODE_PRIVATE)
        val fechaActual = Date()
        val formatoFecha = SimpleDateFormat("ddMMyyyy", Locale.getDefault())
        val formatoDia = SimpleDateFormat("EEE", Locale("es", "ES"))
        
        val fechaStr = formatoFecha.format(fechaActual)
        var diaStr = formatoDia.format(fechaActual).uppercase(Locale.getDefault())
        diaStr = diaStr.replace("Á", "A").replace("É", "E").replace("Í", "I").replace("Ó", "O").replace("Ú", "U").take(3)
        
        val ultimaFecha = prefs.getString("lastDate", "")
        var contador = prefs.getInt("counter", 1)
        if (ultimaFecha != fechaStr) {
            contador = 1
        }
        
        val extension = if (modoActual == 0) ".png" else ".jpg"
        val mimeType = if (modoActual == 0) "image/png" else "image/jpeg"
        val nombreArchivo = "$fechaStr$diaStr$contador$extension"
        
        prefs.edit().putString("lastDate", fechaStr).putInt("counter", contador + 1).apply()

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nombreArchivo)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CamaraRetro")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            val outputStream: OutputStream? = contentResolver.openOutputStream(it)
            outputStream?.use { stream ->
                if (modoActual == 0) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) // Sin compresión
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream) // Compresión media
                }
            }
        }
    }

    private fun todosLosPermisosConcedidos() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (todosLosPermisosConcedidos()) {
                iniciarCamara()
            } else {
                Toast.makeText(this, "Permisos denegados", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
