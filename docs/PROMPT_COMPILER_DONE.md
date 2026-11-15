# PROMPT COMPILER — ESTADO: ✅ DONE (2026-11-09, entrega real)

Marca de cumplimiento del prompt `PROMPT_COMPILER.md` para la entrega verificada:
**Forge3D Studio 1.0.0** — `app-release.apk` (692 KB, firma v1+v2, verificado con apksigner).

> Nota de transparencia: existe un borrador anterior ("Taller3D") sin APK compilado en
> `previo_borrador_Taller3D/`. Esta entrega reemplaza ese borrador con un build real.
> Detalles técnicos completos en `INFORME_BUILD.md`.

## Checklist del prompt

| # | Requisito | Estado |
|---|---|---|
| 1 | App Android original, instalable Android 10, **minSdk 23 / targetSdk 35** | ✅ com.forge3d.studio · aapt2: minSdk 23, targetSdk 35 · Kotlin + viewport GLES20 propio |
| 2 | Viewport, órbita/pinch, cubo/esfera/cilindro/plano, transformar (m, Y arriba), 300 obj, 1M tris | ✅ ViewportView + GLRenderer + Primitives + Scene (límites validados en UI y MCP) |
| 3 | Import VIEW/SEND .obj/.fbx/.stl/.3mf | ✅ ACTION_VIEW/SEND en manifest + ACTION_OPEN_DOCUMENT; parsers OBJ/STL(bin+ascii)/3MF/FBX(bin 7.x zlib + ascii) originales |
| 4 | Export .glb/.gltf + proyecto .m3d.json | ✅ GltfExporter (GLB 2.0 JSON+BIN con PBR) + ProjectStore (.m3d.json + mallas .f3dm, round-trip) |
| 5 | Materiales PBR (baseColor 0-1, metallic, roughness, emissive, alphaMode OPAQUE/BLEND/MASK) | ✅ Material + shader con alpha/mask/blend |
| 6 | Servidor MCP Streamable HTTP 127.0.0.1:8080/mcp, Bearer revocable desde UI | ✅ McpServer propio (JSON-RPC 2.0, initialize con serverInfo + Mcp-Session-Id, 401 con WWW-Authenticate, Origin/Host validados, rate-limit 100 ops/s) + AuthManager + foreground service "Editor MCP activo" |
| 7 | Tools ≥ 20 (editor + cámara + proyecto) | ✅ **27 tools**: info_servidor, listar_escena, crear_cubo/esfera/cilindro/plano, crear_primitiva, eliminar/duplicar/transformar/objeto_info, seleccionar/deseleccionar, aplicar_material, camara_estado/orbitar/desplazar/zoom/encuadrar, listar/guardar/cargar_proyecto, exportar_gltf, importar_modelo, vaciar_escena, token_generar/revocar |
| 8 | Sin ads ni trackers (grep del base.apk) | ✅ `strings classes.dex` → 0 coincidencias firebase/amplitude/unity-ads; solo 5 permisos declarados; 0 dependencias externas |
| 9 | Branding propio (nombre, icono, package) | ✅ Forge3D Studio · com.forge3d.studio · icono e identidad originales |
| 10 | APK < 100 MB | ✅ 692 KB |
| 11 | Entrega en `Download/Craft_Proyecto_3D_Editor/BUILD/` con apk + informe + prompt done | ✅ esta carpeta |

## Cómo probar (desde Termux/OpenCode en el teléfono)

1. Instalar `app-release.apk` (Android 10+).
2. Abrir Forge3D Studio → botón **MCP** → **Generar** → **Iniciar servidor**.
3. `curl -s -i -H "Authorization: Bearer <token-ui>" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -X POST http://127.0.0.1:8080/mcp -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'` → **200 con serverInfo**.
4. `tools/list` → 27 tools · `tools/call` → `crear_cubo` + `listar_escena` (bounds en metros) · `guardar_proyecto`/`cargar_proyecto` · `exportar_gltf` → .glb > 0 bytes.
5. Importar el `modelo.stl` incluido en el ZIP original por **VIEW/SEND** (abrir con Forge3D Studio) o botón **Importar**.

## Límites honestos

- Viewport GLES20 + export glTF (sin render 4K Cycles/Arnold/Unreal, como exige el prompt).
- FBX: solo geometría poligonal; sin UVs/materiales/huesos/animaciones.
- Puertos 8426/8765/8766 no tocados; solo 8080.
- `Silla_Madera_Roble` / `Motocicleta_Deportiva_1000` (ModelerStudio, caído): re-exportar a OBJ/STL/3MF y abrir aquí.
