package com.mikel.gyrowallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.random.Random

data class Spark(
    var x: Float,
    var y: Float,
    var radius: Float,
    var depth: Float,     // 0..1, controla intensidad del parallax y velocidad de subida
    var speedY: Float,    // deriva lenta hacia arriba, como una brasa real
    var alpha: Int,
    var warm: Boolean     // true = tono naranja/fuego, false = blanco/chispa fría
)

/**
 * Live wallpaper: tu imagen (calavera en llamas) como fondo con un ligero
 * efecto parallax al inclinar el móvil, más una capa de chispas/brasas
 * flotando por encima que reacciona con más fuerza al mismo movimiento.
 *
 * Ajusta las constantes de la sección "CONFIG" para cambiar
 * sensibilidad, densidad de chispas y cuánto "respira" la imagen.
 */
class GyroWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = GyroEngine()

    inner class GyroEngine : Engine(), SensorEventListener {

        // ---------- CONFIG ----------
        private val sparkCount = 55
        private val bgMaxOffsetPx = 150f     // cuánto se desplaza la imagen de fondo (parallax sutil)
        private val sparkMaxOffsetPx = 420f  // cuánto se desplazan las chispas (más notorio, primer plano)
        private val bgZoom = 1.35f           // margen extra de zoom para que el desplazamiento no deje bordes vacíos
        private val smoothing = 0.18f        // 0..1, más bajo = movimiento más lento y suave
        private val tiltSensitivity = 5.5f   // divisor de la gravedad: más bajo = reacciona con menos inclinación
        // -----------------------------

        private lateinit var sensorManager: SensorManager
        private var rotationSensor: Sensor? = null

        private var targetTiltX = 0f
        private var targetTiltY = 0f
        private var currentTiltX = 0f
        private var currentTiltY = 0f

        private var width = 0
        private var height = 0
        private var visible = false

        private var rawBitmap: Bitmap? = null
        private var scaledBitmap: Bitmap? = null
        private val sparks = mutableListOf<Spark>()
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val drawRunner = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            rawBitmap = BitmapFactory.decodeResource(resources, R.drawable.bg_flame_skull)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                registerSensor()
                handler.post(drawRunner)
            } else {
                unregisterSensor()
                handler.removeCallbacks(drawRunner)
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder, format: Int, w: Int, h: Int
        ) {
            width = w
            height = h
            prepareScaledBitmap()
            if (sparks.isEmpty()) generateSparks()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            unregisterSensor()
            handler.removeCallbacks(drawRunner)
        }

        override fun onDestroy() {
            super.onDestroy()
            scaledBitmap?.recycle()
            rawBitmap?.recycle()
        }

        private fun registerSensor() {
            rotationSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        private fun unregisterSensor() {
            sensorManager.unregisterListener(this)
        }

        /** Escala la imagen tipo "center crop" al tamaño de pantalla, con un zoom extra
         *  para tener margen de sobra al desplazarla con el parallax. */
        private fun prepareScaledBitmap() {
            val src = rawBitmap ?: return
            if (width == 0 || height == 0) return

            val targetW = (width * bgZoom).toInt()
            val targetH = (height * bgZoom).toInt()

            val scale = maxOf(
                targetW.toFloat() / src.width,
                targetH.toFloat() / src.height
            )
            val scaledW = (src.width * scale).toInt()
            val scaledH = (src.height * scale).toInt()

            val fullyScaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

            val cropX = ((scaledW - targetW) / 2).coerceAtLeast(0)
            val cropY = ((scaledH - targetH) / 2).coerceAtLeast(0)
            val cropW = targetW.coerceAtMost(scaledW - cropX)
            val cropH = targetH.coerceAtMost(scaledH - cropY)

            scaledBitmap?.recycle()
            scaledBitmap = Bitmap.createBitmap(fullyScaled, cropX, cropY, cropW, cropH)
            if (fullyScaled != scaledBitmap) fullyScaled.recycle()
        }

        private fun generateSparks() {
            sparks.clear()
            repeat(sparkCount) {
                sparks.add(
                    Spark(
                        x = Random.nextFloat() * width,
                        y = Random.nextFloat() * height,
                        radius = Random.nextFloat() * 3.5f + 1f,
                        depth = Random.nextFloat(),
                        speedY = Random.nextFloat() * 0.35f + 0.08f,
                        alpha = (120..255).random(),
                        warm = Random.nextFloat() < 0.75f
                    )
                )
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    // values[0] = inclinación izquierda/derecha, values[1] = adelante/atrás
                    targetTiltX = (event.values[0] / tiltSensitivity).coerceIn(-1f, 1f)
                    targetTiltY = (event.values[1] / tiltSensitivity).coerceIn(-1f, 1f)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    currentTiltX += (targetTiltX - currentTiltX) * smoothing
                    currentTiltY += (targetTiltY - currentTiltY) * smoothing
                    render(canvas)
                }
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
            handler.removeCallbacks(drawRunner)
            if (visible) {
                handler.postDelayed(drawRunner, 16L) // ~60 fps
            }
        }

        private fun render(canvas: Canvas) {
            if (width == 0 || height == 0) return
            canvas.drawColor(Color.BLACK)

            // --- Fondo: tu imagen, con parallax sutil ---
            scaledBitmap?.let { bmp ->
                val offsetX = currentTiltX * bgMaxOffsetPx
                val offsetY = -currentTiltY * bgMaxOffsetPx

                val left = ((bmp.width - width) / 2f) - offsetX
                val top = ((bmp.height - height) / 2f) - offsetY

                val srcLeft = left.coerceIn(0f, (bmp.width - width).toFloat().coerceAtLeast(0f))
                val srcTop = top.coerceIn(0f, (bmp.height - height).toFloat().coerceAtLeast(0f))

                val srcRect = Rect(
                    srcLeft.toInt(), srcTop.toInt(),
                    (srcLeft.toInt() + width).coerceAtMost(bmp.width),
                    (srcTop.toInt() + height).coerceAtMost(bmp.height)
                )
                val dstRect = Rect(0, 0, width, height)
                canvas.drawBitmap(bmp, srcRect, dstRect, null)
            }

            // --- Capa de chispas/brasas flotando, con parallax más marcado ---
            val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (s in sparks) {
                // deriva lenta hacia arriba (como una brasa real)
                s.y -= s.speedY
                if (s.y < -10f) {
                    s.y = height + 10f
                    s.x = Random.nextFloat() * width
                }

                val offsetX = currentTiltX * sparkMaxOffsetPx * s.depth
                val offsetY = -currentTiltY * sparkMaxOffsetPx * s.depth

                sparkPaint.color = if (s.warm) Color.parseColor("#FFA53E") else Color.parseColor("#FFF4D6")
                sparkPaint.alpha = s.alpha
                canvas.drawCircle(s.x + offsetX, s.y + offsetY, s.radius, sparkPaint)
            }
        }
    }
}
