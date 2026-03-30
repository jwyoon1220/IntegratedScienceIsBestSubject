package io.github.jwyoon1220.scisbest

import imgui.ImGui
import imgui.ImGuiIO
import imgui.flag.*
import imgui.gl3.ImGuiImplGl3
import imgui.glfw.ImGuiImplGlfw
import imgui.type.ImInt
import org.joml.Matrix4f
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL43.*
import org.lwjgl.system.MemoryUtil.*

/**
 * AtomEngine — GPU-accelerated 2D nuclear fission simulator.
 *
 * Architecture:
 *  • All physics (movement, fission, Xe-135, thermodynamics) runs on GPU via Compute Shaders.
 *  • CPU only handles GLFW/ImGui events, sub-step loop dispatch, and minimal SSBO read-backs.
 *  • Rendering uses instanced draw + SSBO without a traditional VBO.
 */
class AtomEngine {

    // ── Window / GL ──────────────────────────────────────────
    private var windowHandle: Long = NULL
    private val windowWidth   = 1280
    private val windowHeight  = 720

    // ── World dimensions (pixel units matching shader grid) ──
    private val worldWidth  = 512f * 2f   // 1024 world units
    private val worldHeight = 512f * 2f   // (each grid cell ≈ 2 px)

    // ── SSBO wrappers ────────────────────────────────────────
    private lateinit var neutronSSBO:   NeutronSSBO
    private lateinit var gridSSBO:      GridSSBO
    private lateinit var counterSSBO:   CounterSSBO
    private lateinit var selectionSSBO: SelectionSSBO

    // ── Compute shader programs ──────────────────────────────
    private var physicsProgram:  Int = 0
    private var decayProgram:    Int = 0

    // ── Render programs ──────────────────────────────────────
    private var neutronProgram:  Int = 0   // instanced particle draw
    private var gridProgram:     Int = 0   // full-screen background quad

    // ── Dummy VAO (required by core profile) ─────────────────
    private var dummyVao: Int = 0

    // ── ImGui backends ───────────────────────────────────────
    private lateinit var imGuiGlfw: ImGuiImplGlfw
    private lateinit var imGuiGl3:  ImGuiImplGl3

    // ── Simulation state (CPU-side) ──────────────────────────
    private var running        = true
    private var simPaused      = false
    private var timeScale      = 1.0f
    private var renderMode     = 0           // 0=material,1=radiation,2=temperature
    private var brushMode      = 0           // 0=U235,1=U238,2=Pu239,3=ctrl rod,4=light water,5=graphite
    private var brushRadius    = 5
    private var brushDensity   = 10f         // kg
    private var criticalMass   = 50f
    private var hoverRadius    = 8f          // world units
    private var frameCount     = 0
    private var lastFpsTime    = System.nanoTime()
    private var fps            = 0f

    // Stats read-back from GPU
    private var activeNeutrons = 0L
    private var totalFissions  = 0L
    private var hoveredNeutronIdx = -1

    // Mouse world-position
    private var mouseWorldX = 0f
    private var mouseWorldY = 0f

    // ── Projection matrix (JOML) ─────────────────────────────
    private val projMatrix = Matrix4f()

    // ── OpenGL timer query ───────────────────────────────────
    private val timerQuery = IntArray(1)
    private var gpuTimeMs  = 0f

    // ═══════════════════════════════════════════════════════════
    //  Initialisation
    // ═══════════════════════════════════════════════════════════

    fun init() {
        initGlfw()
        initOpenGL()
        initImGui()
        initSSBOs()
        initShaders()
        setupDefaultScene()
    }

    private fun initGlfw() {
        GLFWErrorCallback.createPrint(System.err).set()
        check(glfwInit()) { "Failed to init GLFW" }

        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        glfwWindowHint(GLFW_VISIBLE,   GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE)

        windowHandle = glfwCreateWindow(windowWidth, windowHeight,
            "⚛  Integrated Science Nuclear Fission Simulator", NULL, NULL)
        check(windowHandle != NULL) { "Failed to create GLFW window" }

        // Center window
        val vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor())
        if (vidMode != null) {
            glfwSetWindowPos(
                windowHandle,
                (vidMode.width()  - windowWidth)  / 2,
                (vidMode.height() - windowHeight) / 2
            )
        }

        glfwMakeContextCurrent(windowHandle)
        glfwSwapInterval(1)  // V-Sync ON by default
        glfwShowWindow(windowHandle)
    }

    private fun initOpenGL() {
        GL.createCapabilities()
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_PROGRAM_POINT_SIZE)
        glViewport(0, 0, windowWidth, windowHeight)
        glClearColor(0.02f, 0.02f, 0.05f, 1f)

        // Dummy VAO required by OpenGL core profile
        dummyVao = glGenVertexArrays()
        glBindVertexArray(dummyVao)

        // Timer query
        glGenQueries(timerQuery)
    }

    private fun initImGui() {
        ImGui.createContext()
        val io: ImGuiIO = ImGui.getIO()
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard)
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable)
        ImGui.styleColorsDark()

        imGuiGlfw = ImGuiImplGlfw()
        imGuiGlfw.init(windowHandle, true)
        imGuiGl3  = ImGuiImplGl3()
        imGuiGl3.init("#version 430 core")
    }

    private fun initSSBOs() {
        neutronSSBO   = NeutronSSBO()
        gridSSBO      = GridSSBO()
        counterSSBO   = CounterSSBO()
        selectionSSBO = SelectionSSBO()
    }

    private fun initShaders() {
        physicsProgram  = ShaderUtils.computeProgram("shaders/physics.comp")
        decayProgram    = ShaderUtils.computeProgram("shaders/decay.comp")
        neutronProgram  = ShaderUtils.renderProgram("shaders/neutron.vert", "shaders/neutron.frag")
        gridProgram     = ShaderUtils.renderProgram("shaders/grid.vert",    "shaders/grid.frag")

        // Build orthographic projection: world [0..worldWidth, 0..worldHeight] → NDC
        projMatrix.setOrtho(0f, worldWidth, 0f, worldHeight, -1f, 1f)
    }

    /** Place a basic reactor layout: water moderator + small U-235 core. */
    private fun setupDefaultScene() {
        // Fill entire grid with light water as moderator
        gridSSBO.fillRegion(0, 0, 511, 511, structureType = 1, temperature = 20f)

        // Central fuel core: 20×20 cells of U-235
        val cx = 256; val cy = 256; val r = 10
        gridSSBO.fillRegion(cx - r, cy - r, cx + r, cy + r,
            structureType = 0, u235 = 15f, temperature = 20f)

        // Control rods (vertical strips)
        gridSSBO.fillRegion(230, 240, 232, 272, structureType = 4)
        gridSSBO.fillRegion(280, 240, 282, 272, structureType = 4)

        // Outer wall
        gridSSBO.fillRegion(  0,   0, 511,   3, structureType = 6)
        gridSSBO.fillRegion(  0, 508, 511, 511, structureType = 6)
        gridSSBO.fillRegion(  0,   0,   3, 511, structureType = 6)
        gridSSBO.fillRegion(508,   0, 511, 511, structureType = 6)
    }

    // ═══════════════════════════════════════════════════════════
    //  Main Loop
    // ═══════════════════════════════════════════════════════════

    fun run() {
        val startTime = System.nanoTime()
        var lastTime  = startTime

        while (!glfwWindowShouldClose(windowHandle) && running) {
            val now   = System.nanoTime()
            val dtRaw = ((now - lastTime) / 1_000_000_000.0).toFloat().coerceAtMost(0.05f)
            lastTime  = now

            glfwPollEvents()
            updateMouseWorldPos()

            // ── Sub-step physics dispatch ─────────────────────
            if (!simPaused) {
                val steps   = Math.ceil(timeScale.toDouble()).toInt().coerceAtLeast(1)
                val stepDt  = (1f / 60f) * (timeScale / steps.toFloat())

                counterSSBO.updateSeed((now xor (now shr 32)).toInt())
                glBeginQuery(GL_TIME_ELAPSED, timerQuery[0])

                for (s in 0 until steps) {
                    dispatchPhysics(stepDt)
                    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
                    dispatchDecay(stepDt)
                    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
                }

                glEndQuery(GL_TIME_ELAPSED)
            }

            // ── Read back minimal GPU data ────────────────────
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
            val counters     = counterSSBO.read()
            activeNeutrons   = counters.activeNeutrons
            totalFissions    = counters.totalFissions
            hoveredNeutronIdx = selectionSSBO.readHoveredIndex()
            selectionSSBO.reset()  // reset for next frame

            // GPU timer (non-blocking, one frame latency)
            val timerAvail = IntArray(1)
            glGetQueryObjectiv(timerQuery[0], GL_QUERY_RESULT_AVAILABLE, timerAvail)
            if (timerAvail[0] == GL_TRUE) {
                val ns = LongArray(1)
                glGetQueryObjecti64v(timerQuery[0], GL_QUERY_RESULT, ns)
                gpuTimeMs = ns[0] / 1_000_000f
            }

            // ── Render ────────────────────────────────────────
            glClear(GL_COLOR_BUFFER_BIT)
            renderGrid()
            glMemoryBarrier(GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT)
            renderNeutrons()

            // ── ImGui overlay ─────────────────────────────────
            imGuiGlfw.newFrame()
            ImGui.newFrame()
            drawImGui()
            ImGui.render()
            imGuiGl3.renderDrawData(ImGui.getDrawData())

            glfwSwapBuffers(windowHandle)

            // FPS calculation
            frameCount++
            val elapsed = now - lastFpsTime
            if (elapsed >= 1_000_000_000L) {
                fps         = frameCount * 1_000_000_000f / elapsed
                frameCount  = 0
                lastFpsTime = now
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  GPU Dispatch
    // ═══════════════════════════════════════════════════════════

    private fun dispatchPhysics(dt: Float) {
        glUseProgram(physicsProgram)

        // Bind all SSBOs
        neutronSSBO.bind()
        gridSSBO.bind()
        counterSSBO.bind()
        selectionSSBO.bind()

        // Uniforms
        glUniform1f(glGetUniformLocation(physicsProgram, "u_dt"),          dt)
        glUniform2f(glGetUniformLocation(physicsProgram, "u_world_size"),  worldWidth, worldHeight)
        glUniform2f(glGetUniformLocation(physicsProgram, "u_mouse_pos"),   mouseWorldX, mouseWorldY)
        glUniform1f(glGetUniformLocation(physicsProgram, "u_hover_radius"), hoverRadius)
        glUniform1f(glGetUniformLocation(physicsProgram, "u_critical_mass"), criticalMass)

        // 1D dispatch over MAX_NEUTRONS, workgroup size = 256
        val groups = (NeutronSSBO.MAX_NEUTRONS_CONST + 255) / 256
        glDispatchCompute(groups, 1, 1)
    }

    private fun dispatchDecay(dt: Float) {
        glUseProgram(decayProgram)
        gridSSBO.bind()
        glUniform1f(glGetUniformLocation(decayProgram, "u_dt"), dt)

        // 2D dispatch over 512×512 grid, workgroup size = 16×16
        glDispatchCompute(512 / 16, 512 / 16, 1)
    }

    // ═══════════════════════════════════════════════════════════
    //  Rendering
    // ═══════════════════════════════════════════════════════════

    private fun renderGrid() {
        glUseProgram(gridProgram)
        gridSSBO.bind()
        glUniform1i(glGetUniformLocation(gridProgram, "u_render_mode"), renderMode)
        glBindVertexArray(dummyVao)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun renderNeutrons() {
        if (activeNeutrons == 0L) return
        glUseProgram(neutronProgram)
        neutronSSBO.bind()

        // Upload projection matrix
        val matBuf = memAllocFloat(16)
        projMatrix.get(matBuf)
        glUniformMatrix4fv(
            glGetUniformLocation(neutronProgram, "u_projection"), false, matBuf)
        memFree(matBuf)

        glUniform1f(glGetUniformLocation(neutronProgram, "u_point_size"), 3f)
        glUniform1f(glGetUniformLocation(neutronProgram, "u_world_w"), worldWidth)
        glUniform1f(glGetUniformLocation(neutronProgram, "u_world_h"), worldHeight)

        glBindVertexArray(dummyVao)
        // One instanced draw call — vertex shader reads SSBO by gl_InstanceID
        glDrawArraysInstanced(GL_POINTS, 0, 1, activeNeutrons.toInt().coerceAtMost(NeutronSSBO.MAX_NEUTRONS_CONST))
    }

    // ═══════════════════════════════════════════════════════════
    //  ImGui UI
    // ═══════════════════════════════════════════════════════════

    private fun drawImGui() {
        drawPanelSimControl()
        drawPanelEnvironment()
        drawPanelAnalytics()
        drawDebugOverlay()
        drawTooltip()
    }

    // ── Panel 1: Simulation Control ──────────────────────────
    private fun drawPanelSimControl() {
        ImGui.setNextWindowPos(10f, 10f, ImGuiCond.Once)
        ImGui.setNextWindowSize(300f, 200f, ImGuiCond.Once)
        if (ImGui.begin("⚙ Simulation Control")) {
            if (ImGui.button(if (simPaused) "▶ Resume" else "⏸ Pause", 120f, 0f)) {
                simPaused = !simPaused
            }
            ImGui.sameLine()
            if (ImGui.button("🗑 Clear All", 120f, 0f)) {
                // Neutron buffer is reset implicitly when counterSSBO is cleared:
                // all neutron slots stay in memory; the GPU reads active_neutron_count = 0.
                gridSSBO.clearAll()
                counterSSBO.resetCounters()
                setupDefaultScene()
            }

            val ts = floatArrayOf(timeScale)
            if (ImGui.sliderFloat("Time Scale", ts, 0.1f, 100f, "%.1fx")) {
                timeScale = ts[0]
            }

            val steps = Math.ceil(timeScale.toDouble()).toInt().coerceAtLeast(1)
            ImGui.text("Sub-steps/frame: $steps")
            ImGui.text("GPU Compute: %.2f ms".format(gpuTimeMs))
        }
        ImGui.end()
    }

    // ── Panel 2: Environment Setup ───────────────────────────
    private val brushLabels = arrayOf("U-235", "U-238", "Pu-239", "Control Rod", "Light Water", "Graphite", "Wall")
    private val brushStructureMap = intArrayOf(0, 0, 0, 4, 1, 3, 6)

    private fun drawPanelEnvironment() {
        ImGui.setNextWindowPos(10f, 220f, ImGuiCond.Once)
        ImGui.setNextWindowSize(300f, 260f, ImGuiCond.Once)
        if (ImGui.begin("🏗 Environment Setup")) {
            val bm = ImInt(brushMode)
            if (ImGui.combo("Brush Mode", bm, brushLabels)) brushMode = bm.get()

            val br = intArrayOf(brushRadius)
            if (ImGui.sliderInt("Brush Radius", br, 1, 30)) brushRadius = br[0]

            val bd = floatArrayOf(brushDensity)
            if (ImGui.sliderFloat("Density (kg)", bd, 1f, 100f, "%.1f kg")) brushDensity = bd[0]

            val cm = floatArrayOf(criticalMass)
            if (ImGui.sliderFloat("Critical Mass Threshold", cm, 10f, 200f, "%.0f kg")) criticalMass = cm[0]

            ImGui.separator()
            if (ImGui.button("⚡ Spawn 10k Neutrons", 240f, 0f)) {
                spawnNeutronsAtCenter(10_000)
            }
            if (ImGui.button("💥 Spawn 100k Neutrons", 240f, 0f)) {
                spawnNeutronsAtCenter(100_000)
            }

            ImGui.separator()
            ImGui.text("Left-click on grid to paint")
            if (isMouseOnGrid() && ImGui.isMouseDown(ImGuiMouseButton.Left) && !ImGui.getIO().wantCaptureMouse) {
                paintBrush()
            }
        }
        ImGui.end()
    }

    // ── Panel 3: Analytics & Sensors ────────────────────────
    private val renderModeLabels = arrayOf("Particles (Material)", "Radiation Heatmap", "Temperature Heatmap")

    private fun drawPanelAnalytics() {
        ImGui.setNextWindowPos(10f, 490f, ImGuiCond.Once)
        ImGui.setNextWindowSize(300f, 220f, ImGuiCond.Once)
        if (ImGui.begin("📊 Analytics & Sensors")) {
            ImGui.text("Active Neutrons:  %,d / %,d".format(activeNeutrons, NeutronSSBO.MAX_NEUTRONS_CONST))
            ImGui.progressBar(activeNeutrons.toFloat() / NeutronSSBO.MAX_NEUTRONS_CONST, 280f, 0f)

            ImGui.text("Total Fissions:   %,d".format(totalFissions))

            ImGui.separator()
            val rm = intArrayOf(renderMode)
            for ((i, label) in renderModeLabels.withIndex()) {
                if (ImGui.radioButton(label, rm[0] == i)) renderMode = i
            }
        }
        ImGui.end()
    }

    // ── Debug Overlay (top-right) ────────────────────────────
    private fun drawDebugOverlay() {
        val pad = 10f
        ImGui.setNextWindowPos(windowWidth - 260f - pad, pad, ImGuiCond.Always)
        ImGui.setNextWindowSize(260f, 150f, ImGuiCond.Always)
        ImGui.setNextWindowBgAlpha(0.65f)
        val flags = ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoInputs or
                    ImGuiWindowFlags.NoNav or ImGuiWindowFlags.NoMove
        if (ImGui.begin("##Debug", flags)) {
            ImGui.text("FPS:        %.1f".format(fps))
            ImGui.text("Frame time: %.2f ms".format(if (fps > 0) 1000f / fps else 0f))
            ImGui.text("GPU time:   %.2f ms".format(gpuTimeMs))
            ImGui.text("Neutrons:   %,d".format(activeNeutrons))
            ImGui.text("Fissions:   %,d".format(totalFissions))
            ImGui.text("Sim speed:  %.1fx (%d steps)".format(
                timeScale, Math.ceil(timeScale.toDouble()).toInt()))
        }
        ImGui.end()
    }

    // ── Mouse hover tooltip ──────────────────────────────────
    private fun drawTooltip() {
        if (!ImGui.getIO().wantCaptureMouse) {
            // Grid cell tooltip
            val gcx  = (mouseWorldX / (worldWidth  / 512f)).toInt().coerceIn(0, 511)
            val gcy  = (mouseWorldY / (worldHeight / 512f)).toInt().coerceIn(0, 511)
            val cell = gridSSBO.readCell(gcx, gcy)

            ImGui.beginTooltip()
            ImGui.text("[Cell %d, %d]".format(gcx, gcy))
            ImGui.text("Material: ${structureName(cell.structureType)}")

            val tempStr = if (cell.temperature > 2000f) "⚠ CRITICAL" else "%.1f °C".format(cell.temperature)
            ImGui.text("Temperature: $tempStr")

            if (cell.u235  > 0f) ImGui.textColored(1f, 1f, 0f, 1f, "U-235:  %.2f kg".format(cell.u235))
            if (cell.u238  > 0f) ImGui.text("U-238:  %.2f kg".format(cell.u238))
            if (cell.pu239 > 0f) ImGui.textColored(1f, 0.5f, 0f, 1f, "Pu-239: %.2f kg".format(cell.pu239))
            if (cell.xe135 > 0.0001f) ImGui.textColored(0.8f, 0f, 0.8f, 1f,
                "Xe-135: %.4f ppm".format(cell.xe135))
            ImGui.text("Dose:   %.2f Sv/h".format(cell.radiationDose))

            // Neutron tooltip (GPU-picked)
            if (hoveredNeutronIdx >= 0) {
                val n = neutronSSBO.readNeutron(hoveredNeutronIdx)
                if (n != null) {
                    ImGui.separator()
                    ImGui.text("[Neutron ID: #%d]".format(hoveredNeutronIdx))
                    val state = if (n.energy >= 15f) "Fast ⚡" else "Thermal 🔴"
                    ImGui.text("State:  $state")
                    ImGui.text("Energy: %.2f MeV".format(n.energy))
                    val speed = Math.sqrt((n.velX * n.velX + n.velY * n.velY).toDouble()).toFloat()
                    ImGui.text("Speed:  %.2f  [vx: %.1f, vy: %.1f]".format(speed, n.velX, n.velY))
                }
            }
            ImGui.endTooltip()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private fun structureName(t: Int) = when (t) {
        1    -> "Light Water"
        2    -> "Heavy Water"
        3    -> "Graphite"
        4    -> "Control Rod"
        5    -> "Reflector"
        6    -> "Wall"
        else -> "Vacuum/Fuel"
    }

    private fun updateMouseWorldPos() {
        val mx = DoubleArray(1); val my = DoubleArray(1)
        glfwGetCursorPos(windowHandle, mx, my)
        // Convert screen pixels → world units (Y is flipped)
        mouseWorldX = (mx[0].toFloat() / windowWidth)  * worldWidth
        mouseWorldY = (1f - my[0].toFloat() / windowHeight) * worldHeight
    }

    private fun isMouseOnGrid(): Boolean =
        mouseWorldX in 0f..worldWidth && mouseWorldY in 0f..worldHeight

    private fun paintBrush() {
        val cellW = worldWidth  / 512f
        val cellH = worldHeight / 512f
        val cx    = (mouseWorldX / cellW).toInt()
        val cy    = (mouseWorldY / cellH).toInt()
        val r     = brushRadius

        val st = brushStructureMap[brushMode]
        val (u235, u238, pu239) = when (brushMode) {
            0    -> Triple(brushDensity, 0f, 0f)
            1    -> Triple(0f, brushDensity, 0f)
            2    -> Triple(0f, 0f, brushDensity)
            else -> Triple(0f, 0f, 0f)
        }

        gridSSBO.fillRegion(
            cx - r, cy - r, cx + r, cy + r,
            structureType = st,
            u235 = u235, u238 = u238, pu239 = pu239
        )
    }

    private fun spawnNeutronsAtCenter(count: Int) {
        val cx = worldWidth  / 2f
        val cy = worldHeight / 2f
        // Current active count is the write offset (slots are reused by GPU)
        val offset = activeNeutrons.toInt().coerceAtMost(NeutronSSBO.MAX_NEUTRONS_CONST - count)
        if (offset >= 0) {
            neutronSSBO.activateNeutrons(offset, count.coerceAtMost(NeutronSSBO.MAX_NEUTRONS_CONST - offset), cx, cy)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Cleanup
    // ═══════════════════════════════════════════════════════════

    fun cleanup() {
        imGuiGl3.dispose()
        imGuiGlfw.dispose()
        ImGui.destroyContext()

        neutronSSBO.delete()
        gridSSBO.delete()
        counterSSBO.delete()
        selectionSSBO.delete()

        glDeleteProgram(physicsProgram)
        glDeleteProgram(decayProgram)
        glDeleteProgram(neutronProgram)
        glDeleteProgram(gridProgram)
        glDeleteVertexArrays(dummyVao)
        glDeleteQueries(timerQuery)

        glfwFreeCallbacks(windowHandle)
        glfwDestroyWindow(windowHandle)
        glfwTerminate()
        glfwSetErrorCallback(null)?.free()
    }
}
