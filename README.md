# Visor XXMI (Android, Kotlin)

Visor 3D. Abre un zip con:
- un mod XXMI/3DMigoto (.ini + .buf + .ib + .dds): modelo con texturas, variantes del .ini y partes.
- un modelo FBX binario con texturas PNG/JPG/DDS: una parte por material (sin huesos ni blendshapes).
- un modelo OBJ (con o sin .mtl): una parte por material o grupo. Si no hay .mtl usa la imagen del zip como textura.

Tambien abre zips dentro de zips (hasta 4 niveles).

## Compilar el APK con GitHub (sin PC)
1. Sube esta carpeta completa a un repositorio de GitHub con mgit (el archivo `.github/workflows/build.yml` debe ir incluido).
2. En GitHub: pestaña **Actions** > **Build APK** (corre sola con cada push; tambien puedes usar *Run workflow*).
3. Al terminar, abre la ejecucion y descarga el artefacto **VisorXXMI-debug-apk**. Dentro esta el `.apk`.

## Uso
Abrir zip > elegir el zip del mod. Un dedo gira, dos dedos hacen zoom y mueven.
