package com.mikel.gyrowallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Esta actividad no tiene interfaz propia: solo abre directamente la
 * pantalla del sistema para aplicar "Gyro Wallpaper" como fondo de
 * pantalla en vivo, sin tener que buscarlo a mano en Ajustes.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, GyroWallpaperService::class.java)
            )
            startActivity(intent)
        } catch (e: Exception) {
            try {
                // Alternativa si el fabricante bloquea el intent directo:
                // abre el selector general de fondos animados del sistema.
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: Exception) {
                Toast.makeText(
                    this,
                    "Ve a Ajustes > Fondos de pantalla > Fondos animados > Gyro Wallpaper",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        finish()
    }
}
