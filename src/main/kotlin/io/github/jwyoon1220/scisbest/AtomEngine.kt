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

    // ── Camera / viewport ────────────────────────────────────
    // cameraOffset: world-space position of the bottom-left corner of the viewport
    // cameraZoom:   how many times the world is magnified (1 = fit-to-window)
    private var cameraZoom    = 1f
    private var cameraOffsetX = 0f
    private var cameraOffsetY = 0f

    // Middle-mouse drag state
    private var middleDragging = false
    private var lastMiddleX    = 0.0
    private var lastMiddleY    = 0.0

    // Stats read-back from GPU
    private var activeNeutrons = 0L
    private var totalFissions  = 0L
    private var hoveredNeutronIdx = -1

    // Mouse world-position
    private var mouseWorldX = 0f
    private var mouseWorldY = 0f

    // ── 가이거 계수기 (오디오) ────────────────────────────────
    private lateinit var geigerCounter: GeigerCounter
    private var prevTotalFissions   = 0L   // 이전 프레임의 총 핵분열 수
    private var frameFissions       = 0L   // 현재 프레임의 신규 핵분열 수

    // 우클릭 상태 추적 (버튼-다운 에지 검출)
    private var prevRightButtonDown = false

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
        geigerCounter = GeigerCounter()
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
            "핵분열 시뮬레이터", NULL, NULL)
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

        // ── 한글 폰트 로드 (NanumGothic) ──────────────────────────
        val fontBytes = AtomEngine::class.java.getResourceAsStream("/fonts/NanumGothic.ttf")?.readBytes()
        if (fontBytes != null) {
            // Korean glyph ranges include basic Latin (0x0020-0x00FF) + Hangul
            io.fonts.addFontFromMemoryTTF(fontBytes, 16f, io.fonts.getGlyphRangesKorean())
        } else {
            System.err.println("[AtomEngine] 경고: 한글 폰트(/fonts/NanumGothic.ttf)를 찾을 수 없습니다. 기본 폰트를 사용합니다.")
            io.fonts.addFontDefault()
        }

        applyCustomStyle()

        imGuiGlfw = ImGuiImplGlfw()
        imGuiGlfw.init(windowHandle, true)
        imGuiGl3  = ImGuiImplGl3()
        imGuiGl3.init("#version 430 core")
    }

    /** 커스텀 ImGui 스타일 (어두운 청록색 테마) */
    private fun applyCustomStyle() {
        ImGui.styleColorsDark()
        val style = ImGui.getStyle()

        style.windowRounding    = 6f
        style.frameRounding     = 4f
        style.grabRounding      = 4f
        style.popupRounding     = 4f
        style.scrollbarRounding = 6f
        style.tabRounding       = 4f

        style.setWindowPadding(10f, 10f)
        style.setFramePadding(6f, 4f)
        style.setItemSpacing(8f, 6f)

        // Accent: 청록색 계열
        style.setColor(ImGuiCol.TitleBgActive,      0.10f, 0.35f, 0.45f, 1.00f)
        style.setColor(ImGuiCol.TitleBg,            0.07f, 0.22f, 0.30f, 1.00f)
        style.setColor(ImGuiCol.Header,             0.12f, 0.40f, 0.50f, 0.80f)
        style.setColor(ImGuiCol.HeaderHovered,      0.15f, 0.50f, 0.62f, 1.00f)
        style.setColor(ImGuiCol.HeaderActive,       0.18f, 0.58f, 0.72f, 1.00f)
        style.setColor(ImGuiCol.Button,             0.10f, 0.35f, 0.45f, 1.00f)
        style.setColor(ImGuiCol.ButtonHovered,      0.15f, 0.50f, 0.62f, 1.00f)
        style.setColor(ImGuiCol.ButtonActive,       0.20f, 0.60f, 0.76f, 1.00f)
        style.setColor(ImGuiCol.FrameBg,            0.05f, 0.15f, 0.20f, 1.00f)
        style.setColor(ImGuiCol.FrameBgHovered,     0.08f, 0.24f, 0.32f, 1.00f)
        style.setColor(ImGuiCol.FrameBgActive,      0.10f, 0.30f, 0.40f, 1.00f)
        style.setColor(ImGuiCol.SliderGrab,         0.20f, 0.65f, 0.80f, 1.00f)
        style.setColor(ImGuiCol.SliderGrabActive,   0.25f, 0.78f, 0.95f, 1.00f)
        style.setColor(ImGuiCol.CheckMark,          0.20f, 0.78f, 0.95f, 1.00f)
        style.setColor(ImGuiCol.PlotHistogram,      0.20f, 0.65f, 0.80f, 1.00f)
        style.setColor(ImGuiCol.PlotHistogramHovered, 0.25f, 0.78f, 0.95f, 1.00f)
        style.setColor(ImGuiCol.Tab,                0.07f, 0.22f, 0.30f, 1.00f)
        style.setColor(ImGuiCol.TabHovered,         0.15f, 0.50f, 0.62f, 1.00f)
        style.setColor(ImGuiCol.TabActive,          0.10f, 0.38f, 0.50f, 1.00f)
        style.setColor(ImGuiCol.Separator,          0.20f, 0.55f, 0.65f, 0.60f)
        style.setColor(ImGuiCol.WindowBg,           0.04f, 0.06f, 0.10f, 0.92f)
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
                val steps   = kotlin.math.ceil(timeScale.toDouble()).toInt().coerceAtLeast(1)
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

            // 가이거 계수기 — 신규 핵분열 이벤트에 따라 클릭 사운드 예약
            frameFissions       = (totalFissions - prevTotalFissions).coerceAtLeast(0L)
            prevTotalFissions   = totalFissions
            geigerCounter.triggerFissions(frameFissions)

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

            // FPS calculation + window title update
            frameCount++
            val elapsed = now - lastFpsTime
            if (elapsed >= 1_000_000_000L) {
                fps         = frameCount * 1_000_000_000f / elapsed
                frameCount  = 0
                lastFpsTime = now
                glfwSetWindowTitle(windowHandle,
                    "핵분열 시뮬레이터  |  FPS: %.0f  |  중성자: %,d".format(fps, activeNeutrons))
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
        // Camera: pass UV-space offset and zoom so the vertex shader can pan/zoom the grid
        glUniform2f(glGetUniformLocation(gridProgram, "u_cam_uv_offset"),
            cameraOffsetX / worldWidth, cameraOffsetY / worldHeight)
        glUniform1f(glGetUniformLocation(gridProgram, "u_cam_zoom"), cameraZoom)
        glBindVertexArray(dummyVao)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun renderNeutrons() {
        if (activeNeutrons == 0L) return
        glUseProgram(neutronProgram)
        neutronSSBO.bind()

        // Rebuild orthographic projection from current camera state each frame.
        // The visible world region is [offsetX .. offsetX+W/zoom, offsetY .. offsetY+H/zoom].
        projMatrix.setOrtho(
            cameraOffsetX,
            cameraOffsetX + worldWidth  / cameraZoom,
            cameraOffsetY,
            cameraOffsetY + worldHeight / cameraZoom,
            -1f, 1f
        )

        val matBuf = memAllocFloat(16)
        projMatrix.get(matBuf)
        glUniformMatrix4fv(
            glGetUniformLocation(neutronProgram, "u_projection"), false, matBuf)
        memFree(matBuf)

        // Scale point size proportionally to zoom so neutrons remain visible
        glUniform1f(glGetUniformLocation(neutronProgram, "u_point_size"), 3f * cameraZoom.coerceAtLeast(1f))
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
        handleViewportInput()
        drawPanelSimControl()
        drawPanelEnvironment()
        drawPanelAnalytics()
        drawDebugOverlay()
        drawTooltip()
    }

    // ── Viewport pan & zoom (mouse wheel + middle-button drag) ─
    private fun handleViewportInput() {
        // Scroll wheel zoom — works regardless of ImGui capture state so the
        // simulation viewport can always be zoomed.
        val scrollY = ImGui.getIO().mouseWheel
        if (scrollY != 0f) {
            val factor = if (scrollY > 0f) 1.1f else (1f / 1.1f)

            // Get current cursor position and compute the world point under it.
            // That point must remain fixed on screen after the zoom.
            val mx = DoubleArray(1); val my = DoubleArray(1)
            glfwGetCursorPos(windowHandle, mx, my)
            val screenUvX = mx[0].toFloat() / windowWidth
            val screenUvY = 1f - my[0].toFloat() / windowHeight
            val wx = cameraOffsetX + screenUvX * (worldWidth  / cameraZoom)
            val wy = cameraOffsetY + screenUvY * (worldHeight / cameraZoom)

            cameraZoom = (cameraZoom * factor).coerceIn(0.1f, 20f)

            // Recompute offset so the same world point sits under the cursor
            cameraOffsetX = wx - screenUvX * (worldWidth  / cameraZoom)
            cameraOffsetY = wy - screenUvY * (worldHeight / cameraZoom)
        }

        // Middle-mouse button drag for panning
        val mx = DoubleArray(1); val my = DoubleArray(1)
        glfwGetCursorPos(windowHandle, mx, my)
        val midDown = glfwGetMouseButton(windowHandle, GLFW_MOUSE_BUTTON_MIDDLE) == GLFW_PRESS
        if (midDown) {
            if (middleDragging) {
                // dx/dy in screen pixels; Y is flipped (screen Y-down, world Y-up)
                val dx = (mx[0] - lastMiddleX).toFloat()
                val dy = (my[0] - lastMiddleY).toFloat()
                cameraOffsetX -= dx * (worldWidth  / cameraZoom) / windowWidth
                cameraOffsetY += dy * (worldHeight / cameraZoom) / windowHeight
            }
            middleDragging = true
        } else {
            middleDragging = false
        }
        lastMiddleX = mx[0]
        lastMiddleY = my[0]

        // 우클릭 — 마우스 위치에 중성자 생성 (버튼-다운 에지에서 1회 실행)
        val rightDown = glfwGetMouseButton(windowHandle, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS
        if (rightDown && !prevRightButtonDown && isMouseOnGrid() && !ImGui.getIO().wantCaptureMouse) {
            spawnNeutronsAtPos(200, mouseWorldX, mouseWorldY)
        }
        prevRightButtonDown = rightDown
    }

    // ── Panel 1: Simulation Control ──────────────────────────
    private fun drawPanelSimControl() {
        ImGui.setNextWindowPos(10f, 10f, ImGuiCond.Once)
        ImGui.setNextWindowSize(300f, 195f, ImGuiCond.Once)
        if (ImGui.begin("시뮬레이션 제어")) {
            val buttonWidth    = 130f
            val pauseResumeButtonLabel = if (simPaused) "▶  재개" else "⏸  일시정지"
            if (simPaused) {
                ImGui.pushStyleColor(ImGuiCol.Button,        0.15f, 0.55f, 0.20f, 1f)
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.20f, 0.70f, 0.25f, 1f)
                ImGui.pushStyleColor(ImGuiCol.ButtonActive,  0.25f, 0.80f, 0.30f, 1f)
            }
            if (ImGui.button(pauseResumeButtonLabel, buttonWidth, 0f)) simPaused = !simPaused
            if (simPaused) ImGui.popStyleColor(3)

            ImGui.sameLine()
            if (ImGui.button("↺  전체 초기화", buttonWidth, 0f)) {
                gridSSBO.clearAll()
                counterSSBO.resetCounters()
                prevTotalFissions = 0L
                frameFissions     = 0L
                setupDefaultScene()
            }

            val ts = floatArrayOf(timeScale)
            if (ImGui.sliderFloat("시간 배율", ts, 0.1f, 100f, "%.1fx")) timeScale = ts[0]

            val steps = kotlin.math.ceil(timeScale.toDouble()).toInt().coerceAtLeast(1)
            ImGui.textColored(0.65f, 0.85f, 1.00f, 1f, "하위 스텝: $steps  |  GPU: %.2f ms".format(gpuTimeMs))

            // 상태 표시
            if (simPaused) ImGui.textColored(1f, 0.8f, 0.2f, 1f, "● 일시정지")
            else           ImGui.textColored(0.2f, 1f, 0.4f, 1f, "● 실행 중")
        }
        ImGui.end()
    }

    // ── Panel 2: Environment Setup ───────────────────────────
    private val brushLabels = arrayOf("U-235", "U-238", "Pu-239", "제어봉", "경수", "흑연", "벽")
    private val brushStructureMap = intArrayOf(0, 0, 0, 4, 1, 3, 6)

    private fun drawPanelEnvironment() {
        ImGui.setNextWindowPos(10f, 220f, ImGuiCond.Once)
        ImGui.setNextWindowSize(300f, 260f, ImGuiCond.Once)
        if (ImGui.begin("환경 설정")) {
            val bm = ImInt(brushMode)
            if (ImGui.combo("브러시 모드", bm, brushLabels)) brushMode = bm.get()

            val br = intArrayOf(brushRadius)
            if (ImGui.sliderInt("브러시 반경", br, 1, 30)) brushRadius = br[0]

            val bd = floatArrayOf(brushDensity)
            if (ImGui.sliderFloat("밀도 (kg)", bd, 1f, 100f, "%.1f kg")) brushDensity = bd[0]

            val cm = floatArrayOf(criticalMass)
            if (ImGui.sliderFloat("임계 질량 임계값", cm, 10f, 200f, "%.0f kg")) criticalMass = cm[0]

            ImGui.separator()
            if (ImGui.button("10,000 중성자 생성", 240f, 0f)) {
                spawnNeutronsAtCenter(10_000)
            }
            if (ImGui.button("100,000 중성자 생성", 240f, 0f)) {
                spawnNeutronsAtCenter(100_000)
            }

            ImGui.separator()
            ImGui.text("좌클릭: 소재 칠하기   우클릭: 중성자 생성")
            if (isMouseOnGrid() && ImGui.isMouseDown(ImGuiMouseButton.Left) && !ImGui.getIO().wantCaptureMouse) {
                paintBrush()
            }
        }
        ImGui.end()
    }

    // ── Panel 3: Analytics & Sensors ────────────────────────
    private val renderModeLabels = arrayOf("입자 (소재)", "방사선 열지도", "온도 열지도")

    private fun drawPanelAnalytics() {
        ImGui.setNextWindowPos(10f, 490f, ImGuiCond.Once)
        ImGui.setNextWindowSize(300f, 265f, ImGuiCond.Once)
        if (ImGui.begin("분석 및 센서")) {
            // 중성자 수
            val neutronFrac = activeNeutrons.toFloat() / NeutronSSBO.MAX_NEUTRONS_CONST
            val (nr, ng, nb) = when {
                neutronFrac > 0.80f -> Triple(1.00f, 0.30f, 0.20f)
                neutronFrac > 0.50f -> Triple(1.00f, 0.75f, 0.10f)
                else                -> Triple(0.70f, 1.00f, 0.70f)
            }
            ImGui.textColored(nr, ng, nb, 1f,
                "중성자: %,d / %,d".format(activeNeutrons, NeutronSSBO.MAX_NEUTRONS_CONST))
            ImGui.progressBar(neutronFrac, 280f, 10f, "")

            ImGui.textColored(0.90f, 0.85f, 0.50f, 1f, "총 핵분열: %,d".format(totalFissions))

            ImGui.separator()
            // ── 가이거 계수기 ──────────────────────────────────────
            if (geigerCounter.isAudioAvailable) {
                ImGui.textColored(0.40f, 1.00f, 0.50f, 1f, "가이거 계수기: 활성")
            } else {
                ImGui.textColored(0.60f, 0.60f, 0.60f, 1f, "가이거 계수기: 비활성 (오디오 없음)")
            }

            // 핵분열 속도 강도
            val intensity = (frameFissions.toFloat() / 500f).coerceIn(0f, 1f)
            val (fr, fg, fb) = when {
                intensity > 0.75f -> Triple(1.0f, 0.25f, 0.10f)
                intensity > 0.40f -> Triple(1.0f, 0.65f, 0.10f)
                else              -> Triple(0.85f, 0.85f, 0.85f)
            }
            ImGui.textColored(fr, fg, fb, 1f, "프레임 핵분열: %,d".format(frameFissions))
            ImGui.progressBar(intensity, 280f, 10f, "")

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
        ImGui.setNextWindowSize(260f, 140f, ImGuiCond.Always)
        ImGui.setNextWindowBgAlpha(0.70f)
        val flags = ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoInputs or
                    ImGuiWindowFlags.NoNav or ImGuiWindowFlags.NoMove
        if (ImGui.begin("##Debug", flags)) {
            // FPS — 색상: 60+ 초록, 30-60 노랑, 30 미만 빨강
            val (fr, fg, fb) = when {
                fps >= 60f -> Triple(0.30f, 1.00f, 0.40f)
                fps >= 30f -> Triple(1.00f, 0.80f, 0.20f)
                else       -> Triple(1.00f, 0.35f, 0.20f)
            }
            ImGui.textColored(fr, fg, fb, 1f, "FPS: %.1f  (%.2f ms)".format(fps, if (fps > 0) 1000f / fps else 0f))
            ImGui.textColored(0.65f, 0.85f, 1.00f, 1f, "GPU: %.2f ms".format(gpuTimeMs))
            ImGui.separator()
            ImGui.text("중성자: %,d".format(activeNeutrons))
            ImGui.text("핵분열: %,d".format(totalFissions))
            ImGui.text("속도:   %.1fx  (%d 스텝)".format(
                timeScale, kotlin.math.ceil(timeScale.toDouble()).toInt()))
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
            ImGui.text("[셀 %d, %d]".format(gcx, gcy))
            ImGui.text("소재: ${structureName(cell.structureType)}")

            val tempStr = if (cell.temperature > 2000f) "위험" else "%.1f °C".format(cell.temperature)
            ImGui.text("온도: $tempStr")

            if (cell.u235  > 0f) ImGui.textColored(1f, 1f, 0f, 1f, "U-235:  %.2f kg".format(cell.u235))
            if (cell.u238  > 0f) ImGui.text("U-238:  %.2f kg".format(cell.u238))
            if (cell.pu239 > 0f) ImGui.textColored(1f, 0.5f, 0f, 1f, "Pu-239: %.2f kg".format(cell.pu239))
            if (cell.xe135 > 0.0001f) ImGui.textColored(0.8f, 0f, 0.8f, 1f,
                "Xe-135: %.4f ppm".format(cell.xe135))
            ImGui.text("방사선량: %.2f Sv/h".format(cell.radiationDose))

            // Neutron tooltip (GPU-picked)
            if (hoveredNeutronIdx >= 0) {
                val n = neutronSSBO.readNeutron(hoveredNeutronIdx)
                if (n != null) {
                    ImGui.separator()
                    ImGui.text("[중성자 ID: #%d]".format(hoveredNeutronIdx))
                    val state = if (n.energy >= 15f) "고속" else "열중성자"
                    ImGui.text("상태: $state")
                    ImGui.text("에너지: %.2f MeV".format(n.energy))
                    val speed = kotlin.math.sqrt((n.velX * n.velX + n.velY * n.velY).toDouble()).toFloat()
                    ImGui.text("속도: %.2f  [vx: %.1f, vy: %.1f]".format(speed, n.velX, n.velY))
                }
            }
            ImGui.endTooltip()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private fun structureName(t: Int) = when (t) {
        1    -> "경수"
        2    -> "중수"
        3    -> "흑연"
        4    -> "제어봉"
        5    -> "반사체"
        6    -> "벽"
        else -> "진공/연료"
    }

    private fun updateMouseWorldPos() {
        val mx = DoubleArray(1); val my = DoubleArray(1)
        glfwGetCursorPos(windowHandle, mx, my)
        // Screen UV [0..1], Y flipped so that Y=0 is the bottom of the viewport
        val screenUvX = mx[0].toFloat() / windowWidth
        val screenUvY = 1f - my[0].toFloat() / windowHeight
        // Inverse-project through camera: visible world region is
        //   X: [cameraOffsetX .. cameraOffsetX + worldWidth/cameraZoom]
        //   Y: [cameraOffsetY .. cameraOffsetY + worldHeight/cameraZoom]
        mouseWorldX = cameraOffsetX + screenUvX * (worldWidth  / cameraZoom)
        mouseWorldY = cameraOffsetY + screenUvY * (worldHeight / cameraZoom)
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
        spawnNeutronsAtPos(count, cx, cy)
    }

    private fun spawnNeutronsAtPos(count: Int, worldX: Float, worldY: Float) {
        // Current active count is the write offset (slots are reused by GPU)
        val offset = activeNeutrons.toInt().coerceAtMost(NeutronSSBO.MAX_NEUTRONS_CONST - count)
        if (offset >= 0) {
            neutronSSBO.activateNeutrons(offset, count.coerceAtMost(NeutronSSBO.MAX_NEUTRONS_CONST - offset), worldX, worldY)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Cleanup
    // ═══════════════════════════════════════════════════════════

    fun cleanup() {
        geigerCounter.cleanup()

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
