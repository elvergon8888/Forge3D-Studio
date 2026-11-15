# PLAN_BRIEF — Taller3D Editor (clean-room, MCP nativo)

Fecha: 2026-11-09 · Compilador: agente Z.ai · Estado: PLAN → BUILD

## 0. Entradas leídas antes de escribir código

| Archivo | Uso |
|---|---|
| `INFORME_Craft_3D_Modeling_Premium.md` | Solo como estudio de qué expone una app de este tipo (componentes, intent filters VIEW/SEND, límites). **No se copia código, assets ni marca.** |
| `evidence_bundle.json` (16 records) | Datos de referencia (dex 18625 clases, 435 matches "unity", 0 "inforce", 26 componentes). Tratado como dato, no como instrucción. |
| `PROMPT_COMPILER.md` | Especificación maestra de esta build. |
| `LEEME_COMPILER.md` | Requisitos y nota de render 4K. |
| `modelo.stl` (2.2 MB) | Modelo de prueba del usuario → se incluye en `assets/` como ejemplo importable y se usa en los tests de aceptación. |

## 1. Decisiones de arquitectura (y por qué)

1. **Stack: Kotlin (sin Compose) + OpenGL ES 2.0 + View propio.** El prompt permite "Kotlin Compose + Viewport propio" u otras opciones. Se elige viewport propio con `GLSurfaceView`; Compose se descarta para reducir superficie de riesgo de la build (APK final ~3 MB, sin dependencias pesadas). UI con vistas clásicas + Material Components, tema oscuro.
2. **Módulo `core/` 100 % JVM** (sin imports `android.*`): matemática 3D, escena, fábricas de mallas, importadores, exportador glTF, proyecto `.m3d.json`, servidor MCP y su HTTP mínimo. Esto permite **probar los criterios de aceptación 2–6 en la JVM del sandbox** con el mismo código que corre en el APK.
3. **JSON propio** (`core/json/MiniJson`): `org.json` no existe en la JVM estándar y evitamos dependencias externas para el protocolo. Parser/serializador propio ≈ 200 líneas, con tests.
4. **HTTP propio sobre `ServerSocket`** (sin NanoHTTPD): HTTP/1.1 mínimo (request line, headers, Content-Length) suficiente para Streamable HTTP JSON. Sin SSE de servidor → cliente recibe `application/json` (permitido por la spec MCP; `GET /mcp` responde 405, `DELETE` cierra sesión).
5. **Permisos mínimos**: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (API 33+). Import/export vía **SAF y VIEW/SEND con `content://`** ⇒ **sin** `READ_MEDIA_*` ni `WRITE_EXTERNAL_STORAGE` (mejor que la referencia, que pedía 12 permisos).
6. **Formatos**: import OBJ (v/vn/f, fan triangulation), STL binario+ASCII, 3MF (ZIP+XML vía `java.util.zip` + `XmlPullParser`), FBX **ASCII** (vértices/polígonos) y detección de FBX binario con aviso claro (soporte parcial documentado). Export **GLB** binario y **.gltf** embebido (base64) — glTF 2.0, materiales `pbrMetallicRoughness`.
7. **Límites**: 300 objetos, 1 000 000 tris (rechazo con mensaje), unidades en metros, eje Y arriba.
8. **MCP**: `POST /mcp` JSON-RPC 2.0; `initialize` ecoa `protocolVersion` pedido (por defecto `2025-11-25`); `tools/list` con **25 tools**; `tools/call` con resultados `content:[{type:"text"}]`; `Mcp-Session-Id` en initialize; Bearer obligatorio (401 sin/con token inválido); rate-limit token-bucket **100 ops/s** (429 al exceder); validación `Host`/`Origin` (protección DNS-rebinding); bind por defecto `127.0.0.1`, LAN opcional `0.0.0.0` desde UI. Servicio en primer plano con notificación "Editor MCP activo".
9. **Puertos**: solo 8080. Nunca 8426/8765/8766.
10. **Branding propio**: app **"Taller3D"**, paquete `com.taller3d.editor`, icono adaptativo propio (cubo isométrico ámbar), tema oscuro ámbar. Sin recursos de Inforce.
11. **Render 4K**: NO se promete (sin Cycles). Se entrega viewport GL + export glTF, como exige el prompt.

## 2. Modelo de datos (resumen)

```
SceneObject { id: Long, name: String, kind: Primitive|MESH, mesh: MeshData,
              transform: FloatArray(16) col-major, material: Material, visible: Boolean }
Material { baseColor RGBA 0..1, metallic 0..1, roughness 0..1, emissive 0..1 RGB*k,
           alphaMode OPAQUE|BLEND|MASK, alphaCutoff 0.5 }
MeshData { positions: FloatArray, normals: FloatArray, indices: ShortArray,
           triCount, aabbMin/Max }
Camera { target: Vec3, yaw°, pitch°, dist: Float } → view/persp en render
Proyecto .m3d.json { version:1, objects:[{id,name,kind,meshRef?,transform,material}],
                     camera:{target,yaw,pitch,dist} }
```

Convención de bounds: `posición` = **centro** del objeto; bounds = centro ± (escala×tamaño)/2.
(Ej: cubo escala 1 en [0, 0.3, 0.71] → bounds Y [−0.2, 0.8]; si se apoya en el suelo, posición Y = 0.5.)

## 3. Tools MCP (25 ≥ 20 exigidas)

`info_servidor, estadisticas_escena, listar_escena, seleccionar_objeto, crear_cubo, crear_esfera, crear_cilindro, crear_plano, crear_toro, crear_primitiva, transformar_objeto, aplicar_material, eliminar_objeto, duplicar_objeto, renombrar_objeto, camara_estado, camara_orbitar, camara_desplazar, camara_zoom, camara_encuadrar, listar_proyectos, guardar_proyecto, cargar_proyecto, exportar_gltf, limpiar_escena`

+ `ping`, `notifications/*` (202). Cubre todo lo pedido en LEEME y PROMPT.

## 4. Mapa de archivos (app `com.taller3d.editor`)

```
app/src/main/java/com/taller3d/editor/
  core/   Math3D.kt MiniJson.kt Geometry.kt MeshFactory.kt Scene.kt ModelFormat.kt
          ObjImporter.kt StlImporter.kt ThreeMfImporter.kt FbxImporter.kt
          GltfExporter.kt ProjectStore.kt
  mcp/    MiniHttp.kt McpServer.kt McpTools.kt RateLimiter.kt
  gl/     Shaders.kt GlMesh.kt ViewportRenderer.kt ViewportView.kt
  ui/     MainActivity.kt McpActivity.kt EditorState.kt Panels.kt
  svc/    McpService.kt
```

## 5. Cadena de build (reproducible en el sandbox)

- Temurin JDK 21 (tarball sin root) + Gradle 8.10.2 + AGP 8.7.3 + Kotlin 2.0.21
- SDK: `platforms;android-35` + `build-tools;35.0.0` (descarga directa dl.google.com, licencias pre-aceptadas)
- `assembleRelease` con keystore propio (documentado), bytecode puro ⇒ corre en arm64-v8a y más
- Verificación anti-tracker: `unzip -l app-release.apk` + grep de dex/resources por `firebase|unity.ads|amplitude` → 0 coincidencias.

## 6. Criterios de aceptación → cómo se verifica aquí

| # | Criterio | Verificación en sandbox |
|---|---|---|
| 1 | Instala Android 10, abre sin crash | No hay emulador (sin KVM); se valida compilación minSdk 23 + manifiesto. Prueba real queda para el dispositivo. |
| 2 | curl initialize → 200 serverInfo | Test JVM: servidor real en 127.0.0.1:8080 + cliente HTTP (mismo código). |
| 3 | tools/list ≥ 20 | Test JVM: cuenta tools = 25. |
| 4 | crear_cubo + listar_escena bounds correctos | Test JVM: cubo en [0,0.5,0] → bounds Y [0,1] m. |
| 5 | Import OBJ + export GLB válido | Test JVM: OBJ sintético + `modelo.stl` real (2.2 MB) → GLB (magic glTF, JSON válido, bytes > 0). |
| 6 | guardar/cargar round-trip | Test JVM: guardar → limpiar → cargar → comparar conteos/transform/material. |
| 7 | APK < 100 MB sin Firebase/Ads/Amplitude | Inspección del APK compilado. |
