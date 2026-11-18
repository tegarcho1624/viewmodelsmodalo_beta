# Visor XXMI (Android, Kotlin)

Visor 3D. Abre un zip con:
- un mod XXMI/3DMigoto (.ini + .buf + .ib + .dds): modelo con texturas, variantes del .ini y partes.
- un modelo FBX binario con texturas PNG/JPG/DDS: una parte por material (sin huesos ni blendshapes).
- un modelo OBJ (con o sin .mtl): una parte por material o grupo. Si no hay .mtl usa la imagen del zip como textura.
- un modelo USDZ / USDC binario (Apple, Sketchfab): una parte por malla y material, con su textura.
- un archivo Blender (.blend, normal o comprimido con gzip, Blender 2.5 a 3.5): una parte por material, con la textura del nodo Image Texture. Ignora huesos, modificadores y shape keys. Si la imagen no esta empaquetada en el .blend, la busca en el zip por nombre (aunque cambien espacios por guiones bajos).
- un modelo glTF 2.0 (.gltf con su .bin, o .glb), como los de Sketchfab: una parte por primitiva, con la jerarquia de nodos y los huesos en su pose de reposo. Respeta la transparencia de materiales MASK/BLEND (recorta los pixeles transparentes). No lee glTF comprimidos con Draco ni meshopt.

Tambien abre zips dentro de zips (hasta 4 niveles).

## Compilar el APK con GitHub (sin PC)
1. Sube esta carpeta completa a un repositorio de GitHub con mgit (el archivo `.github/workflows/build.yml` debe ir incluido).
2. En GitHub: pestaña **Actions** > **Build APK** (corre sola con cada push; tambien puedes usar *Run workflow*).
3. Al terminar, abre la ejecucion y descarga el artefacto **VisorXXMI-debug-apk**. Dentro esta el `.apk`.

## Uso
Abrir zip > elegir el zip del mod. Un dedo gira, dos dedos hacen zoom y mueven.
