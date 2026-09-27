# 📄 UltraLight PDF Viewer for Android

[![Build Android APK](https://github.com/krthonovix/Pdf-viewer/actions/workflows/build-apk.yml/badge.svg)](https://github.com/krthonovix/Pdf-viewer/actions/workflows/build-apk.yml)
![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![APK Size](https://img.shields.io/badge/APK%20Size-~1%20MB-2563EB)

Un visor de documentos PDF nativo para Android diseñado bajo una premisa estricta: **ser lo más liviano, rápido y eficiente en consumo de memoria RAM posible**.

A diferencia de otros visores que empaquetan motores C++ duplicados de 15–30 MB o frameworks pesados de interfaz, **UltraLight PDF Viewer** utiliza directamente el motor nativo del sistema Android (`android.graphics.pdf.PdfRenderer`), vistas personalizadas de dibujo directo en `Canvas` y reciclaje in-situ de buffers de memoria.

---

## ✨ Características Principales

- 🚀 **Peso Ultra-Reducido (~1 MB APK)**: Compilado con R8 en modo completo (`fullMode`), reducción de recursos y cero librerías pesadas de interfaz.
- 🧠 **Consumo de Memoria O(1) con `BitmapPool`**: Reutiliza buffers `ARGB_8888` existentes al desplazarse entre páginas, evitando ejecuciones del *Garbage Collector* (GC) y tirones de interfaz (*jank*).
- 🔍 **Zoom Nítido sin Desbordar la RAM (*Viewport Patch Rendering*)**: Soporta *pinch-to-zoom* (`1.0x` a `5.0x`) y doble toque (`2.5x`). Al hacer zoom, renderiza en alta resolución **únicamente el recorte visible en pantalla** mediante transformación matricial (`Matrix`), manteniendo el uso de RAM constante.
- ⚡ **Apertura Instantánea *Zero-Copy***: Abre descriptores de archivo (`ParcelFileDescriptor`) directamente con verificación `lseek` sin copiar el PDF al almacenamiento interno.
- 🌙 **Modo Noche Acelerado por GPU**: Inversión inteligente de contraste mediante `ColorMatrixColorFilter` en el `Canvas` con **0 bytes adicionales de RAM**.
- ↕️ **Doble Modo de Lectura**: Alterna al instante entre desplazamiento vertical continuo y paginación horizontal tipo libro (`PagerSnapHelper`).
- 📱 **Interfaz Flotante Adaptada a *Edge-to-Edge***: Controles tipo cápsula que respetan automáticamente el *notch*/cámara frontal (`DisplayCutout`), barra de estado y barra de navegación por gestos en Android 8.0 hasta Android 15+.
- 💾 **Persistencia Ligera**: Recuerda automáticamente el último documento abierto, la página exacta donde te quedaste, el modo nocturno y la orientación de lectura.

---

## 🏗️ Arquitectura del Proyecto

```text
app/src/main/java/com/ultralight/pdfviewer/
├── MainActivity.kt                  # Actividad principal ligera (sin sobrecarga de AppCompat)
├── engine/
│   ├── PdfDocumentEngine.kt         # Motor serializado sobre PdfRenderer (Single-Thread + Mutex)
│   └── ViewportMatrixCalculator.kt  # Empaquetado binario LongArray y cálculo de Matrix para Zoom
├── memory/
│   ├── BitmapPool.kt                # Pool de reutilización in-situ de Bitmaps ARGB_8888
│   └── PageBitmapCache.kt           # LruCache dinámica (1/8 Heap) con ciclo de vida pin/unpin
├── storage/
│   ├── FileDescriptorResolver.kt    # Resolución Zero-Copy de URIs content:// y file://
│   └── ReadingStateStore.kt         # Persistencia clave-valor en SharedPreferences
└── ui/
    ├── PdfPageAdapter.kt            # Adaptador con cancelación reactiva de corrutinas en scroll rápido
    ├── PdfPageView.kt               # Custom View con dibujo directo en Canvas y filtro nocturno GPU
    └── ZoomableRecyclerView.kt      # Gestos multitáctiles (Pinch, Double-Tap, Pan 2D y Debounce 80ms)
```

Para más detalles sobre las decisiones de bajo nivel y presupuesto de memoria, consulta el [Plan de Implementación](IMPLEMENTATION_PLAN.md).

---

## 📲 Instalación (Descargar APK)

1. Ve a la pestaña [**Actions**](https://github.com/krthonovix/Pdf-viewer/actions) de este repositorio.
2. Selecciona la ejecución más reciente del flujo **Build Android APK**.
3. En la sección inferior **Artifacts**, descarga **`UltraLightPdfViewer-release-apk`**.
4. Extrae el archivo `app-release.apk` e instálalo en cualquier dispositivo con **Android 8.0 (API 26)** o superior.

---

## 🛠️ Compilación desde el Código Fuente

### Requisitos
- **JDK 17** o superior.
- **Android SDK** (`compileSdk = 35`) o compilación automática mediante **GitHub Actions**.

### Comandos Gradle
```bash
# Ejecutar pruebas unitarias (empaquetado binario y matemáticas de transformación de zoom)
gradle testDebugUnitTest

# Generar APK de lanzamiento firmado y optimizado con R8
gradle assembleRelease
```

El APK resultante se generará en:
```text
app/build/outputs/apk/release/app-release.apk
```

---

## 📄 Licencia

Este proyecto es de código abierto y está disponible para uso personal y educativo.
