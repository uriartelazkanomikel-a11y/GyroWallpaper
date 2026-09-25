# Gyro Wallpaper

Live wallpaper para Android: un campo de partículas con efecto parallax
que se mueve al inclinar el teléfono (usa el sensor `TYPE_ROTATION_VECTOR`,
con fallback a `TYPE_GYROSCOPE`).

## Qué contiene

- `app/src/main/java/com/mikel/gyrowallpaper/GyroWallpaperService.kt`
  Toda la lógica está aquí: generación de partículas, lectura del sensor,
  suavizado del movimiento y dibujado del frame. Es el único archivo que
  normalmente necesitas tocar.
- `app/src/main/res/xml/wallpaper.xml` — metadata obligatoria de Android
  para que el sistema reconozca la app como live wallpaper.
- `AndroidManifest.xml` — declara el `WallpaperService`.

## Opción A — Con Android Studio (la más cómoda)

1. Instala [Android Studio](https://developer.android.com/studio) si no lo tienes.
2. Abre esta carpeta como proyecto (`File > Open`).
3. Deja que Gradle sincronice (descargará dependencias automáticamente).
4. Conecta tu móvil por USB con la depuración USB activada, o usa un emulador.
5. Pulsa Run (▶). Esto instala la app `Gyro Wallpaper`.
6. En el móvil: mantén pulsado en la pantalla de inicio > Fondos de pantalla >
   busca "Gyro Wallpaper" en la lista de live wallpapers > Aplicar.

## Opción B — Sin instalar nada: compilar en la nube con GitHub Actions

Esta opción no requiere instalar Android Studio, SDK ni Gradle en tu ordenador.
Todo se compila en los servidores de GitHub y descargas el APK ya hecho.

1. Crea una cuenta gratuita en [github.com](https://github.com) si no tienes.
2. Crea un repositorio nuevo (puede ser privado) y sube el contenido de esta
   carpeta (`GyroWallpaper/`) — puedes arrastrar los archivos desde la web de
   GitHub ("Add file > Upload files") o con `git push` si usas git.
   El workflow ya incluido en `.github/workflows/build.yml` se encarga del resto.
3. En la pestaña **Actions** del repositorio, verás que el workflow "Build APK"
   se ejecuta automáticamente al subir los archivos (o pulsa "Run workflow"
   para lanzarlo a mano).
4. Espera unos 3-5 minutos a que termine (icono verde ✓).
5. Entra en esa ejecución y baja hasta **Artifacts** > descarga
   `GyroWallpaper-debug-apk` (es un .zip con el .apk dentro).
6. Pasa el .apk a tu móvil (por USB, Drive, Telegram a ti mismo, etc.) y
   ábrelo desde el móvil para instalarlo. Android pedirá permiso para
   "instalar apps de origen desconocido" la primera vez — actívalo solo
   para la app que uses para abrir el archivo (Archivos, Chrome, etc.).
7. En el móvil: mantén pulsado en la pantalla de inicio > Fondos de pantalla >
   "Gyro Wallpaper" > Aplicar.

## Opción C — Línea de comandos, sin Android Studio ni GitHub

Si prefieres compilarlo tú mismo en tu ordenador sin la interfaz de Android
Studio (más manual, útil si quieres editar el código con otro editor):

1. Instala un JDK 17 (por ejemplo `sdk install java 17-tem` con SDKMAN, o el
   paquete `openjdk-17-jdk` en Linux, o desde adoptium.net en Windows/Mac).
2. Instala Gradle: en Mac `brew install gradle`, en Linux tu gestor de
   paquetes o [SDKMAN](https://sdkman.io), en Windows `choco install gradle`.
3. Instala las "command line tools" de Android SDK (sin el IDE completo):
   descárgalas desde la sección "Command line tools only" en
   https://developer.android.com/studio y descomprímelas.
4. Con el SDK descomprimido, usa su `sdkmanager` para instalar lo necesario:
   ```
   sdkmanager --sdk_root=$HOME/android-sdk "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```
5. Crea un archivo `local.properties` en la raíz del proyecto (junto a
   `build.gradle`) con esta línea, apuntando a donde descomprimiste el SDK:
   ```
   sdk.dir=/ruta/a/android-sdk
   ```
6. Desde la carpeta del proyecto, compila:
   ```
   gradle assembleDebug
   ```
   El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.
7. Instálalo en el móvil conectado por USB (con depuración USB activada) con
   `adb` (viene en `platform-tools`):
   ```
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
   O simplemente copia ese .apk al móvil y ábrelo para instalarlo a mano.
8. En el móvil: mantén pulsado en la pantalla de inicio > Fondos de pantalla >
   "Gyro Wallpaper" > Aplicar.

**Recomendación:** si no programas habitualmente, la Opción B (GitHub Actions)
es la más sencilla porque no instalas nada — solo subes archivos y descargas
un .apk ya compilado.

## Cómo personalizarlo

Dentro de `GyroWallpaperService.kt`, en la sección `// ---------- CONFIG ----------`:

- `particleCount` — número de partículas (más = más denso, más batería).
- `maxTiltOffsetPx` — cuánto se desplazan las partículas al inclinar al máximo.
- `smoothing` — 0 a 1. Más bajo = movimiento más lento y suave; más alto = más reactivo.
- `backgroundColorTop` / `backgroundColorBottom` — colores del degradado de fondo.

Ideas para ampliar:
- Cambiar círculos por un logo/imagen de MAIK MT.
- Usar `Sensor.TYPE_ACCELEROMETER` en vez de rotación para un efecto distinto.
- Añadir una capa extra de "estrellas" más lentas para dar más profundidad.

## Nota sobre batería

Los live wallpapers con sensores activos consumen algo más de batería que un
fondo estático, porque el sensor y el dibujado (~60 fps) están activos mientras
la pantalla de inicio es visible. `onVisibilityChanged` ya se encarga de
desactivar el sensor y detener el dibujado cuando el wallpaper no está visible
(por ejemplo, con una app abierta a pantalla completa).
