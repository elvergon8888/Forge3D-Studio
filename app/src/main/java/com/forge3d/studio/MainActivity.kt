package com.forge3d.studio

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.forge3d.studio.engine.DeviceCapabilities
import com.forge3d.studio.engine.Material
import com.forge3d.studio.engine.Math3D
import com.forge3d.studio.engine.MemoryManager
import com.forge3d.studio.engine.Mesh
import com.forge3d.studio.engine.Primitives
import com.forge3d.studio.engine.SceneObject
import com.forge3d.studio.io.GltfExporter
import com.forge3d.studio.io.ProjectStore
import com.forge3d.studio.mcp.AppModel
import com.forge3d.studio.mcp.AuthManager
import com.forge3d.studio.mcp.McpService
import com.forge3d.studio.mcp.McpTools

class MainActivity : Activity() {

    private lateinit var viewport: ViewportView
    private lateinit var txtObjCount: TextView
    private lateinit var txtMcpState: TextView
    private lateinit var selPanel: ScrollView
    private lateinit var txtSelName: TextView

    private lateinit var sbPX: SeekBar; private lateinit var sbPY: SeekBar; private lateinit var sbPZ: SeekBar
    private lateinit var sbRX: SeekBar; private lateinit var sbRY: SeekBar; private lateinit var sbRZ: SeekBar
    private lateinit var sbScale: SeekBar; private lateinit var sbMetal: SeekBar; private lateinit var sbRough: SeekBar

    private var updatingPanel = false
    private val handler = Handler(Looper.getMainLooper())

    private val REQ_PICK = 41
    private val REQ_SAVE_GLB = 42
    private val REQ_SAVE_GLTF = 43
    private val REQ_NOTIF = 90
    private var pendingLanStart = false

    private val colorHexes = arrayOf(
        "#F2F2F2", "#8C8C8C", "#1F1F1F", "#C0392B", "#E67E22",
        "#F1C40F", "#27AE60", "#2980B9", "#8E44AD"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppModel.appContext = applicationContext

        // Gestión de memoria: el perfil de capacidades y el registro de callbacks de trim.
        MemoryManager.init(applicationContext)

        setContentView(R.layout.activity_main)

        viewport = findViewById(R.id.viewport)
        txtObjCount = findViewById(R.id.txtObjCount)
        txtMcpState = findViewById(R.id.txtMcpState)
        selPanel = findViewById(R.id.selPanel)
        txtSelName = findViewById(R.id.txtSelName)

        sbPX = findViewById(R.id.sbPX); sbPY = findViewById(R.id.sbPY); sbPZ = findViewById(R.id.sbPZ)
        sbRX = findViewById(R.id.sbRX); sbRY = findViewById(R.id.sbRY); sbRZ = findViewById(R.id.sbRZ)
        sbScale = findViewById(R.id.sbScale); sbMetal = findViewById(R.id.sbMetal); sbRough = findViewById(R.id.sbRough)

        setupSliders()
        setupModes()
        setupButtons()
        setupColors()

        viewport.onSelectChanged = { runOnUiThread { refreshSelection(); updateStats() } }

        // escena inicial: un cubo de demostración
        synchronized(AppModel.lock) {
            val s = AppModel.scene
            if (s.objects.isEmpty()) {
                val obj = SceneObject(s.nextId(), s.autoName(Primitives.CUBE), Primitives.cube())
                obj.primitiva = Primitives.CUBE
                obj.position[1] = 0.5f
                s.add(obj)
                s.selected = obj.id
                val b = FloatArray(6)
                if (s.worldAabb(b)) AppModel.camera.fit(b)
            }
        }
        refreshSelection()
        updateStats()

        // Enlaza el gestor de memoria con el renderer GL y la escena, para poder
        // liberar buffers y degradar la calidad cuando el sistema avise de presión.
        MemoryManager.bind(
            viewport,
            object : MemoryManager.SceneResourceOwner {
                override fun purgeUnusedMeshes(keepAtLeast: Int): Int {
                    synchronized(AppModel.lock) {
                        var freed = 0
                        val s = AppModel.scene
                        // Elimina mallas huérfanas del pool de liberación del renderer.
                        while (AppModel.meshDeleteQueue.size > keepAtLeast) {
                            AppModel.meshDeleteQueue.poll()
                            freed++
                        }
                        return freed
                    }
                }

                override fun onQualityChanged(q: DeviceCapabilities.Quality) = Unit
            }
        )

        handleIntent(intent)
    }

    /**
     * El sistema pide liberar memoria. Delegamos en [MemoryManager], que decide si hay que
     * soltar cachés, bajar la resolución de render o purgar mallas.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MemoryManager.handleTrim(level)
    }

    /** Presión de memoria crítica: liberamos todo lo recuperable. */
    override fun onLowMemory() {
        super.onLowMemory()
        MemoryManager.handleTrim(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ---------- UI ----------

    private fun setupSliders() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingPanel) return
                val id = AppModel.scene.selected ?: return
                synchronized(AppModel.lock) {
                    val o = AppModel.scene.byId(id) ?: return@synchronized
                    when (bar.id) {
                        R.id.sbPX -> o.position[0] = (progress - 300) / 100f
                        R.id.sbPY -> o.position[1] = (progress - 300) / 100f
                        R.id.sbPZ -> o.position[2] = (progress - 300) / 100f
                        R.id.sbRX -> o.rotation[0] = progress.toFloat()
                        R.id.sbRY -> o.rotation[1] = progress.toFloat()
                        R.id.sbRZ -> o.rotation[2] = progress.toFloat()
                        R.id.sbScale -> {
                            val s = 0.1f + progress / 100f
                            for (i in 0..2) o.scale[i] = s
                        }
                        R.id.sbMetal -> o.material.metallic = progress / 100f
                        R.id.sbRough -> o.material.roughness = progress / 100f
                    }
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        }
        for (b in arrayOf(sbPX, sbPY, sbPZ, sbRX, sbRY, sbRZ, sbScale, sbMetal, sbRough)) {
            b.setOnSeekBarChangeListener(listener)
        }
    }

    private fun setupModes() {
        val group = findViewById<RadioGroup>(R.id.modeGroup)
        group.setOnCheckedChangeListener { _, checkedId ->
            viewport.mode = when (checkedId) {
                R.id.modeMove -> ViewportView.MODE_MOVE
                R.id.modeRotate -> ViewportView.MODE_ROTATE
                R.id.modeScale -> ViewportView.MODE_SCALE
                else -> ViewportView.MODE_ORBIT
            }
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnCube).setOnClickListener { addPrimitive(Primitives.CUBE) }
        findViewById<Button>(R.id.btnSphere).setOnClickListener { addPrimitive(Primitives.SPHERE) }
        findViewById<Button>(R.id.btnCylinder).setOnClickListener { addPrimitive(Primitives.CYLINDER) }
        findViewById<Button>(R.id.btnPlane).setOnClickListener { addPrimitive(Primitives.PLANE) }
        findViewById<Button>(R.id.btnDeleteSel).setOnClickListener { deleteSelected() }
        findViewById<Button>(R.id.btnImport).setOnClickListener { openImport() }
        findViewById<Button>(R.id.btnExportGlb).setOnClickListener { saveExport(true) }
        findViewById<Button>(R.id.btnExportGltf).setOnClickListener { saveExport(false) }
        findViewById<Button>(R.id.btnProjects).setOnClickListener { showProjectsDialog() }
        findViewById<Button>(R.id.btnMcp).setOnClickListener { showMcpDialog() }
    }

    private fun setupColors() {
        val ids = arrayOf(
            R.id.colBtn1, R.id.colBtn2, R.id.colBtn3, R.id.colBtn4, R.id.colBtn5,
            R.id.colBtn6, R.id.colBtn7, R.id.colBtn8, R.id.colBtn9
        )
        for (i in ids.indices) {
            findViewById<Button>(ids[i]).setOnClickListener {
                val id = AppModel.scene.selected ?: return@setOnClickListener
                synchronized(AppModel.lock) {
                    AppModel.scene.byId(id)?.material?.let { m ->
                        Material.parseHex(colorHexes[i])?.let { m.baseColor = it }
                    }
                }
            }
        }
    }

    private fun addPrimitive(tipo: String) {
        synchronized(AppModel.lock) {
            val s = AppModel.scene
            val mesh = Primitives.create(tipo) ?: return
            val obj = SceneObject(s.nextId(), s.autoName(tipo), mesh)
            obj.primitiva = tipo
            if (tipo != Primitives.PLANE) obj.position[1] = 0.5f
            if (!s.add(obj)) {
                toast("Límite alcanzado (300 objetos / 1M tris)")
                return
            }
            s.selected = obj.id
            AppModel.camera.fit(obj.worldBounds(FloatArray(6)))
        }
        refreshSelection()
        updateStats()
    }

    private fun deleteSelected() {
        synchronized(AppModel.lock) {
            val id = AppModel.scene.selected ?: return
            val o = AppModel.scene.remove(id) ?: return
            if (o.mesh.users <= 0) AppModel.meshDeleteQueue.add(o.mesh)
        }
        refreshSelection()
        updateStats()
        toast("Objeto eliminado")
    }

    private fun refreshSelection() {
        val id = AppModel.scene.selected
        val o = synchronized(AppModel.lock) { AppModel.scene.byId(id) }
        if (o == null) {
            selPanel.visibility = View.GONE
            return
        }
        selPanel.visibility = View.VISIBLE
        txtSelName.text = "${o.name} · ${o.id}"
        updatingPanel = true
        sbPX.progress = ((o.position[0] + 3f) * 100f).toInt().coerceIn(0, 600)
        sbPY.progress = ((o.position[1] + 3f) * 100f).toInt().coerceIn(0, 600)
        sbPZ.progress = ((o.position[2] + 3f) * 100f).toInt().coerceIn(0, 600)
        sbRX.progress = o.rotation[0].toInt().coerceIn(0, 360)
        sbRY.progress = o.rotation[1].toInt().coerceIn(0, 360)
        sbRZ.progress = o.rotation[2].toInt().coerceIn(0, 360)
        sbScale.progress = (((o.scale[0] - 0.1f) * 100f).toInt()).coerceIn(0, 400)
        sbMetal.progress = (o.material.metallic * 100f).toInt()
        sbRough.progress = (o.material.roughness * 100f).toInt()
        updatingPanel = false
    }

    private fun updateStats() {
        val s = AppModel.scene
        txtObjCount.text = "${s.objects.size} objetos · ${String.format(java.util.Locale.US, "%.1fk", s.totalTris() / 1000f)} tris"
        txtMcpState.text = if (McpService.running) "MCP:8080" else "MCP:off"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- Importar (SAF + VIEW/SEND) ----------

    private fun openImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            toast("No se pudo abrir el selector")
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val action = i.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri: Uri? = i.data ?: i.getParcelableExtra(Intent.EXTRA_STREAM)
        if (uri != null) importUri(uri)
    }

    private fun nameOf(uri: Uri): String {
        var n = uri.lastPathSegment ?: "modelo"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { n = it }
                }
            }
        } catch (e: Exception) {}
        return n
    }

    private fun importUri(uri: Uri) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    runOnUiThread { toast("Archivo vacío o ilegible") }
                    return@Thread
                }
                val name = nameOf(uri)
                val mesh = McpTools.importar(bytes, name)
                if (mesh == null) {
                    runOnUiThread { toast("Formato no reconocido (.obj/.stl/.3mf/.fbx)") }
                    return@Thread
                }
                val obj: SceneObject
                synchronized(AppModel.lock) {
                    val s = AppModel.scene
                    obj = SceneObject(s.nextId(), name.substringBeforeLast('.'), mesh)
                    obj.tipo = "importado"
                    s.add(obj)
                    s.selected = obj.id
                    AppModel.camera.fit(obj.worldBounds(FloatArray(6)))
                }
                runOnUiThread {
                    refreshSelection()
                    updateStats()
                    toast("Importado ${obj.name} (${mesh.triangleCount} tris)")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Error importando: ${e.message}") }
            }
        }.start()
    }

    // ---------- Exportar (SAF) ----------

    private fun saveExport(glb: Boolean) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = if (glb) "model/gltf-binary" else "model/gltf+json"
        i.putExtra(Intent.EXTRA_TITLE, if (glb) "escena.glb" else "escena.gltf")
        try {
            startActivityForResult(i, if (glb) REQ_SAVE_GLB else REQ_SAVE_GLTF)
        } catch (e: Exception) {
            toast("No se pudo abrir el guardado")
        }
    }

    private fun doExport(uri: Uri, glb: Boolean) {
        try {
            val objs = synchronized(AppModel.lock) { ArrayList(AppModel.scene.objects) }
            if (objs.isEmpty()) {
                toast("Escena vacía")
                return
            }
            val bytes = if (glb) GltfExporter.buildGlb(objs) else GltfExporter.buildGltf(objs)
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            toast("Exportado (${bytes.size} bytes)")
        } catch (e: Exception) {
            toast("Error exportando: ${e.message}")
        }
    }

    // ---------- Proyectos ----------

    private fun showProjectsDialog() {
        val items = ProjectStore.listProyectos()
        val opts = arrayOf("Guardar proyecto…", "Cargar proyecto…") +
            if (items.isEmpty()) emptyArray() else items.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Proyectos (.m3d.json)")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> saveProjectDialog()
                    1 -> loadProjectDialog(items)
                    else -> loadProject(items[which - 2])
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun saveProjectDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "Nombre_del_proyecto"
        android.app.AlertDialog.Builder(this)
            .setTitle("Guardar proyecto")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = input.text.toString().ifBlank { "Proyecto" }
                val f = synchronized(AppModel.lock) {
                    ProjectStore.guardar(nombre, AppModel.scene, AppModel.camera)
                }
                toast("Guardado: ${f.name}")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadProjectDialog(items: List<String>) {
        if (items.isEmpty()) {
            toast("Sin proyectos guardados")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Cargar proyecto")
            .setItems(items.toTypedArray()) { _, which -> loadProject(items[which]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadProject(nombre: String) {
        Thread {
            val n = try {
                synchronized(AppModel.lock) {
                    ProjectStore.cargar(nombre, AppModel.scene, AppModel.camera)
                }
            } catch (e: Exception) {
                -1
            }
            runOnUiThread {
                if (n > 0) {
                    synchronized(AppModel.lock) {
                        val b = FloatArray(6)
                        if (AppModel.scene.worldAabb(b)) AppModel.camera.fit(b)
                    }
                    refreshSelection()
                    updateStats()
                    toast("Cargados $n objetos")
                } else {
                    toast("No se pudo cargar el proyecto")
                }
            }
        }.start()
    }

    // ---------- MCP ----------

    private fun showMcpDialog() {
        val ctx = this
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)

        val txtToken = TextView(this)
        txtToken.typeface = android.graphics.Typeface.MONOSPACE
        txtToken.textSize = 12f
        txtToken.setTextColor(0xFFFF7A29.toInt())
        txtToken.setTextIsSelectable(true)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        fun mkBtn(label: String, action: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.textSize = 12f
            b.setOnClickListener { action() }
            return b
        }
        var status: TextView? = null
        var lanCheck: CheckBox? = null

        row.addView(mkBtn("Generar") {
            AuthManager.generar(applicationContext)
            txtToken.text = AuthManager.token(applicationContext)
        })
        row.addView(mkBtn("Copiar") {
            val t = AuthManager.token(applicationContext)
            if (t != null) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("token_mcp", t))
                toast("Token copiado")
            } else toast("Sin token")
        })
        row.addView(mkBtn("Revocar") {
            AuthManager.revocar(applicationContext)
            txtToken.text = "(sin token)"
        })
        box.addView(row)

        box.addView(TextView(this).apply {
            text = "Token Bearer (curl/OpenCode/Termux):"
            textSize = 12f
            setTextColor(0xFF9AA6B2.toInt())
        })
        txtToken.text = AuthManager.token(applicationContext) ?: "(sin token — pulsa Generar)"
        box.addView(txtToken)

        val lan = CheckBox(this)
        lan.text = "Exponer en LAN (0.0.0.0:8080)"
        lan.textSize = 12f
        lan.setTextColor(0xFFE8ECF1.toInt())
        lanCheck = lan
        box.addView(lan)

        val st = TextView(this)
        st.textSize = 12f
        st.setTextColor(0xFFE8ECF1.toInt())
        status = st
        box.addView(st)

        val toggle = Button(this)
        toggle.textSize = 12f
        fun refreshStatus() {
            val running = McpService.running
            status?.text = if (running) "Activo: ${McpService.server?.info()}" else "Detenido"
            toggle.text = if (running) "Detener servidor" else "Iniciar servidor"
            updateStats()
        }
        toggle.setOnClickListener {
            if (McpService.running) {
                McpService.stop(ctx)
                refreshStatus()
            } else {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingLanStart = lanCheck?.isChecked == true
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                } else {
                    McpService.start(ctx, lanCheck?.isChecked == true)
                }
                refreshStatus()
            }
        }
        box.addView(toggle)
        refreshStatus()

        android.app.AlertDialog.Builder(this)
            .setTitle("Servidor MCP · 127.0.0.1:8080/mcp")
            .setView(box)
            .setPositiveButton("Cerrar") { _, _ -> updateStats() }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            McpService.start(this, pendingLanStart)
            pendingLanStart = false
        }
    }

    // ---------- Resultados SAF ----------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQ_PICK -> importUri(data.data!!)
            REQ_SAVE_GLB -> doExport(data.data!!, true)
            REQ_SAVE_GLTF -> doExport(data.data!!, false)
        }
    }

    // ---------- Ciclo ----------

    private val refresher = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSelection()
        updateStats()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }
}
