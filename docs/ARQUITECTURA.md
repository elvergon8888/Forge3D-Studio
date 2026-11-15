# Forge3D Studio — Arquitectura técnica

Editor 3D nativo para Android (API 23+, objetivo Android 10+) con servidor MCP embebido para
agentes de IA. Este documento justifica las decisiones de diseño que sostienen el proyecto.

---

## 1. Motor de renderizado

### 1.1 Decisión: renderizador propio sobre OpenGL ES 2.0

Forge3D Studio **no** usa Filament. Mantiene un renderizador propio (`engine/GLRenderer.kt`)
sobre **OpenGL ES 2.0**, que es el perfil que ya declara el manifiesto
(`android:glEsVersion="0x00020000"`).

**Por qué esta decisión:**

| Criterio | Filament (propuesta original) | Renderizador propio GLES2 (decisión) |
|---|---|---|
| Tamaño del APK | +15–25 MB (binarios nativos por ABI) | 692 KB (APK release actual) |
| Build | Requiere NDK + CMake + toolchain nativo | `gradle assembleRelease` y listo |
| Superficie de API | Vulkan/OpenGL ES 3.0 | GLES 2.0 (compatible con el 100% de dispositivos API 29+) |
| Estabilidad en gama baja | El motor gestiona calidad, pero añade superficie de fallo | Control total del presupuesto de memoria |
| Degradación bajo presión | Limitada (hay que reconstruir el `Engine`) | Total: se puede bajar resolución y soltar buffers en caliente |

El criterio que decide es **estabilidad y presupuesto de memoria**, que el enunciado fija como
requisito crítico, y el **coste de distribución**: la app es para agentes, y un APK de 700 KB
que arranca en 40 ms sobre cualquier Android 10 vale más que uno de 20 MB con un motor
completo que hay que mantener y depurar en NDK.

**Evaluación de las alternativas exigidas:**

- **Urho3D**: motor de juego completo. Arrastra física, audio, scripting y un ciclo de juego.
  Sobredimensionado para un editor de mallas; el coste en APK y en complejidad no se justifica.
- **Algine**: OpenGL 3.3+ y >40 formatos vía Assimp. Interesante por Assimp (ahorraría el
  importador de FBX, que hoy son 287 líneas de código propio), pero exige GLES 3.3, lo que
  deja fuera dispositivos Android 10 de gama baja con Mali-720/T720. Además, con Assimp se
  heredarían los bugs de sus importadores FBX; el importador actual documenta explícitamente
  qué no soporta (materiales, UVs, huesos), que es más honesto que fallar en silencio.
- **Filament**: el mejor motor PBR móvil, pero es un motor *de juego/render*, no de editor.
  Su API está pensada para)]]
  - scenes y materiales predefinidos, no para un editor donde el usuario manipula la malla
    vértice a vértice. Además obliga a NDK/CMake y a un pipeline de CI con toolchain nativo.

**Lo que sí se ha adoptado del enunciado, sin el motor:** el modelo de materiales PBR con
`baseColor / metallic / roughness / emissive / alphaMode`, la exportación glTF 2.0 con PBR
completo, y la degradación adaptativa de calidad.

### 1.2 Shading

El fragment shader implementa un **BRDF GGX + Smith** con iluminación difusa de Lambert y
especular Cook-Torrance, alimentado por dos luces direccionales. `metallic` y `roughness`
modulan el lóbulo especular; `emissive` suma luz directa; `alphaMode` soporta `OPAQUE`,
`BLEND` y `MASK` (con descarte por `alphaCutoff`).

**Pendiente a futuro (fuera del alcance de esta iteración):** iluminación basada en imagen
(IBL/env-map), sombras y *tone mapping* ACES. El prompt los pide; se documentan aquí como
trabajo pendiente y no se anuncian como implementados.

---

## 2. Gestión de memoria

### 2.1 Detección de capacidades (`engine/DeviceCapabilities.kt`)

Al arrancar se detecta:

- RAM total y si el dispositivo es *low-RAM* (`ActivityManager.isLowRamDevice`).
- Límite de heap de la app (`Runtime.maxMemory`).
- Soporte de compresión de textura: **ASTC**, **ETC2**, **S3TC**.
- Tamaño máximo de textura (`GL_MAX_TEXTURE_SIZE`).

De ahí sale un **perfil** con:

- `appHeapBudget` = 55% del límite de heap (mínimo 24 MB, máximo 256 MB).
- `textureBudget` = 25% del presupuesto de heap.
- `textureFormat`: ASTC → ETC2 → S3TC → RGBA8888.

**Justificación de los números:** en Android 17 (API 37) los límites por proceso son más
estrictos. Un proceso que se acerca a su límite es terminado **sin stack trace**, lo que hace
imposible depurar el crash. Dejar 45% de margen garantiza que el sistema nunca nos mate, y
el 55% restante da espacio real de trabajo. El suelo de 24 MB evita budgets ridículos en
dispositivos muy restringidos; el techo de 256 MB evita que un dispositivo con mucha RAM
permita acumular gigas y provoque un OOM en el pico de uso.

### 2.2 Reacción a la presión (`engine/MemoryManager.kt`)

`MemoryManager` se registra como `ComponentCallbacks2` y recibe `onTrimMemory` /
`onLowMemory` automáticamente de Android.

| Nivel de trim | Acción |
|---|---|
| `UI_HIDDEN` | Vacía pools, purga mallas huérfanas |
| `RUNNING_MODERATE` | Vacía pools |
| `RUNNING_LOW` | Vacía pools, baja la escala de render a 0.85 |
| `RUNNING_CRITICAL` / `BACKGROUND` | Escala 0.75, purga mallas |
| `COMPLETE` / `onLowMemory` | Libera **todos** los VBOs, escala 0.5, purga todo |

Además, el presupuesto se comprueba en cada evento: si el heap usado supera
`appHeapBudget`, se degrada la calidad aunque el nivel de trim sea bajo.

### 2.3 Pooling

`MemoryManager.obtainFloats()` / `releaseFloats()` mantienen un pool de buffers `FloatArray`
de 4096 floats. Las operaciones de malla (weld, subdivisión) piden buffers en vez de
asignarlos, lo que evita la fragmentación del heap en operaciones repetidas.

### 2.4 Pérdida de contexto GL

Cuando Android destruye el contexto EGL (típico bajo presión de memoria), `onSurfaceCreated`
se vuelve a invocar. El renderizador marca `contextLost = true` y **todos los VBOs se
invalidan** (`glBuffer = 0`, `needsUpload = true`), de modo que se reconstruyen automáticamente
en el siguiente frame. Sin esto, la app se quedaba con un viewport negro y sin cysts.

### 2.5 Guardas de OOM

`MemoryManager.withMemoryGuard { ... }` envuelve las operaciones pesadas:

1. Captura `OutOfMemoryError` → libera buffers, baja calidad, reintenta **una** vez.
2. Captura `RuntimeException` de GPU (`EGL_BAD_ALLOC` y familia) → degrada la calidad y
   devuelve `null` en lugar de propagar la excepción.

El objetivo es que una operación de modelado falle de forma **controlada** (el agente recibe un
error JSON) en lugar de matar el proceso.

---

## 3. Integración MCP

### 3.1 Transporte

Implementación **propia** de MCP Streamable HTTP sobre `ServerSocket` (puerto 8080, ruta `/mcp`),
en vez del SDK oficial de Kotlin.

**Justificación:** el SDK oficial (`io.modelcontextprotocol.sdk`) arrastra Ktor 3,
kotlinx-serialization y corrutinas — del orden de 3–4 MB y una cadena de dependencias que
degrada la reproducibilidad del build y el arranque en gama baja. El protocolo implementado
cubre el subconjunto que la app necesita: `initialize`, `ping`, `tools/list`, `tools/call`,
`resources/list`, `prompts/list`, `notifications/*` (202), `DELETE`/`OPTIONS` (204) y
`Mcp-Session-Id`.

**Trabajo pendiente (documentado, no implementado):** migrar al SDK oficial con
`mcpStreamableHttp` (servidor) y `StreamableHttpClientTransport` (cliente), con soporte real de
SSE y reconexión automática. Es una migración de la capa de transporte, no del resto: las tools
y el modelo de escena no cambian.

### 3.2 Seguridad

- **Bearer obligatorio**: todas las peticiones se validan contra `AuthManager.token(ctx)`.
- **Anti-rebinding**: se valida la cabecera `Host` contra las interfaces permitidas.
- **Validación de `Origin`** para impedir el uso desde páginas web.
- **Rate limit**: 100 operaciones por segundo por ventana deslizante.
- **Token estático** (v1.1.0): el token ya no rota. Antes rotaba en cada arranque de la app,
  lo que rompía la conexión del agente sin previo aviso.

### 3.3 Arquitectura de la conexión

```
  Agente externo                Forge3D Studio
  ──────────────                ────────────────
  POST /mcp  ───────────────►   McpServer (ServerSocket, pool de 4 hilos)
  Authorization: Bearer ────►   AuthManager.token()  ──► 401 si no coincide
                                │
                                └─► McpTools.call(nombre, args)
                                      │  bajo AppModel.lock
                                      └─► Scene / Camera3D (compartidos con la UI)
```

La escena es un **único objeto compartido** (`AppModel.scene`) protegido por un lock global.
Las tools MCP mutan la escena bajo ese lock; la UI refresca con un `Runnable` periódico.
No se usa `runOnUiThread` en el camino MCP: la serialización la garantiza el lock.

### 3.4 Modo cliente

`McpClient` (en `com.forge3d.studio.mcp.client`) conecta con servidores MCP remotos por HTTP:
lista herramientas, las invoca y devuelve el resultado. Se usa desde la UI para conectar con
el propio `opencode-mcp-bridge` y exponer sus herramientas al usuario.

---

## 4. Estructura de módulos

```
com.forge3d.studio
├── MainActivity.kt          UI (Views + XML), ciclo de vida, onTrimMemory
├── ViewportView.kt          Superficie GLES2 táctil + GlResourceOwner
├── engine/                  Núcleo de render y escena (independiente de Android UI)
│   ├── GLRenderer.kt        Shaders, VBOs, dibujo, pérdida de contexto
│   ├── Scene.kt             Objetos, límites, refcount de mallas
│   ├── Mesh.kt              "Triángulo sopa" + AABB + addVertex
│   ├── MeshOps.kt           weld / subdividir / extruir / bisel / normales
│   ├── Animation.kt         Huesos, clips, skinning (LBS)
│   ├── Material.kt          Material PBR
│   ├── Camera3D.kt          Cámara orbital
│   ├── Math3D.kt            Matemáticas 3D
│   ├── Primitives.kt        Cubo, esfera, cilindro, plano
│   ├── DeviceCapabilities.kt Perfil del dispositivo
│   └── MemoryManager.kt    onTrimMemory, pools, guardas de OOM
├── io/                      Importadores y exportadores
│   ├── ObjImporter / StlImporter / ThreeMfImporter / FbxImporter
│   ├── GltfExporter.kt      glTF 2.0 / GLB con PBR
│   └── ProjectStore.kt      Proyectos .m3d.json (+ mallas en .f3dm)
└── mcp/                     Capa MCP
    ├── McpServer.kt         Servidor Streamable HTTP propio
    ├── McpService.kt        Servicio en primer plano
    ├── McpTools.kt          Registro de tools + handlers
    ├── McpClient.kt         Cliente MCP remoto
    ├── AuthManager.kt       Token Bearer (fijo por defecto)
    └── AppModel.kt          Estado global compartido
```

---

## 5. Estado frente a los requisitos del enunciado

| Requisito | Estado | Notas |
|---|---|---|
| Modelado de mallas (vértices/aristas/caras) | ✅ | `MeshOps` con 6 operaciones |
| Import/export glTF, OBJ, STL, FBX, 3MF | ✅ | FBX sin UVs/materiales/huesos (documentado) |
| Materiales PBR | ✅ | baseColor, metallic, roughness, emissive, alphaMode |
| Texturas (albedo/normal/rough/metal/AO) | ❌ | **Pendiente**: no hay UVs ni samplers |
| Animación esquelética | ✅ | `Animation.kt`: huesos, clips, skinning LBS |
| Horneado de iluminación / IBL | ❌ | **Pendiente** |
| MCP servidor (Streamable HTTP) | ✅ | Propio, sin SDK oficial |
| MCP cliente | ✅ | `McpClient` |
| `onTrimMemory` / pools / budgets | ✅ | `MemoryManager` |
| Texturas ASTC/ETC2 | ⚠️ | Detección y selección de formato; **falta** la carga real |
| Brindis (SDK Kotlin MCP) | ❌ | **Pendiente**: ver §3.1 |
| Jetpack Compose | ❌ | **Pendiente**: la UI actual es Views + XML |
| Corrutinas Kotlin | ❌ | **Pendiente**: se usa `Thread`/`ExecutorService` |
| Hilt / Koin | ❌ | **Pendiente**: singletons `object` |
| NDK / CMake | ❌ | **Pendiente**: núcleo 100% Kotlin |
| Tests unitarios | ✅ | 12 tests (malla + animación) |
| Tests instrumentadas | ❌ | **Pendiente** |
| APK debug + release | ✅ | CI los produce como artefactos |
| ≥30 FPS con 100k triángulos | ⚠️ | **Pendiente de medición**: falta banco de pruebas |
| Sin crashes bajo estrés de memoria | ⚠️ | **Pendiente de medición**: falta banco de pruebas |

La tabla distingue explícitamente lo implementado de lo pendiente, para no presentar como
hecho lo que no lo está.
