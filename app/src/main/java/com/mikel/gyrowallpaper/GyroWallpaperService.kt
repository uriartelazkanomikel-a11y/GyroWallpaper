package com.mikel.gyrowallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
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
 * Live wallpaper en 3 capas para un efecto más espectacular:
 *  1) Fondo (llamas, luna, rocas) — se mueve poco, es el "telón de fondo".
 *  2) La calavera — recortada de la misma imagen, con bordes suavizados,
 *     y se mueve MUCHO más que el fondo: da la sensación de "flotar" o
 *     "salirse" de la imagen al mover el móvil.
 *  3) Chispas/brasas por encima de todo, con el parallax más fuerte.
 *
 * Reacciona a dos cosas a la vez: la inclinación estática (acelerómetro)
 * y cualquier movimiento/giro rápido (giroscopio, como un "empujón" que
 * se desvanece solo).
 *
 * Toca la sección "CONFIG" para ajustar todo esto.
 */
class GyroWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = GyroEngine()

    inner class GyroEngine : Engine(), SensorEventListener {

        // ---------- CONFIG ----------
        private val sparkCount = 55

        // Cuánto se mueve cada capa (multiplicador sobre la inclinación + el empujón)
        private val bgMoveFactor = 0.5f     // fondo: casi quieto
        private val faceMoveFactor = 2.4f   // calavera: el doble o más que el fondo -> "efecto pop"
        private val sparkMoveFactor = 3.2f  // chispas: lo que más se mueve, en primer plano

        private val bgMaxOffsetPx = 150f
        private val sparkMaxOffsetPx = 420f
        private val bgZoom = 1.35f          // margen extra para que el fondo no deje huecos al moverse
        private val faceZoom = 1.9f         // margen extra SOLO de la capa de la cara (se mueve más, necesita más colchón)

        private val smoothing = 0.22f       // 0..1, más alto = reacciona más rápido a la inclinación
        private val tiltSensitivity = 4f    // divisor de la gravedad: más bajo = reacciona con menos inclinación
        private val kickGain = 3500f        // fuerza del "empujón" al mover/girar el móvil rápido
        private val kickMax = 500f          // tope del empujón, en píxeles
        private val kickFriction = 0.90f    // 0..1, más alto = el empujón tarda más en desvanecerse

        // Recorte de la calavera dentro de tu imagen original, como fracción (0..1)
        // del ancho/alto. Ajusta estos 4 números si quieres recortar más o menos.
        private val faceLeftFrac = 0.13f
        private val faceTopFrac = 0.32f
        private val faceRightFrac = 0.87f
        private val faceBottomFrac = 0.64f
        // -----------------------------

        private lateinit var sensorManager: SensorManager
        private var accelSensor: Sensor? = null
        private var gyroSensor: Sensor? = null

        private var targetTiltX = 0f
        private var targetTiltY = 0f
        private var currentTiltX = 0f
        private var currentTiltY = 0f

        // "Empujón" extra por movimiento/giro rápido (no solo inclinación estática)
        private var kickX = 0f
        private var kickY = 0f

        private var width = 0
        private var height = 0
        private var visible = false

        private var rawBitmap: Bitmap? = null
        private var bgBitmap: Bitmap? = null
        private var faceBitmap: Bitmap? = null
        private var faceBaseX = 0f
        private var faceBaseY = 0f

        private val sparks = mutableListOf<Spark>()
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val drawRunner = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
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
            prepareLayers()
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
            bgBitmap?.recycle()
            faceBitmap?.recycle()
            rawBitmap?.recycle()
        }

        private fun registerSensor() {
            accelSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            gyroSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        private fun unregisterSensor() {
            sensorManager.unregisterListener(this)
        }

        /** Crea, a partir de tu imagen original, un bitmap "center crop" escalado
         *  al tamaño de pantalla + zoom extra (para poder desplazarlo sin dejar huecos). */
        private fun buildCoverBitmap(src: Bitmap, zoom: Float): Bitmap {
            val targetW = (width * zoom).toInt()
            val targetH = (height * zoom).toInt()

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

            val result = Bitmap.createBitmap(fullyScaled, cropX, cropY, cropW, cropH)
            if (fullyScaled != result) fullyScaled.recycle()
            return result
        }

        /** Recorta la zona de la calavera de un bitmap "cover" ya construido, y le
         *  aplica un degradado ovalado para que los bordes se disuelvan en vez de
         *  verse como un recorte cuadrado. También calcula dónde cae esa zona en
         *  pantalla cuando el desplazamiento es cero (posición de reposo). */
        private fun extractFaceLayer(coverBitmap: Bitmap) {
            val left = (faceLeftFrac * coverBitmap.width).toInt().coerceIn(0, coverBitmap.width - 1)
            val top = (faceTopFrac * coverBitmap.height).toInt().coerceIn(0, coverBitmap.height - 1)
            val right = (faceRightFrac * coverBitmap.width).toInt().coerceIn(left + 1, coverBitmap.width)
            val bottom = (faceBottomFrac * coverBitmap.height).toInt().coerceIn(top + 1, coverBitmap.height)

            val faceW = right - left
            val faceH = bottom - top
            val rawFace = Bitmap.createBitmap(coverBitmap, left, top, faceW, faceH)

            // Máscara: opaco en el centro, transparente en el borde (elipse suave)
            val masked = Bitmap.createBitmap(faceW, faceH, Bitmap.Config.ARGB_8888)
            val maskCanvas = Canvas(masked)
            maskCanvas.drawBitmap(rawFace, 0f, 0f, null)

            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            val cx = faceW / 2f
            val cy = faceH / 2f
            val radius = maxOf(cx, cy)
            maskPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
            maskCanvas.drawRect(0f, 0f, faceW.toFloat(), faceH.toFloat(), maskPaint)
            rawFace.recycle()

            faceBitmap?.recycle()
            faceBitmap = masked

            // Posición en pantalla cuando el desplazamiento del fondo es cero
            // (el fondo se dibuja centrado, así que restamos ese mismo margen).
            faceBaseX = left - (coverBitmap.width - width) / 2f
            faceBaseY = top - (coverBitmap.height - height) / 2f
        }

        private fun prepareLayers() {
            val src = rawBitmap ?: return
            if (width == 0 || height == 0) return

            bgBitmap?.recycle()
            bgBitmap = buildCoverBitmap(src, bgZoom)

            // La capa de la cara se recorta de una versión con MÁS zoom (más resolución
            // disponible), para que al ampliarla un poco no se vea pixelada.
            val faceCover = buildCoverBitmap(src, faceZoom)
            extractFaceLayer(faceCover)
            faceCover.recycle()
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
                    // Inclinación estática: hacia dónde tienes el móvil ahora mismo.
                    // values[0] = izquierda/derecha, values[1] = adelante/atrás
                    targetTiltX = (event.values[0] / tiltSensitivity).coerceIn(-1f, 1f)
                    targetTiltY = (event.values[1] / tiltSensitivity).coerceIn(-1f, 1f)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    // Empujón extra: reacciona a CUALQUIER movimiento/giro, no solo
                    // a la postura final. values están en rad/s (velocidad angular).
                    kickX = (kickX + event.values[1] * kickGain * 0.001f).coerceIn(-kickMax, kickMax)
                    kickY = (kickY - event.values[0] * kickGain * 0.001f).coerceIn(-kickMax, kickMax)
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
                    kickX *= kickFriction
                    kickY *= kickFriction
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

            val baseOffsetX = currentTiltX * bgMaxOffsetPx + kickX * 0.35f
            val baseOffsetY = -currentTiltY * bgMaxOffsetPx + kickY * 0.35f

            // --- Capa 1: fondo, casi quieto ---
            bgBitmap?.let { bmp ->
                val offsetX = baseOffsetX * bgMoveFactor
                val offsetY = baseOffsetY * bgMoveFactor

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

            // --- Capa 2: la calavera, con mucho más movimiento ("efecto pop") ---
            faceBitmap?.let { face ->
                val offsetX = baseOffsetX * faceMoveFactor
                val offsetY = baseOffsetY * faceMoveFactor
                canvas.drawBitmap(face, faceBaseX + offsetX, faceBaseY + offsetY, null)
            }

            // --- Capa 3: chispas/brasas, el mayor movimiento, en primer plano ---
            val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (s in sparks) {
                // deriva lenta hacia arriba (como una brasa real)
                s.y -= s.speedY
                if (s.y < -10f) {
                    s.y = height + 10f
                    s.x = Random.nextFloat() * width
                }

                val offsetX = (currentTiltX * sparkMaxOffsetPx + kickX) * s.depth * (sparkMoveFactor / 3.2f)
                val offsetY = (-currentTiltY * sparkMaxOffsetPx + kickY) * s.depth * (sparkMoveFactor / 3.2f)

                sparkPaint.color = if (s.warm) Color.parseColor("#FFA53E") else Color.parseColor("#FFF4D6")
                sparkPaint.alpha = s.alpha
                canvas.drawCircle(s.x + offsetX, s.y + offsetY, s.radius, sparkPaint)
            }
        }
    }
}
