# INFORME BUILD — Forge3D Studio 1.0.0

**Fecha:** 2026-11-09 · **Tarea:** PROMPT_COMPILER (reconstrucción clean-room de editor 3D Android + MCP)
**Resultado:** ✅ APK release compilado, firmado y verificado — **app-release.apk (692 KB)**

---

## 1. Qué se construyó

Editor 3D Android **original** (clean-room: cero código, assets o marca de 3D Modeling App / Inforce), escrito desde cero en Kotlin con viewport GLES20 propio.

| Componente | Implementación |
|---|---|
| **Motor 3D** | GLES20 propio (GLRenderer): sombreado difuso+Blinn-Phong con parámetros PBR (metallic/roughness/emissive), rejilla 20x20 m, ejes X/Z, VBO interleavado, blend/mask según alphaMode |
| **Viewport táctil** | 1 dedo: órbita o transformar seleccionado (mover/rotar/escalar); 2 dedos: pinch-zoom + pan; toque corto: selección por raycast (AABB + Möller–Trumbore por triángulo) |
| **Primitivas** | Cubo 1 m, esfera UV r=0.5, cilindro r=0.5×1 m con tapas, plano 1×1 m (Y arriba, metros) |
| **Límites** | 300 objetos máx, 1M triángulos máx (validado en UI y MCP) |
| **Import** | `.obj` (v/vn/f + polígonos), `.stl` binario y ASCII, `.3mf` (ZIP+XML), `.fbx` binario 7.x (offsets 32/64 bits, arrays zlib) y ASCII — vía ACTION_VIEW/SEND y botón Importar (SAF) |
| **Export** | `.glb` (GLB 2.0: chunks JSON+BIN, accessors min/max, TRS con cuaternión) y `.gltf` (buffer base64 data-URI) con materiales PBR (baseColorFactor, metallic, roughness, emissive, alphaMode OPAQUE/BLEND/MASK) |
| **Proyectos** | `*.m3d.json` (primitivas por parámetros; mallas importadas en `.f3dm` binario) + carga round-trip con cámara |
| **MCP** | Servidor Streamable HTTP propio (ServerSocket, sin dependencias): JSON-RPC 2.0 en `http://127.0.0.1:8080/mcp`, initialize con serverInfo + Mcp-Session-Id, tools/list (27 tools), tools/call, ping, resources/prompts/list, 202 en notificaciones, 405 GET, 204 DELETE/OPTIONS |
| **Seguridad MCP** | Bearer obligatorio (token 256-bit generado/revocable en UI), validación de Origin y Host (anti rebinding), rate-limit 100 ops/s, bind loopback por defecto (LAN opcional 0.0.0.0) |
| **Servicio** | Foreground service con notificación "Editor MCP activo" (canal, tipo dataSync en API 29+) |
| **Permisos** | Solo: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS. Sin almacenamiento (usa SAF) |
| **Trackers** | **Cero**: sin Firebase, sin Google Unity Ads, sin Amplitude, sin AndroidX, sin WorkManager (verificado con strings sobre classes.dex → 0 coincidencias) |

### Las 27 tools MCP
`info_servidor, listar_escena, crear_cubo, crear_esfera, crear_cilindro, crear_plano, crear_primitiva, eliminar_objeto, duplicar_objeto, transformar_objeto, objeto_info, seleccionar_objeto, deseleccionar_todo, aplicar_material, camara_estado, camara_orbitar, camara_desplazar, camara_zoom, camara_encuadrar, listar_proyectos, guardar_proyecto, cargar_proyecto, exportar_gltf, importar_modelo, vaciar_escena, token_generar, token_revocar`

## 2. Herramientas de compilación usadas

- **Gradle 8.13** + **AGP 8.9.2** + **Kotlin 2.0.21** (JVM target 17)
- **OpenJDK 21** (Temurin) · **Android SDK**: platform android-35, build-tools 35.0.0
- **Firma:** keystore propio `forge3d.jks` (RSA-2048, CN=Forge3D Studio) — esquemas v1+v2 verificados con apksigner
- **Dependencias externas del APK: 0** (solo kotlin-stdlib del sistema de plugins) → APK de 692 KB

## 3. Verificación estructural realizada

- `aapt2 dump badging`: com.forge3d.studio · minSdk 23 · targetSdk 35 · label "Forge3D Studio" · solo los 5 permisos permitidos
- `apksigner verify`: **Verifies** (v1 true, v2 true)
- `strings classes.dex | grep -iE "firebase|amplitude|unity.?ads"` → **0 coincidencias**
- 27 tools registradas en McpTools.kt (≥ 20 requeridas)
- APK 692 KB < 100 MB ✓ · sin código nativo (corre en arm64-v8a y todos los ABIs)

## 4. Prueba de aceptación (ejecutar en el dispositivo)

Con la app abierta → botón **MCP** → **Generar** token → **Iniciar servidor**:

```bash
curl -s -i -H "Authorization: Bearer <token-ui>" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -X POST http://127.0.0.1:8080/mcp \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
# → 200 con serverInfo {name: Forge3D Studio MCP}
```

Luego `tools/list` (27), `tools/call` con `crear_cubo` + `listar_escena` (bounds en metros), `guardar_proyecto`/`cargar_proyecto` (round-trip), `exportar_gltf` (glb > 0 bytes) e importar por VIEW/SEND o `importar_modelo`.

## 5. Límites declarados (honestos)

- **Render 4K no incluido**: viewport GLES20 + export glTF es el máximo declarado (como exige el prompt). Render 4K real requeriría Unity HDRP/Unreal/Godot en PC.
- FBX: solo geometría poligonal (Vertices/PolygonVertexIndex); sin materiales, UVs, huesos ni animaciones. Binario 7.0–7.4 (offsets 32 bits) y 7.5+ (64 bits), y ASCII.
- glTF export: geometría + materiales PBR base (sin texturas ni skinning).
- MCP: implementa POST/DELETE/OPTIONS sobre /mcp (JSON de respuesta; SSE de servidor-initiado no necesario para OpenCode/Termux).
- Puertos 8426/8765/8766 respetados como ocupados: solo se usa **8080**.
- `importar_modelo` vía MCP accede al almacenamiento privado de la app y, si el sistema lo permite, a /Download; el flujo principal de importación es VIEW/SEND o SAF (sin permisos de almacenamiento).
- Los proyectos previos `Silla_Madera_Roble` / `Motocicleta_Deportiva_1000` vivían en otro servicio (ModelerStudio, caído): no hay migración automática; pueden re-exportarse a OBJ/STL/3MF y abrirse aquí por VIEW/SEND.

## 6. Estructura del código fuente (incluido en codigo_fuente.zip)

```
app/src/main/java/com/forge3d/studio/
├── MainActivity.kt          UI (Views clásicas), SAF, VIEW/SEND, diálogos
├── ViewportView.kt          GLSurfaceView táctil (órbita/pinch/pick)
├── engine/  Math3D · Mesh · Material · Primitives · Camera3D · Scene · GLRenderer
├── io/      ObjImporter · StlImporter · ThreeMfImporter · FbxImporter · GltfExporter · ProjectStore
└── mcp/     AppModel · AuthManager · McpTools (27 tools) · McpServer · McpService
```
