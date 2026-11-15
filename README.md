# Forge3D Studio

Editor 3D nativo para **Android 10+ (API 29)** con **servidor MCP embebido** para agentes de IA.

Un agente se conecta por HTTP, lista las tools y controla la escena: crea geometría, aplica
materiales PBR, manipula mallas, mueve la cámara y exporta a glTF 2.0.

---

## Estado rápido

| | |
|---|---|
| **Endpoint MCP** | `http://127.0.0.1:8080/mcp` |
| **Token** | `Forge3D-studio` (estático, no rota) |
| **Protocolo** | MCP Streamable HTTP · JSON-RPC 2.0 · `2025-11-25` |
| **Tools** | 35 |
| **APK release** | ~700 KB, sin dependencias externas |
| **minSdk / targetSdk** | 23 / 35 |
| **Dependencias runtime** | ninguna (solo `kotlin-stdlib` y APIs del sistema) |

---

## Compilación

Requisitos: JDK 17 y Android SDK (platform 35, build-tools 35.0.0).

```bash
# 1. Wrapper incluido (Gradle 8.11.1)
./gradlew assembleRelease      # APK firmado en app/build/outputs/apk/release/
./gradlew assembleDebug        # APK de depuración
./gradlew testDebugUnitTest    # tests unitarios
```

La firma de release usa el keystore de `keystore/forge3d.jks` (contraseñas en
`app/build.gradle.kts`). **En un repositorio público, sustituye ese keystore por uno propio**
o mueve las credenciales a variables de entorno.

### Build en CI

`.github/workflows/build-apk.yml` compila debug y release en cada push, corre los tests unitarios
y publica ambos APK como artefactos. Además verifica la firma y el `minSdk`/`targetSdk` del APK
generado.

---

## Instalación

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

O copia el APK al teléfono y ábrelo (hay que permitir "instalar apps de orígenes desconocidos").

---

## Uso desde un agente (OpenCode, Claude, Termux, Python...)

```json
{
  "mcpServers": {
    "forge3d": {
      "url": "http://127.0.0.1:8080/mcp",
      "headers": { "Authorization": "Bearer Forge3D-studio" }
    }
  }
}
```

Comprobación:

```bash
curl -s -H "Authorization: Bearer Forge3D-studio" \
  -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:8080/mcp \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Detalles completos en [`docs/INTEGRACION_MCP.md`](docs/INTEGRACION_MCP.md).

### Tools disponibles

| Grupo | Tools |
|---|---|
| Escena | `info_servidor` `listar_escena` `objeto_info` `vaciar_escena` |
| Primitivas | `crear_cubo` `crear_esfera` `crear_cilindro` `crear_plano` `crear_primitiva` |
| Transformación | `transformar_objeto` `duplicar_objeto` `eliminar_objeto` `seleccionar_objeto` `deseleccionar_todo` |
| Malla | `malla_weld` `malla_subdividir` `malla_extruir` `malla_bisel` `malla_normales` `malla_estadisticas` `malla_uv` |
| Materiales | `aplicar_material` `aplicar_textura` |
| Cámara | `camara_estado` `camara_orbitar` `camara_desplazar` `camara_zoom` `camara_encuadrar` |
| Proyectos | `listar_proyectos` `guardar_proyecto` `cargar_proyecto` |
| IO | `exportar_gltf` `importar_modelo` |
| Seguridad | `token_generar` `token_revocar` |
| Diagnóstico | `info_memoria` |

---

## Estabilidad y memoria

La app está diseñada para no morir bajo presión de memoria:

- **Presupuesto explícito**: 55% del límite de heap, 25% para texturas.
- **`onTrimMemory` / `onLowMemory`**: liberan cachés, bajan la resolución de render y purgan mallas.
- **Pooling** de buffers de floats para no fragmentar el heap.
- **Guardas de OOM**: `OutOfMemoryError` y errores de GPU degradan la calidad en vez de cerrar la app.
- **Pérdida de contexto GL**: los VBOs se invalidan y se reconstruyen solos.
- **Calidad adaptativa** según RAM, GPU y formato de compresión soportado (ASTC/ETC2/S3TC).

Detalle en [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md).

---

## Estructura

```
app/src/main/java/com/forge3d/studio/
├── MainActivity.kt          UI (Views) + onTrimMemory
├── ViewportView.kt          Superficie GLES2 táctil
├── engine/                  Núcleo del motor (Kotlin puro, testeable en JVM)
│   ├── GLRenderer.kt        Shaders PBR, VBOs, pérdida de contexto
│   ├── Scene.kt             Objetos, límites, refcount
│   ├── Mesh.kt              Geometría + AABB + UVs
│   ├── MeshOps.kt           weld / subdividir / extruir / bisel / normales / UVs
│   ├── Animation.kt         Huesos, clips, skinning LBS
│   ├── Mat4.kt              Matrices 4x4 propias (sin android.opengl.Matrix)
│   ├── Material.kt          Material PBR
│   ├── TextureSet.kt        Texturas y presupuesto de VRAM
│   ├── Camera3D.kt          Cámara orbital
│   ├── DeviceCapabilities.kt  Perfil del dispositivo
│   ├── MemoryManager.kt     Trim, pools, guardas de OOM
│   ├── Math3D.kt  Primitives.kt
├── io/                      OBJ/STL/3MF/FBX/glTF, proyectos .m3d.json
└── mcp/                     McpServer, McpService, McpTools, McpClient, AuthManager
```

---

## Pruebas

```bash
./gradlew testDebugUnitTest
```

Tests unitarios sobre la lógica pura del motor (malla y animación). El núcleo `engine/` no
depende de Android, así que corre en la JVM sin emulador.

---

## Licencia

MIT. Ver [`LICENSE`](LICENSE).
