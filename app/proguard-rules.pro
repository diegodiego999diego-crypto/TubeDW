# Reglas de NewPipeExtractor (necesarias si en el futuro se activa R8/minify)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
