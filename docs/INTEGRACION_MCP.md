# Manual de integración MCP — Forge3D Studio

Cómo se conecta un agente externo a Forge3D Studio y cómo la app se conecta a servidores MCP
remotos.

---

## 1. Agente → App (modo servidor)

Forge3D Studio levanta un servidor **MCP Streamable HTTP** en:

```
http://127.0.0.1:8080/mcp
```

### 1.1 Token

El token por defecto es **estático**:

```
Forge3D-studio
```

> **Por qué estático.** En la v1.0.0 el token se regeneraba al reiniciar la app, de modo que la
> configuración del agente se quedaba obsoleta sin aviso. Desde la v1.1.0 el token no cambia:
> el agente se configura **una vez** y la conexión sobrevive a reinicios de la app.

El modo rotativo sigue disponible para quien lo necesite explícitamente (preferencias
`forge3d_mcp` → `modo_token` = `rotativo`). En ese modo, la rotación reinicia el servidor MCP
para que el valor en memoria y el de la UI coincidan.

### 1.2 Configuración en OpenCode

```json
{
  "mcpServers": {
    "forge3d": {
      "url": "http://127.0.0.1:8080/mcp",
      "headers": {
        "Authorization": "Bearer Forge3D-studio"
      }
    }
  }
}
```

Si el agente corre en **otro dispositivo** de la misma red, activa **"Exponer en LAN (0.0.0.0)"**
en la UI de Forge3D y usa la IP del teléfono:

```
http://192.168.1.79:8080/mcp
```

> **Aviso de seguridad.** En modo LAN el servidor queda accesible a toda la red local. El token
> es la única protección: no lo compartas y desactiva el modo LAN cuando no lo uses.

### 1.3 Comprobación rápida

```bash
curl -s -H "Authorization: Bearer Forge3D-studio" \
  -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:8080/mcp \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Respuesta esperada: `{"jsonrpc":"2.0","id":1,"result":{"tools":[...]}}` con **33 tools**.

Si la respuesta es `{"error":"Bearer token requerido o inválido"}`, el token no coincide: vuelve
a copiarlo desde la UI de la app (pantalla *Servidor MCP (Streamable HTTP)*).

### 1.4 Listado de tools

| Grupo | Tools |
|---|---|
| Escena | `info_servidor`, `listar_escena`, `objeto_info`, `vaciar_escena` |
| Primitivas | `crear_cubo`, `crear_esfera`, `crear_cilindro`, `crear_plano`, `crear_primitiva` |
| Transformación | `transformar_objeto`, `duplicar_objeto`, `eliminar_objeto`, `seleccionar_objeto`, `deseleccionar_todo` |
| Malla | `malla_weld`, `malla_subdividir`, `malla_extruir`, `malla_bisel`, `malla_normales`, `malla_estadisticas` |
| Materiales | `aplicar_material` (baseColor, metallic, roughness, emissive, alphaMode) |
| Cámara | `camara_estado`, `camara_orbitar`, `camara_desplazar`, `camara_zoom`, `camara_encuadrar` |
| Proyectos | `listar_proyectos`, `guardar_proyecto`, `cargar_proyecto` |
| IO | `exportar_gltf` (glb/gltf), `importar_modelo` (obj/stl/3mf/fbx) |
| Seguridad | `token_generar`, `token_revocar` |
| Diagnóstico | `info_memoria` |

### 1.5 Ejemplos

Crear un cubo y aplicarle material PBR:

```bash
curl -s -H "Authorization: Bearer Forge3D-studio" -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:8080/mcp \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
        "name":"crear_cubo",
        "arguments":{"nombre":"Caja","posicion":[0,0.5,0],"escala":[1,1,1]}}}'
```

```bash
curl -s -H "Authorization: Bearer Forge3D-studio" -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:8080/mcp \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
        "name":"aplicar_material",
        "arguments":{"id":"obj_0002","material":{
          "baseColor":[0.13,0.09,0.06,1],"metallic":0.0,"roughness":0.88}}}}'
```

Exportar la escena a glTF binario:

```bash
curl -s -H "Authorization: Bearer Forge3D-studio" -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:8080/mcp \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
        "name":"exportar_gltf","arguments":{"formato":"glb","nombre":"mi_escena"}}}'
```

La respuesta incluye `ruta`, `bytes` y `objetos`.

### 1.6 Endpoint y protocolo

- **Endpoint**: `POST http://127.0.0.1:8080/mcp`
- **Protocolo**: MCP Streamable HTTP / JSON-RPC 2.0
- **Versión de protocolo**: `2025-11-25`
- **Cabecera de sesión**: `Mcp-Session-Id` (se genera en `initialize`)
- **Métodos**: `initialize`, `ping`, `tools/list`, `tools/call`, `resources/list`,
  `prompts/list`, `notifications/*` (responde 202)
- **Otros verbos**: `GET` → 405, `DELETE`/`OPTIONS` → 204, `OPTIONS` → CORS

### 1.7 Límites

| Límite | Valor |
|---|---|
| Objetos por escena | 300 |
| Triángulos por escena | 1 000 000 |
| Rate limit | 100 ops/s por ventana deslizante |

---

## 2. App → Agente (modo cliente)

Forge3D Studio puede conectarse a un servidor MCP remoto para delegar tareas (por ejemplo,
generación procedural de geometría). Desde la UI:

1. Abre **Servidor MCP → Cliente MCP remoto**.
2. Escribe la **URL del servidor** (debe terminar en `/mcp`).
3. Escribe el **Bearer token** si el servidor lo requiere.
4. Pulsa **Conectar**.

La app implementa `initialize` con la versión de protocolo `2025-11-25`, obtiene la lista de
tools remotas y las muestra. Desde un servidor remoto, un agente puede invocar la tool
`crear_esfera` de Forge3D Studio y recibir la confirmación.

---

## 3. Anatomía de una petición MCP contra Forge3D

```
POST /mcp HTTP/1.1
Host: 127.0.0.1:8080
Content-Type: application/json
Accept: application/json, text/event-stream
Authorization: Bearer Forge3D-studio
Content-Length: <n>

{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
   "name":"crear_primitiva",
   "arguments":{"tipo":"esfera","nombre":"Bola","posicion":[0,1,0]}}}
```

Respuesta:

```json
{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"id\":\"obj_0002\", ...}"}],"isError":false}}
```

Los errores de negocio (objeto inexistente, límite alcanzado) vuelven con `isError: true` y un
mensaje explicativo en `content[0].text`. Los errores de protocolo (método desconocido) usan
el error JSON-RPC estándar (`-32601`).

---

## 4. Diagnóstico

La tool `info_memoria` da el estado de memoria y las capacidades del dispositivo:

```bash
curl -s -H "Authorization: Bearer Forge3D-studio" -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:8080/mcp \
  -d '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"info_memoria","arguments":{}}}'
```

Devuelve: nivel de presión de memoria, calidad gráfica vigente, heap usado (Java y nativo),
bloques en el pool, modelo del dispositivo, RAM total/libre, presupuesto de heap y de texturas,
formato de textura detectado (ASTC/ETC2/S3TC) y tamaño máximo de textura.

Si la app se cierra sin stack trace (típico cuando el sistema mata el proceso por superar su
presupuesto de memoria), consulta `info_memoria` y baja la carga de la escena o desactiva
objetos grandes.
