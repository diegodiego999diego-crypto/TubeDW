# TubeDW

Cliente para YouTube hecho en Kotlin, con interfaz blanca y roja (tono YouTube).

## Características (v1)

- Feed con videos en tendencia y recomendaciones según lo que has visto.
- Búsqueda de videos.
- Reproducción con ExoPlayer (Media3) y extracción de streams con NewPipe Extractor.
- Reproducción limpia: al tocar un video se abre la pantalla de reproducción; se puede deslizar hacia abajo para minimizar a un reproductor pequeño (mini player) y volver al feed.
- Botones de **Me gusta** (guardado localmente), **Canal** y **Descargar** (próximamente).
- Canales y "me gusta" guardados en una base de datos local (SQLite) en el almacenamiento privado de la app.
- Sin anuncios. Material Design 3. Sin modo oscuro.

## Compilación

El proyecto se compila automáticamente con **GitHub Actions**:

1. Sube cambios a la rama `main`.
2. Abre la pestaña **Actions** y espera el resultado.
3. Descarga el APK desde el artefacto **`TubeDW-debug`**.

> El APK se firma con la clave de depuración (firma temporal), por lo que al actualizar una versión nueva es necesario desinstalar la anterior.

También puedes compilar localmente con Android Studio (JDK 17).

## Notas técnicas

- `minSdk 24`, `targetSdk 35`, `compileSdk 36`.
- Dependencias: NewPipe Extractor (JitPack), Media3/ExoPlayer, Material 3, Coil.
- La extracción usa `NewPipe Extractor` solo para obtener los streams; la reproducción la hace ExoPlayer.
