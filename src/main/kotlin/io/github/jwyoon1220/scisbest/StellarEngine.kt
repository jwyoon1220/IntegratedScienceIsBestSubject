package io.github.jwyoon1220.scisbest

import imgui.ImGui
import imgui.ImGuiIO
import imgui.flag.*
import imgui.gl3.ImGuiImplGl3
import imgui.glfw.ImGuiImplGlfw
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL43.*
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.*
import kotlin.random.Random

// ─── Enums ────────────────────────────────────────────────────────────────────

enum class StellarState {
    NEBULA_NORMAL, NEBULA_CONTRACTING, PROTO_DISK,
    PROTO_STELLAR_SYSTEM, PROTOSTAR, MAIN_SEQUENCE,
    RED_GIANT, SUPERGIANT, WHITE_DWARF,
    SUPERNOVA, BLACK_DWARF, BLACK_HOLE
}

enum class BodyType {
    STAR, PLANET, PLANETESIMAL, NEBULA_PARTICLE
}

// ─── StellarBody ──────────────────────────────────────────────────────────────

class StellarBody(
    var id: Int = 0,
    var type: BodyType = BodyType.STAR,
    var state: StellarState = StellarState.MAIN_SEQUENCE,
    var x: Float = 0f, var y: Float = 0f, var z: Float = 0f,
    var vx: Float = 0f, var vy: Float = 0f, var vz: Float = 0f,
    var ax: Float = 0f, var ay: Float = 0f, var az: Float = 0f,
    var mass: Double = 1.0,
    var radius: Float = 0.1f,
    var temperature: Float = 5778f,
    var luminosity: Double = 1.0,
    var age: Double = 0.0,
    var coreH: Float = 0.7f,
    var coreHe: Float = 0.28f,
    var coreC: Float = 0.01f,
    var coreO: Float = 0.01f,
    var coreSi: Float = 0f,
    var coreFe: Float = 0f,
    var mainSeqLifetime: Double = 1e10,
    var supernovaTimer: Double = 5e6,
    var parentId: Int = -1,
    var isEarth: Boolean = false,
    var earthBlueProgress: Float = 0f,
    var waterUV: Float = 0f,
    var landUV: Float = 0f
)

// ─── StellarParticle ──────────────────────────────────────────────────────────

class StellarParticle(
    var px: Float, var py: Float, var pz: Float, var life: Float,
    var vx: Float, var vy: Float, var vz: Float, var size: Float,
    var cr: Float, var cg: Float, var cb: Float, var type: Float
)

// ─── Barnes-Hut Octree ────────────────────────────────────────────────────────

class OctreeNode(val cx: Float, val cy: Float, val cz: Float, val halfSize: Float) {
    var totalMass = 0f
    var cmx = 0f; var cmy = 0f; var cmz = 0f
    var bodyIdx = -1
    var bodyCount = 0
    val children = arrayOfNulls<OctreeNode>(8)

    private fun childIndex(bx: Float, by: Float, bz: Float): Int {
        var ci = 0
        if (bx >= cx) ci = ci or 1
        if (by >= cy) ci = ci or 2
        if (bz >= cz) ci = ci or 4
        return ci
    }

    fun insert(bodies: List<StellarBody>, idx: Int) {
        val b = bodies[idx]
        bodyCount++

        if (bodyCount == 1) {
            bodyIdx = idx
            totalMass = b.mass.toFloat()
            cmx = b.x; cmy = b.y; cmz = b.z
            return
        }

        val newMass = totalMass + b.mass.toFloat()
        if (newMass > 0f) {
            cmx = (cmx * totalMass + b.x * b.mass.toFloat()) / newMass
            cmy = (cmy * totalMass + b.y * b.mass.toFloat()) / newMass
            cmz = (cmz * totalMass + b.z * b.mass.toFloat()) / newMass
        }
        totalMass = newMass

        if (bodyCount == 2) {
            val prevIdx = bodyIdx
            bodyIdx = -1
            if (halfSize > 1e-8f) pushToChild(bodies, prevIdx)
        }

        if (halfSize > 1e-8f) pushToChild(bodies, idx)
    }

    private fun pushToChild(bodies: List<StellarBody>, idx: Int) {
        val b = bodies[idx]
        val ci = childIndex(b.x, b.y, b.z)
        if (children[ci] == null) {
            val hs = halfSize * 0.5f
            val nx = cx + (if (ci and 1 != 0) hs else -hs)
            val ny = cy + (if (ci and 2 != 0) hs else -hs)
            val nz = cz + (if (ci and 4 != 0) hs else -hs)
            children[ci] = OctreeNode(nx, ny, nz, hs)
        }
        children[ci]!!.insert(bodies, idx)
    }

    fun computeAccel(px: Float, py: Float, pz: Float, theta: Float, G: Float): Triple<Float, Float, Float> {
        if (bodyCount == 0 || totalMass == 0f) return Triple(0f, 0f, 0f)

        val dx = cmx - px
        val dy = cmy - py
        val dz = cmz - pz
        val distSq = dx * dx + dy * dy + dz * dz
        if (distSq < 1e-12f) return Triple(0f, 0f, 0f)
        val dist = sqrt(distSq)

        if (bodyIdx >= 0 || (halfSize * 2f / dist) < theta) {
            val softening = 0.001f
            val dSoft = sqrt(distSq + softening * softening)
            val accel = G * totalMass / (dSoft * dSoft * dSoft)
            return Triple(accel * dx, accel * dy, accel * dz)
        }

        var ax = 0f; var ay = 0f; var az = 0f
        for (child in children) {
            if (child != null) {
                val (cax, cay, caz) = child.computeAccel(px, py, pz, theta, G)
                ax += cax; ay += cay; az += caz
            }
        }
        return Triple(ax, ay, az)
    }
}

// ─── StellarEngine ────────────────────────────────────────────────────────────

class StellarEngine {

    companion object {
        private const val G_AU = 39.478f   // 4π² AU³/(M_sun·yr²)
        private const val BH_THETA = 0.5f
        private const val MAX_PARTICLES = 500_000

        val STATE_NAMES = arrayOf(
            "성운 (일반)", "성운 (중력수축중)", "원시 원반",
            "원시 항성계", "원시 항성", "주계열성",
            "적색거성", "초거성", "백색 왜성",
            "초신성 폭발", "흑색 왜성", "블랙홀"
        )
    }

    // ── Window / GL ──────────────────────────────────────────────────────────
    private var windowHandle: Long = NULL
    private var windowWidth = 1280
    private var windowHeight = 720
    private var isFullscreen = false
    private val savedWinPos = IntArray(2)
    private val savedWinSize = IntArray(2)

    // ── Camera ────────────────────────────────────────────────────────────────
    private var cameraTheta = 0f
    private var cameraPhi = 0.4f
    private var cameraDistance = 30f
    private var cameraTargetX = 0f
    private var cameraTargetY = 0f
    private var cameraTargetZ = 0f

    // ── Mouse state ───────────────────────────────────────────────────────────
    private var mouseX = 0.0; private var mouseY = 0.0
    private var prevMouseX = 0.0; private var prevMouseY = 0.0
    private var leftDown = false; private var mmDown = false
    private var leftPressX = 0.0; private var leftPressY = 0.0
    private var leftJustReleased = false
    private var scrollDelta = 0.0

    // ── SSBOs ─────────────────────────────────────────────────────────────────
    private lateinit var bodySSBO: BodySSBO
    private lateinit var particleSSBO: StellarParticleSSBO

    // ── Shader programs ───────────────────────────────────────────────────────
    private var backgroundProgram = 0
    private var starProgram = 0
    private var particleProgram = 0
    private var postProcessProgram = 0

    // ── Uniform locations ─────────────────────────────────────────────────────
    private var bgTimeLoc = -1
    private var starViewLoc = -1; private var starProjLoc = -1; private var starTimeLoc = -1
    private var partViewLoc = -1; private var partProjLoc = -1; private var partResLoc = -1
    private var ppSceneLoc = -1; private var ppResLoc = -1; private var ppTimeLoc = -1
    private var ppNumBHLoc = -1; private var ppBHScreenLoc = -1
    private var ppBHRsLoc = -1; private var ppBHMassLoc = -1

    // ── VAO / FBO ─────────────────────────────────────────────────────────────
    private var dummyVao = 0
    private var fbo = 0
    private var fboColorTex = 0
    private var fboDepthRbo = 0

    // ── ImGui ─────────────────────────────────────────────────────────────────
    private lateinit var imGuiGlfw: ImGuiImplGlfw
    private lateinit var imGuiGl3: ImGuiImplGl3

    // ── Matrices ──────────────────────────────────────────────────────────────
    private val viewMatrix = Matrix4f()
    private val projMatrix = Matrix4f()
    private val matBuf = FloatArray(16)

    // ── Simulation ────────────────────────────────────────────────────────────
    private val bodies = mutableListOf<StellarBody>()
    private val particles = mutableListOf<StellarParticle>()
    private var nextBodyId = 0
    private var selectedBodyIdx = -1
    private var simPaused = false
    private var simTimeYears = 0.0
    private var simTimeScaleYears = 1e6  // years per real second

    // ── Stats ─────────────────────────────────────────────────────────────────
    private var running = true
    private var frameCount = 0
    private var lastFpsTime = 0L
    private var fps = 0f
    private var gpuTimeMs = 0f
    private val timerQuery = IntArray(1)

    private val rng = Random(12345)

    // ═══════════════════════════════════════════════════════════════════════════
    //  Public entry points
    // ═══════════════════════════════════════════════════════════════════════════

    fun init() {
        initGlfw()
        initOpenGL()
        initImGui()
        initSSBOs()
        initShaders()
        initFBO()
        loadSolarNebulaPreset()
    }

    fun run() {
        lastFpsTime = System.nanoTime()
        var lastTime = System.nanoTime()

        while (running && !glfwWindowShouldClose(windowHandle)) {
            val now = System.nanoTime()
            val realDt = ((now - lastTime) / 1e9).toFloat().coerceAtMost(0.05f)
            lastTime = now

            // FPS counter
            frameCount++
            if (now - lastFpsTime >= 1_000_000_000L) {
                fps = frameCount.toFloat() / ((now - lastFpsTime) / 1e9f)
                frameCount = 0
                lastFpsTime = now
            }

            val mouseXBefore = mouseX
            val mouseYBefore = mouseY
            glfwPollEvents()
            val dMouseX = (mouseX - mouseXBefore).toFloat()
            val dMouseY = (mouseY - mouseYBefore).toFloat()

            handleInput(dMouseX, dMouseY)

            if (!simPaused) {
                val simDt = realDt * simTimeScaleYears.toFloat()
                update(simDt)
                simTimeYears += simDt.toDouble()
            }

            uploadSSBOs()

            // GPU timer
            glBeginQuery(GL_TIME_ELAPSED, timerQuery[0])
            render()
            glEndQuery(GL_TIME_ELAPSED)

            imGuiGlfw.newFrame()
            ImGui.newFrame()
            drawImGui()
            ImGui.render()
            imGuiGl3.renderDrawData(ImGui.getDrawData())

            if (glGetQueryObjecti(timerQuery[0], GL_QUERY_RESULT_AVAILABLE) != 0) {
                gpuTimeMs = glGetQueryObjectui64(timerQuery[0], GL_QUERY_RESULT) / 1_000_000f
            }

            glfwSwapBuffers(windowHandle)
        }
        cleanup()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Initialisation
    // ═══════════════════════════════════════════════════════════════════════════

    private fun initGlfw() {
        GLFWErrorCallback.createPrint(System.err).set()
        check(glfwInit()) { "Failed to init GLFW" }

        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)

        windowHandle = glfwCreateWindow(windowWidth, windowHeight,
            "항성 진화 시뮬레이터  [F11=전체화면]", NULL, NULL)
        check(windowHandle != NULL) { "Failed to create GLFW window" }

        glfwGetVideoMode(glfwGetPrimaryMonitor())?.let { vm ->
            glfwSetWindowPos(windowHandle, (vm.width() - windowWidth) / 2, (vm.height() - windowHeight) / 2)
        }

        glfwMakeContextCurrent(windowHandle)
        glfwSwapInterval(1)

        glfwSetKeyCallback(windowHandle) { _, key, _, action, _ ->
            if (action == GLFW_PRESS && key == GLFW_KEY_F11) toggleFullscreen()
            if (action == GLFW_PRESS && key == GLFW_KEY_ESCAPE) selectedBodyIdx = -1
        }

        glfwSetFramebufferSizeCallback(windowHandle) { _, w, h ->
            windowWidth = w.coerceAtLeast(1); windowHeight = h.coerceAtLeast(1)
            glViewport(0, 0, windowWidth, windowHeight)
            resizeFBO(windowWidth, windowHeight)
        }

        glfwSetCursorPosCallback(windowHandle) { _, x, y ->
            mouseX = x; mouseY = y
        }

        glfwSetMouseButtonCallback(windowHandle) { _, button, action, _ ->
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                if (action == GLFW_PRESS) { leftDown = true; leftPressX = mouseX; leftPressY = mouseY }
                else if (action == GLFW_RELEASE) { leftDown = false; leftJustReleased = true }
            }
            if (button == GLFW_MOUSE_BUTTON_MIDDLE) mmDown = (action == GLFW_PRESS)
        }

        glfwSetScrollCallback(windowHandle) { _, _, yOff -> scrollDelta += yOff }

        glfwShowWindow(windowHandle)
    }

    private fun toggleFullscreen() {
        val monitor = glfwGetPrimaryMonitor()
        val vm = glfwGetVideoMode(monitor) ?: return
        if (!isFullscreen) {
            val px = IntArray(1); val py = IntArray(1)
            glfwGetWindowPos(windowHandle, px, py)
            savedWinPos[0] = px[0]; savedWinPos[1] = py[0]
            val sw = IntArray(1); val sh = IntArray(1)
            glfwGetWindowSize(windowHandle, sw, sh)
            savedWinSize[0] = sw[0]; savedWinSize[1] = sh[0]
            glfwSetWindowMonitor(windowHandle, monitor, 0, 0, vm.width(), vm.height(), vm.refreshRate())
        } else {
            glfwSetWindowMonitor(windowHandle, NULL,
                savedWinPos[0], savedWinPos[1],
                savedWinSize[0].coerceAtLeast(1280), savedWinSize[1].coerceAtLeast(720), 0)
        }
        isFullscreen = !isFullscreen
        glfwSwapInterval(1)
    }

    private fun initOpenGL() {
        GL.createCapabilities()
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_PROGRAM_POINT_SIZE)
        glEnable(GL_DEPTH_TEST)
        glViewport(0, 0, windowWidth, windowHeight)
        glClearColor(0f, 0f, 0f, 1f)

        dummyVao = glGenVertexArrays()
        glBindVertexArray(dummyVao)

        glGenQueries(timerQuery)
    }

    private fun initImGui() {
        ImGui.createContext()
        val io: ImGuiIO = ImGui.getIO()
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard)

        val fontBytes = StellarEngine::class.java.getResourceAsStream("/fonts/NanumGothic.ttf")?.readBytes()
        if (fontBytes != null) {
            io.fonts.addFontFromMemoryTTF(fontBytes, 16f, io.fonts.getGlyphRangesKorean())
        } else {
            System.err.println("[StellarEngine] 경고: 한글 폰트를 찾을 수 없습니다.")
            io.fonts.addFontDefault()
        }

        applyImGuiStyle()

        imGuiGlfw = ImGuiImplGlfw()
        imGuiGlfw.init(windowHandle, true)
        imGuiGl3 = ImGuiImplGl3()
        imGuiGl3.init("#version 430 core")
    }

    private fun applyImGuiStyle() {
        ImGui.styleColorsDark()
        val s = ImGui.getStyle()
        s.setColor(ImGuiCol.WindowBg,        0.04f, 0.05f, 0.10f, 0.92f)
        s.setColor(ImGuiCol.TitleBgActive,   0.08f, 0.10f, 0.30f, 1.00f)
        s.setColor(ImGuiCol.TitleBg,         0.05f, 0.06f, 0.18f, 1.00f)
        s.setColor(ImGuiCol.Button,          0.10f, 0.15f, 0.40f, 1.00f)
        s.setColor(ImGuiCol.ButtonHovered,   0.15f, 0.22f, 0.55f, 1.00f)
        s.setColor(ImGuiCol.ButtonActive,    0.20f, 0.30f, 0.70f, 1.00f)
        s.setColor(ImGuiCol.FrameBg,         0.05f, 0.06f, 0.18f, 1.00f)
        s.setColor(ImGuiCol.SliderGrab,      0.30f, 0.50f, 0.90f, 1.00f)
        s.setColor(ImGuiCol.PlotHistogram,   0.20f, 0.55f, 0.90f, 1.00f)
        s.setColor(ImGuiCol.Header,          0.12f, 0.18f, 0.45f, 0.80f)
        s.setColor(ImGuiCol.HeaderHovered,   0.18f, 0.28f, 0.60f, 1.00f)
        s.setColor(ImGuiCol.Separator,       0.20f, 0.30f, 0.60f, 0.60f)
        s.windowRounding = 6f
        s.framePadding.x = 6f; s.framePadding.y = 4f
    }

    private fun initSSBOs() {
        bodySSBO = BodySSBO()
        particleSSBO = StellarParticleSSBO()
    }

    private fun initShaders() {
        backgroundProgram = ShaderUtils.renderProgram("shaders/background.vert", "shaders/background.frag")
        starProgram       = ShaderUtils.renderProgram("shaders/star.vert",        "shaders/star.frag")
        particleProgram   = ShaderUtils.renderProgram("shaders/particle.vert",    "shaders/particle.frag")
        postProcessProgram = ShaderUtils.renderProgram("shaders/postprocess.vert", "shaders/blackhole.frag")

        bgTimeLoc   = glGetUniformLocation(backgroundProgram, "u_time")

        starViewLoc = glGetUniformLocation(starProgram, "u_view")
        starProjLoc = glGetUniformLocation(starProgram, "u_proj")
        starTimeLoc = glGetUniformLocation(starProgram, "u_time")

        partViewLoc = glGetUniformLocation(particleProgram, "u_view")
        partProjLoc = glGetUniformLocation(particleProgram, "u_proj")
        partResLoc  = glGetUniformLocation(particleProgram, "u_resolution")

        ppSceneLoc    = glGetUniformLocation(postProcessProgram, "u_scene")
        ppResLoc      = glGetUniformLocation(postProcessProgram, "u_resolution")
        ppTimeLoc     = glGetUniformLocation(postProcessProgram, "u_time")
        ppNumBHLoc    = glGetUniformLocation(postProcessProgram, "u_num_bh")
        ppBHScreenLoc = glGetUniformLocation(postProcessProgram, "u_bh_screen")
        ppBHRsLoc     = glGetUniformLocation(postProcessProgram, "u_bh_rs")
        ppBHMassLoc   = glGetUniformLocation(postProcessProgram, "u_bh_mass")
    }

    private fun initFBO() {
        fbo = glGenFramebuffers()
        fboColorTex = glGenTextures()
        fboDepthRbo = glGenRenderbuffers()
        resizeFBO(windowWidth, windowHeight)
    }

    private fun resizeFBO(w: Int, h: Int) {
        glBindTexture(GL_TEXTURE_2D, fboColorTex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_FLOAT, 0L)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glBindTexture(GL_TEXTURE_2D, 0)

        glBindRenderbuffer(GL_RENDERBUFFER, fboDepthRbo)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h)
        glBindRenderbuffer(GL_RENDERBUFFER, 0)

        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboColorTex, 0)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, fboDepthRbo)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Input
    // ═══════════════════════════════════════════════════════════════════════════

    private fun handleInput(dMouseX: Float, dMouseY: Float) {
        val io = ImGui.getIO()

        if (leftJustReleased) {
            leftJustReleased = false
            if (!io.wantCaptureMouse) {
                val dragDist = sqrt((mouseX - leftPressX).pow(2) + (mouseY - leftPressY).pow(2))
                if (dragDist < 5.0) handleBodySelection(mouseX.toFloat(), mouseY.toFloat())
            }
        }

        if (!io.wantCaptureMouse) {
            if (leftDown && (dMouseX != 0f || dMouseY != 0f)) {
                cameraTheta -= dMouseX * 0.005f
                cameraPhi = (cameraPhi + dMouseY * 0.005f).coerceIn(-PI.toFloat() * 0.44f, PI.toFloat() * 0.44f)
            }

            if (mmDown && (dMouseX != 0f || dMouseY != 0f)) {
                val eyeX = cameraTargetX + cameraDistance * cos(cameraPhi) * sin(cameraTheta)
                val eyeY = cameraTargetY + cameraDistance * sin(cameraPhi)
                val eyeZ = cameraTargetZ + cameraDistance * cos(cameraPhi) * cos(cameraTheta)
                val forward = Vector3f(cameraTargetX - eyeX, cameraTargetY - eyeY, cameraTargetZ - eyeZ).normalize()
                val worldUp = Vector3f(0f, 1f, 0f)
                val right = Vector3f(forward).cross(worldUp).normalize()
                val camUp = Vector3f(right).cross(forward).normalize()
                val scale = cameraDistance * 0.001f
                cameraTargetX -= right.x * dMouseX * scale + camUp.x * dMouseY * scale
                cameraTargetY -= right.y * dMouseX * scale + camUp.y * dMouseY * scale
                cameraTargetZ -= right.z * dMouseX * scale + camUp.z * dMouseY * scale
            }

            if (scrollDelta != 0.0) {
                cameraDistance *= (1f - scrollDelta.toFloat() * 0.1f)
                cameraDistance = cameraDistance.coerceIn(0.001f, 5000f)
            }
        }
        scrollDelta = 0.0
    }

    private fun handleBodySelection(sx: Float, sy: Float) {
        val ndcX = (2f * sx / windowWidth) - 1f
        val ndcY = 1f - (2f * sy / windowHeight)

        val vpMatrix = Matrix4f(projMatrix).mul(viewMatrix)
        val invVP = Matrix4f(vpMatrix).invert()

        val near4 = Vector4f(ndcX, ndcY, -1f, 1f)
        val far4  = Vector4f(ndcX, ndcY,  1f, 1f)
        invVP.transform(near4); invVP.transform(far4)

        if (near4.w == 0f || far4.w == 0f) return
        near4.div(near4.w); far4.div(far4.w)

        val ox = near4.x; val oy = near4.y; val oz = near4.z
        val dirX = far4.x - ox; val dirY = far4.y - oy; val dirZ = far4.z - oz
        val dirLen = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
        if (dirLen < 1e-10f) return
        val dx = dirX / dirLen; val dy = dirY / dirLen; val dz = dirZ / dirLen

        var bestT = Float.MAX_VALUE
        var bestIdx = -1

        for ((i, b) in bodies.withIndex()) {
            val r = b.radius.coerceAtLeast(0.05f)
            val ocX = b.x - ox; val ocY = b.y - oy; val ocZ = b.z - oz
            val tca = ocX * dx + ocY * dy + ocZ * dz
            val d2 = ocX * ocX + ocY * ocY + ocZ * ocZ - tca * tca
            if (d2 > r * r) continue
            val thc = sqrt((r * r - d2).coerceAtLeast(0f))
            val t = tca - thc
            if (t > 0f && t < bestT) { bestT = t; bestIdx = i }
        }
        selectedBodyIdx = bestIdx
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Simulation update
    // ═══════════════════════════════════════════════════════════════════════════

    private fun update(dtYears: Float) {
        if (dtYears <= 0f) return
        updatePhysics(dtYears)
        updateStellarEvolution(dtYears)
        updatePlanetaryFormation(dtYears)
        updateParticles(dtYears)
        updateBodyRadii()
        clampBodyCount()
    }

    private fun buildOctree(): OctreeNode? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var count = 0

        for (b in bodies) {
            if (b.type == BodyType.NEBULA_PARTICLE || b.mass <= 0.0) continue
            if (b.x < minX) minX = b.x; if (b.x > maxX) maxX = b.x
            if (b.y < minY) minY = b.y; if (b.y > maxY) maxY = b.y
            if (b.z < minZ) minZ = b.z; if (b.z > maxZ) maxZ = b.z
            count++
        }
        if (count == 0) return null

        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        val hs = (maxOf(maxX - minX, maxY - minY, maxZ - minZ) * 0.505f + 0.01f)

        val root = OctreeNode(cx, cy, cz, hs)
        for ((i, b) in bodies.withIndex()) {
            if (b.type != BodyType.NEBULA_PARTICLE && b.mass > 0.0) root.insert(bodies, i)
        }
        return root
    }

    private fun updatePhysics(dtYears: Float) {
        val tree = buildOctree() ?: return

        for (b in bodies) {
            if (b.type == BodyType.NEBULA_PARTICLE || b.mass <= 0.0) continue
            val (accX, accY, accZ) = tree.computeAccel(b.x, b.y, b.z, BH_THETA, G_AU)
            b.ax = accX; b.ay = accY; b.az = accZ
        }

        for (b in bodies) {
            if (b.type == BodyType.NEBULA_PARTICLE || b.mass <= 0.0) continue
            b.vx += b.ax * dtYears; b.vy += b.ay * dtYears; b.vz += b.az * dtYears
            b.x  += b.vx * dtYears; b.y  += b.vy * dtYears; b.z  += b.vz * dtYears
            b.age += dtYears.toDouble()
        }
    }

    private fun updateStellarEvolution(dtYears: Float) {
        val toAdd = mutableListOf<StellarBody>()

        for (b in bodies) {
            when (b.state) {
                StellarState.NEBULA_NORMAL -> {
                    var neighborMass = 0.0
                    for (other in bodies) {
                        if (other === b) continue
                        if (other.type != BodyType.NEBULA_PARTICLE && other.mass > 0.0) {
                            val dx = other.x - b.x; val dy = other.y - b.y; val dz = other.z - b.z
                            if (dx*dx + dy*dy + dz*dz < 25f) neighborMass += other.mass
                        }
                    }
                    if (neighborMass + b.mass > 0.5) b.state = StellarState.NEBULA_CONTRACTING
                }
                StellarState.NEBULA_CONTRACTING -> {
                    b.age += dtYears.toDouble()
                    if (b.age > 1e7) {
                        b.state = StellarState.PROTO_DISK
                        val protostar = StellarBody(
                            id = nextBodyId++, type = BodyType.STAR,
                            state = StellarState.PROTOSTAR,
                            x = b.x, y = b.y, z = b.z,
                            vx = b.vx, vy = b.vy, vz = b.vz,
                            mass = b.mass * 0.8,
                            temperature = 2000f, luminosity = 0.5,
                            radius = 0.1f, age = 0.0
                        )
                        toAdd.add(protostar)
                        b.mass *= 0.2
                        spawnDiskParticles(b, 30)
                    }
                }
                StellarState.PROTO_DISK -> {
                    if (b.age > 1.5e7) b.state = StellarState.PROTO_STELLAR_SYSTEM
                }
                StellarState.PROTO_STELLAR_SYSTEM -> {
                    if (b.age > 5e7) b.state = StellarState.NEBULA_NORMAL
                }
                StellarState.PROTOSTAR -> {
                    if (b.age > 1e5) {
                        b.state = StellarState.MAIN_SEQUENCE
                        b.temperature = starTemperature(b.mass)
                        b.luminosity = starLuminosity(b.mass)
                        b.mainSeqLifetime = mainSeqLifetime(b.mass)
                        b.coreH = 0.70f; b.coreHe = 0.28f
                    }
                }
                StellarState.MAIN_SEQUENCE -> {
                    val burnRate = (1.0 / b.mainSeqLifetime).toFloat()
                    b.coreH = (b.coreH - burnRate * dtYears).coerceAtLeast(0f)
                    b.coreHe = (b.coreHe + burnRate * 0.98f * dtYears).coerceAtMost(1f)
                    b.temperature = starTemperature(b.mass)
                    b.luminosity = starLuminosity(b.mass)
                    if (b.coreH < 0.01f) {
                        if (b.mass < 8.0) {
                            b.state = StellarState.RED_GIANT
                            b.temperature = 3500f; b.coreHe = 0.85f
                        } else {
                            b.state = StellarState.SUPERGIANT
                            b.temperature = 4500f; b.coreHe = 0.80f
                        }
                    }
                }
                StellarState.RED_GIANT -> {
                    val rate = (1.0 / (b.mainSeqLifetime * 0.1)).toFloat()
                    b.coreHe = (b.coreHe - rate * dtYears).coerceAtLeast(0f)
                    b.coreC  = (b.coreC  + rate * 0.5f * dtYears).coerceAtMost(1f)
                    b.coreO  = (b.coreO  + rate * 0.5f * dtYears).coerceAtMost(1f)
                    b.temperature = 3500f
                    if (b.coreHe < 0.01f) {
                        if (b.mass < 8.0) {
                            b.state = StellarState.WHITE_DWARF
                            b.temperature = 50000f; b.mass *= 0.6
                        } else {
                            transitionToSupernova(b)
                        }
                    }
                }
                StellarState.SUPERGIANT -> {
                    val rate = (1.0 / (b.mainSeqLifetime * 0.05)).toFloat()
                    b.coreC  = (b.coreC  + rate * 0.2f * dtYears).coerceAtMost(1f)
                    b.coreO  = (b.coreO  + rate * 0.3f * dtYears).coerceAtMost(1f)
                    b.coreSi = (b.coreSi + rate * 0.3f * dtYears).coerceAtMost(1f)
                    b.coreFe = (b.coreFe + rate * 0.2f * dtYears).coerceAtMost(1f)
                    if (b.coreFe > 0.9f) transitionToSupernova(b)
                }
                StellarState.SUPERNOVA -> {
                    b.supernovaTimer -= dtYears.toDouble()
                    if (b.supernovaTimer <= 0.0) {
                        if (b.mass > 20.0) {
                            b.state = StellarState.BLACK_HOLE
                            b.temperature = 0f; b.luminosity = 0.0
                        } else {
                            b.state = StellarState.WHITE_DWARF
                            b.temperature = 80000f; b.mass = minOf(b.mass * 0.3, 1.4)
                        }
                    }
                }
                StellarState.WHITE_DWARF -> {
                    b.temperature = (b.temperature - 0.001f * dtYears).coerceAtLeast(2500f)
                    if (b.age > 1e13) b.state = StellarState.BLACK_DWARF
                }
                StellarState.BLACK_DWARF -> {
                    b.temperature = 0f; b.luminosity = 0.0
                }
                StellarState.BLACK_HOLE -> {
                    b.temperature = 0f; b.luminosity = 0.0
                }
                else -> {}
            }
        }

        bodies.addAll(toAdd)
    }

    private fun transitionToSupernova(b: StellarBody) {
        b.state = StellarState.SUPERNOVA
        b.supernovaTimer = 5e6
        b.temperature = 100000f
        b.luminosity = 1e9
        spawnSupernovaParticles(b)
    }

    private fun spawnSupernovaParticles(b: StellarBody) {
        repeat(800) {
            val phi   = acos(1f - 2f * rng.nextFloat())
            val theta = rng.nextFloat() * 2f * PI.toFloat()
            val speed = 1f + rng.nextFloat() * 19f
            val svx = speed * sin(phi) * cos(theta)
            val svy = speed * sin(phi) * sin(theta)
            val svz = speed * cos(phi)
            particles.add(StellarParticle(
                px = b.x, py = b.y, pz = b.z, life = 1f,
                vx = b.vx + svx, vy = b.vy + svy, vz = b.vz + svz,
                size = 3f + rng.nextFloat() * 5f,
                cr = 1f, cg = 0.4f + rng.nextFloat() * 0.4f, cb = 0.1f,
                type = 1f
            ))
        }
    }

    private fun spawnDiskParticles(nebula: StellarBody, count: Int) {
        repeat(count) {
            val angle = rng.nextFloat() * 2f * PI.toFloat()
            val r = 0.3f + rng.nextFloat() * 4f
            val orbitV = if (r > 0f) sqrt(G_AU * nebula.mass.toFloat() / r) else 0f
            particles.add(StellarParticle(
                px = nebula.x + r * cos(angle), py = nebula.y + rng.nextFloat() * 0.2f - 0.1f,
                pz = nebula.z + r * sin(angle), life = 0.5f + rng.nextFloat() * 0.5f,
                vx = nebula.vx - orbitV * sin(angle), vy = 0f,
                vz = nebula.vz + orbitV * cos(angle),
                size = 4f + rng.nextFloat() * 4f,
                cr = 0.6f, cg = 0.4f, cb = 0.8f, type = 0f
            ))
        }
    }

    private fun updatePlanetaryFormation(dtYears: Float) {
        val toRemove = mutableSetOf<Int>()

        for (i in bodies.indices) {
            if (toRemove.contains(i)) continue
            val a = bodies[i]
            if (a.type != BodyType.PLANETESIMAL || a.mass <= 0.0) continue

            for (j in i + 1 until bodies.size) {
                if (toRemove.contains(j)) continue
                val bBody = bodies[j]
                if (bBody.type != BodyType.PLANETESIMAL || bBody.mass <= 0.0) continue

                val dx = a.x - bBody.x; val dy = a.y - bBody.y; val dz = a.z - bBody.z
                val distSq = dx*dx + dy*dy + dz*dz
                val sumR = a.radius + bBody.radius
                if (distSq < sumR * sumR) {
                    // Merge bBody into a (conservation of momentum)
                    val totalM = a.mass + bBody.mass
                    a.x = ((a.x * a.mass + bBody.x * bBody.mass) / totalM).toFloat()
                    a.y = ((a.y * a.mass + bBody.y * bBody.mass) / totalM).toFloat()
                    a.z = ((a.z * a.mass + bBody.z * bBody.mass) / totalM).toFloat()
                    a.vx = ((a.vx * a.mass + bBody.vx * bBody.mass) / totalM).toFloat()
                    a.vy = ((a.vy * a.mass + bBody.vy * bBody.mass) / totalM).toFloat()
                    a.vz = ((a.vz * a.mass + bBody.vz * bBody.mass) / totalM).toFloat()
                    a.mass = totalM
                    toRemove.add(j)

                    if (a.mass > 0.0001 && a.type == BodyType.PLANETESIMAL) {
                        a.type = BodyType.PLANET
                        a.state = StellarState.PROTOSTAR  // reuse as planet placeholder
                        checkEarthZone(a)
                    }
                }
            }
        }

        if (toRemove.isNotEmpty()) {
            val sorted = toRemove.sortedDescending()
            for (idx in sorted) {
                if (selectedBodyIdx == idx) selectedBodyIdx = -1
                else if (selectedBodyIdx > idx) selectedBodyIdx--
                bodies.removeAt(idx)
            }
        }

        // Earth blue progress
        for (b in bodies) {
            if (b.isEarth) {
                b.earthBlueProgress = (b.earthBlueProgress + dtYears.toFloat() / 1e9f).coerceAtMost(1f)
            }
        }
    }

    private fun checkEarthZone(planet: StellarBody) {
        val parentStar = bodies.filter {
            it.type == BodyType.STAR &&
            (it.state == StellarState.MAIN_SEQUENCE || it.state == StellarState.RED_GIANT)
        }.minByOrNull {
            val dx = it.x - planet.x; val dy = it.y - planet.y; val dz = it.z - planet.z
            dx*dx + dy*dy + dz*dz
        } ?: return

        val dx = planet.x - parentStar.x; val dy = planet.y - parentStar.y; val dz = planet.z - parentStar.z
        val dist = sqrt(dx*dx + dy*dy + dz*dz)
        if (dist in 0.7f..1.5f && planet.mass in 1e-6..5e-5) {
            planet.isEarth = true
            planet.waterUV = rng.nextFloat()
            planet.landUV = rng.nextFloat()
        }
    }

    private fun updateParticles(dtYears: Float) {
        val lifeDecay = (dtYears / 1e7f).coerceAtMost(0.01f)
        particles.removeIf { p ->
            p.life -= lifeDecay
            p.px += p.vx * dtYears
            p.py += p.vy * dtYears
            p.pz += p.vz * dtYears
            p.life <= 0f
        }
        if (particles.size > 490_000) {
            particles.subList(0, particles.size - 490_000).clear()
        }
    }

    private fun updateBodyRadii() {
        for (b in bodies) b.radius = computeVisualRadius(b)
    }

    private fun clampBodyCount() {
        while (bodies.size > 99_000) bodies.removeAt(bodies.lastIndex)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SSBO upload
    // ═══════════════════════════════════════════════════════════════════════════

    private fun uploadSSBOs() {
        val bCount = bodies.size
        if (bCount > 0) {
            val data = FloatArray(bCount * BodySSBO.FLOATS)
            for ((i, b) in bodies.withIndex()) {
                val (cr, cg, cb) = bodyColor(b)
                val base = i * BodySSBO.FLOATS
                data[base + 0]  = b.x;             data[base + 1]  = b.y
                data[base + 2]  = b.z;             data[base + 3]  = b.radius
                data[base + 4]  = cr;              data[base + 5]  = cg
                data[base + 6]  = cb;              data[base + 7]  = b.type.ordinal.toFloat()
                data[base + 8]  = b.state.ordinal.toFloat()
                data[base + 9]  = b.temperature;   data[base + 10] = computeGlow(b)
                data[base + 11] = if (b.isEarth) 1f else 0f
            }
            bodySSBO.upload(data, bCount)
        }

        val pCount = particles.size.coerceAtMost(MAX_PARTICLES)
        if (pCount > 0) {
            val data = FloatArray(pCount * StellarParticleSSBO.FLOATS)
            for (i in 0 until pCount) {
                val p = particles[i]
                val base = i * StellarParticleSSBO.FLOATS
                data[base + 0]  = p.px;   data[base + 1]  = p.py
                data[base + 2]  = p.pz;   data[base + 3]  = p.life
                data[base + 4]  = p.vx;   data[base + 5]  = p.vy
                data[base + 6]  = p.vz;   data[base + 7]  = p.size
                data[base + 8]  = p.cr;   data[base + 9]  = p.cg
                data[base + 10] = p.cb;   data[base + 11] = p.type
            }
            particleSSBO.upload(data, pCount)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Rendering
    // ═══════════════════════════════════════════════════════════════════════════

    private fun updateMatrices() {
        val eyeX = cameraTargetX + cameraDistance * cos(cameraPhi) * sin(cameraTheta)
        val eyeY = cameraTargetY + cameraDistance * sin(cameraPhi)
        val eyeZ = cameraTargetZ + cameraDistance * cos(cameraPhi) * cos(cameraTheta)
        viewMatrix.setLookAt(eyeX, eyeY, eyeZ, cameraTargetX, cameraTargetY, cameraTargetZ, 0f, 1f, 0f)
        projMatrix.setPerspective(
            (PI / 3.0).toFloat(), windowWidth.toFloat() / windowHeight.toFloat(), 0.001f, 5000f
        )
    }

    private fun render() {
        updateMatrices()

        val hasBlackHoles = bodies.any { it.state == StellarState.BLACK_HOLE }

        if (hasBlackHoles) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo)
            glViewport(0, 0, windowWidth, windowHeight)
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
        }

        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glClearColor(0f, 0f, 0f, 1f)

        // Background starfield (full-screen, no depth write)
        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glDisable(GL_BLEND)
        glUseProgram(backgroundProgram)
        if (bgTimeLoc >= 0) glUniform1f(bgTimeLoc, (simTimeYears * 1e-7).toFloat())
        glBindVertexArray(dummyVao)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        // Stars / planets
        glEnable(GL_DEPTH_TEST)
        glDepthMask(true)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE)

        bodySSBO.bind()
        glUseProgram(starProgram)
        if (starViewLoc >= 0) glUniformMatrix4fv(starViewLoc, false, viewMatrix.get(matBuf))
        if (starProjLoc >= 0) glUniformMatrix4fv(starProjLoc, false, projMatrix.get(matBuf))
        if (starTimeLoc >= 0) glUniform1f(starTimeLoc, (simTimeYears * 1e-7).toFloat())
        glBindVertexArray(dummyVao)
        if (bodies.isNotEmpty()) glDrawArraysInstanced(GL_TRIANGLES, 0, 6, bodies.size)

        // Particles
        glDepthMask(false)
        particleSSBO.bind()
        glUseProgram(particleProgram)
        if (partViewLoc >= 0) glUniformMatrix4fv(partViewLoc, false, viewMatrix.get(matBuf))
        if (partProjLoc >= 0) glUniformMatrix4fv(partProjLoc, false, projMatrix.get(matBuf))
        if (partResLoc  >= 0) glUniform2f(partResLoc, windowWidth.toFloat(), windowHeight.toFloat())
        val pDraw = particles.size.coerceAtMost(MAX_PARTICLES)
        if (pDraw > 0) glDrawArrays(GL_POINTS, 0, pDraw)
        glDepthMask(true)

        // Black-hole post-process
        if (hasBlackHoles) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glViewport(0, 0, windowWidth, windowHeight)
            glDisable(GL_DEPTH_TEST)
            glDisable(GL_BLEND)

            glUseProgram(postProcessProgram)
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, fboColorTex)
            if (ppSceneLoc >= 0) glUniform1i(ppSceneLoc, 0)
            if (ppResLoc   >= 0) glUniform2f(ppResLoc, windowWidth.toFloat(), windowHeight.toFloat())
            if (ppTimeLoc  >= 0) glUniform1f(ppTimeLoc, (simTimeYears * 1e-7).toFloat())

            val bhList = bodies.filter { it.state == StellarState.BLACK_HOLE }.take(8)
            if (ppNumBHLoc >= 0) glUniform1i(ppNumBHLoc, bhList.size)

            if (bhList.isNotEmpty()) {
                val eyeX = cameraTargetX + cameraDistance * cos(cameraPhi) * sin(cameraTheta)
                val eyeY = cameraTargetY + cameraDistance * sin(cameraPhi)
                val eyeZ = cameraTargetZ + cameraDistance * cos(cameraPhi) * cos(cameraTheta)

                val screenArr = FloatArray(16)
                val rsArr     = FloatArray(8)
                val massArr   = FloatArray(8)
                for ((idx, bh) in bhList.withIndex()) {
                    val clip = Vector4f(bh.x, bh.y, bh.z, 1f)
                    projMatrix.transform(viewMatrix.transform(clip))
                    if (clip.w > 0f) {
                        screenArr[idx * 2 + 0] = (clip.x / clip.w + 1f) * 0.5f * windowWidth
                        screenArr[idx * 2 + 1] = (1f - clip.y / clip.w) * 0.5f * windowHeight
                    }
                    val dBH = sqrt((bh.x - eyeX).pow(2) + (bh.y - eyeY).pow(2) + (bh.z - eyeZ).pow(2))
                    val focal = 1f / tan(PI.toFloat() / 6f)
                    rsArr[idx]   = if (dBH > 0f) focal * bh.radius / dBH * windowHeight * 0.5f else 50f
                    massArr[idx] = bh.mass.toFloat()
                }
                if (ppBHScreenLoc >= 0) glUniform2fv(ppBHScreenLoc, screenArr)
                if (ppBHRsLoc     >= 0) glUniform1fv(ppBHRsLoc,     rsArr)
                if (ppBHMassLoc   >= 0) glUniform1fv(ppBHMassLoc,   massArr)
            }

            glBindVertexArray(dummyVao)
            glDrawArrays(GL_TRIANGLES, 0, 3)
        }

        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_DEPTH_TEST)
        glDepthMask(true)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ImGui
    // ═══════════════════════════════════════════════════════════════════════════

    private fun drawImGui() {
        drawControlPanel()
        drawPresetsPanel()
        if (selectedBodyIdx in bodies.indices) drawBodyInspector(bodies[selectedBodyIdx])
        drawDebugOverlay()
    }

    private fun drawControlPanel() {
        ImGui.setNextWindowPos(10f, 10f, ImGuiCond.Once)
        ImGui.setNextWindowSize(280f, 260f, ImGuiCond.Once)
        ImGui.begin("시뮬레이션 제어")

        ImGui.text(if (simPaused) "상태: 일시정지" else "상태: 실행 중")
        if (ImGui.button(if (simPaused) "재개" else "일시정지")) simPaused = !simPaused
        ImGui.sameLine()
        if (ImGui.button("초기화")) resetSimulation()

        ImGui.separator()
        ImGui.text("시간 배율: ${formatTimeScale(simTimeScaleYears)}")
        ImGui.text("경과 시간: ${formatSimTime(simTimeYears)}")

        ImGui.spacing()
        ImGui.text("배율 프리셋:")
        val presets = arrayOf("1초 = 100만년", "1초 = 1천만년", "1초 = 1억년", "1초 = 10억년")
        val scales  = doubleArrayOf(1e6, 1e7, 1e8, 1e9)
        for (i in presets.indices) {
            if (i > 0) ImGui.sameLine()
            if (ImGui.button(presets[i])) simTimeScaleYears = scales[i]
        }

        ImGui.separator()
        ImGui.text("FPS: %.1f  GPU: %.2f ms".format(fps, gpuTimeMs))

        ImGui.end()
    }

    private fun drawPresetsPanel() {
        ImGui.setNextWindowPos(10f, 280f, ImGuiCond.Once)
        ImGui.setNextWindowSize(280f, 120f, ImGuiCond.Once)
        ImGui.begin("프리셋")

        if (ImGui.button("태양계 성운")) { loadSolarNebulaPreset() }
        ImGui.sameLine()
        if (ImGui.button("베텔게우스")) { loadBetelgeusePreset() }
        ImGui.sameLine()
        if (ImGui.button("시리우스 쌍성")) { loadSiriusBinaryPreset() }

        ImGui.spacing()
        ImGui.textWrapped("왼쪽 클릭: 천체 선택  |  드래그: 카메라 회전  |  MMB: 이동  |  스크롤: 줌")

        ImGui.end()
    }

    private fun drawBodyInspector(b: StellarBody) {
        ImGui.setNextWindowPos(windowWidth - 310f, 10f, ImGuiCond.Always)
        ImGui.setNextWindowSize(300f, 480f, ImGuiCond.Always)
        ImGui.begin("천체 검사기")

        val stateStr = STATE_NAMES.getOrElse(b.state.ordinal) { b.state.name }
        ImGui.text("ID: ${b.id}")
        ImGui.text("종류: ${bodyTypeKorean(b.type)}")
        ImGui.text("상태: $stateStr")
        ImGui.separator()
        ImGui.text("위치: (%.2f, %.2f, %.2f) AU".format(b.x, b.y, b.z))
        ImGui.text("속도: %.3f AU/yr".format(sqrt(b.vx*b.vx + b.vy*b.vy + b.vz*b.vz)))
        ImGui.text("질량: %.4f M☉".format(b.mass))
        ImGui.text("반지름: %.4f AU".format(b.radius))
        ImGui.text("온도: %.0f K".format(b.temperature))
        if (b.luminosity > 0.0) ImGui.text("광도: %.3e L☉".format(b.luminosity))
        ImGui.text("나이: ${formatSimTime(b.age)}")
        if (b.isEarth) {
            ImGui.separator()
            ImGui.text("🌍 지구형 행성 감지!")
            val pArr = FloatArray(1) { b.earthBlueProgress }
            ImGui.pushStyleColor(ImGuiCol.PlotHistogram, 0.1f, 0.4f, 0.8f, 1f)
            ImGui.progressBar(b.earthBlueProgress, 200f, 12f, "해양화 %.0f%%".format(b.earthBlueProgress * 100f))
            ImGui.popStyleColor()
        }

        if (b.type == BodyType.STAR || b.state == StellarState.PROTOSTAR ||
            b.state == StellarState.MAIN_SEQUENCE || b.state == StellarState.RED_GIANT ||
            b.state == StellarState.SUPERGIANT) {
            ImGui.separator()
            ImGui.text("핵 조성:")
            drawCompositionBar("H (수소)",   b.coreH,   0.2f, 0.5f, 1.0f)
            drawCompositionBar("He (헬륨)",  b.coreHe,  0.8f, 0.8f, 0.2f)
            drawCompositionBar("C (탄소)",   b.coreC,   0.5f, 0.5f, 0.5f)
            drawCompositionBar("O (산소)",   b.coreO,   0.2f, 0.8f, 0.8f)
            drawCompositionBar("Si (규소)",  b.coreSi,  0.7f, 0.6f, 0.4f)
            drawCompositionBar("Fe (철)",    b.coreFe,  0.8f, 0.3f, 0.1f)
        }

        ImGui.end()
    }

    private fun drawCompositionBar(label: String, value: Float, r: Float, g: Float, b: Float) {
        ImGui.text("%-8s".format(label))
        ImGui.sameLine()
        ImGui.pushStyleColor(ImGuiCol.PlotHistogram, r, g, b, 1f)
        ImGui.progressBar(value.coerceIn(0f, 1f), 160f, 10f, "%.1f%%".format(value * 100f))
        ImGui.popStyleColor()
    }

    private fun drawDebugOverlay() {
        ImGui.setNextWindowPos(windowWidth - 220f, windowHeight - 110f, ImGuiCond.Always)
        ImGui.setNextWindowSize(210f, 100f, ImGuiCond.Always)
        val flags = ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoInputs or
                    ImGuiWindowFlags.NoNav or ImGuiWindowFlags.NoMove or
                    ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoBringToFrontOnFocus
        ImGui.begin("디버그", flags)
        ImGui.text("FPS: %.1f".format(fps))
        ImGui.text("천체 수: ${bodies.size}")
        ImGui.text("파티클: ${particles.size}")
        ImGui.text("시뮬레이션: ${formatSimTime(simTimeYears)}")
        ImGui.text("[F11=전체화면]")
        ImGui.end()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Presets
    // ═══════════════════════════════════════════════════════════════════════════

    private fun resetSimulation() {
        bodies.clear(); particles.clear()
        nextBodyId = 0; selectedBodyIdx = -1; simTimeYears = 0.0
        resetCamera()
    }

    private fun resetCamera() {
        cameraTheta = 0f; cameraPhi = 0.4f; cameraDistance = 30f
        cameraTargetX = 0f; cameraTargetY = 0f; cameraTargetZ = 0f
    }

    fun loadSolarNebulaPreset() {
        resetSimulation()
        simTimeScaleYears = 1e7

        // Central nebula core
        bodies.add(StellarBody(
            id = nextBodyId++, type = BodyType.STAR, state = StellarState.NEBULA_NORMAL,
            x = 0f, y = 0f, z = 0f, mass = 0.9, temperature = 100f,
            luminosity = 0.001, radius = 2.0f, coreH = 0.7f, coreHe = 0.28f
        ))

        // Nebula cloud particles in disk formation
        repeat(200) {
            val angle = rng.nextFloat() * 2f * PI.toFloat()
            val r     = 1f + rng.nextFloat() * 8f
            val yOff  = (rng.nextFloat() - 0.5f) * 0.5f
            val orbitV = sqrt(G_AU * 0.9f / r) * (0.8f + rng.nextFloat() * 0.4f)
            bodies.add(StellarBody(
                id = nextBodyId++, type = BodyType.NEBULA_PARTICLE,
                state = StellarState.NEBULA_NORMAL,
                x = r * cos(angle), y = yOff, z = r * sin(angle),
                vx = -orbitV * sin(angle), vy = 0f, vz = orbitV * cos(angle),
                mass = 0.0005, temperature = 50f, luminosity = 0.0,
                radius = 0.5f + rng.nextFloat() * 1.5f,
                coreH = 0.71f, coreHe = 0.27f
            ))
        }

        // Planetesimals at 0.3–5 AU
        repeat(50) {
            val r     = 0.3f + rng.nextFloat() * 4.7f
            val angle = rng.nextFloat() * 2f * PI.toFloat()
            val orbitV = sqrt(G_AU * 0.9f / r)
            val mass  = 1e-7 + rng.nextDouble() * 9e-7
            bodies.add(StellarBody(
                id = nextBodyId++, type = BodyType.PLANETESIMAL,
                state = StellarState.PROTO_DISK,
                x = r * cos(angle), y = (rng.nextFloat() - 0.5f) * 0.1f, z = r * sin(angle),
                vx = -orbitV * sin(angle), vy = 0f, vz = orbitV * cos(angle),
                mass = mass, temperature = 200f, luminosity = 0.0,
                radius = 0.002f + rng.nextFloat() * 0.008f,
                waterUV = rng.nextFloat(), landUV = rng.nextFloat()
            ))
        }
    }

    fun loadBetelgeusePreset() {
        resetSimulation()
        simTimeScaleYears = 1e7

        bodies.add(StellarBody(
            id = nextBodyId++, type = BodyType.STAR, state = StellarState.SUPERGIANT,
            x = 0f, y = 0f, z = 0f,
            mass = 20.0, radius = 2.0f, temperature = 3500f,
            luminosity = 100000.0, age = 8e6,
            coreH = 0f, coreHe = 0.1f, coreC = 0.2f, coreO = 0.3f, coreSi = 0.3f, coreFe = 0.1f,
            mainSeqLifetime = mainSeqLifetime(20.0)
        ))
        cameraDistance = 10f
    }

    fun loadSiriusBinaryPreset() {
        resetSimulation()
        simTimeScaleYears = 1e6

        val sep   = 20.0f
        val mA    = 2.0; val mB = 1.0; val mTot = mA + mB
        val rA    = (sep * mB / mTot).toFloat()
        val rB    = (sep * mA / mTot).toFloat()
        val vRel  = sqrt(G_AU * mTot.toFloat() / sep)
        val vA    = vRel * (mB / mTot).toFloat()
        val vB    = vRel * (mA / mTot).toFloat()

        // Sirius A - main sequence
        bodies.add(StellarBody(
            id = nextBodyId++, type = BodyType.STAR, state = StellarState.MAIN_SEQUENCE,
            x = -rA, y = 0f, z = 0f,
            vx = 0f, vy = vA, vz = 0f,
            mass = mA, temperature = 9940f,
            luminosity = starLuminosity(mA), radius = 0.05f + 0.05f * mA.toFloat(),
            mainSeqLifetime = mainSeqLifetime(mA),
            coreH = 0.55f, coreHe = 0.43f
        ))

        // Sirius B - white dwarf
        bodies.add(StellarBody(
            id = nextBodyId++, type = BodyType.STAR, state = StellarState.WHITE_DWARF,
            x = rB, y = 0f, z = 0f,
            vx = 0f, vy = -vB, vz = 0f,
            mass = mB, temperature = 25200f,
            luminosity = 0.056, radius = 0.017f,
            mainSeqLifetime = mainSeqLifetime(mB)
        ))

        cameraDistance = 50f
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Helper functions
    // ═══════════════════════════════════════════════════════════════════════════

    private fun computeVisualRadius(b: StellarBody): Float = when {
        b.type == BodyType.PLANET      -> (0.005f + 0.025f * (b.mass / 1e-4).toFloat().coerceIn(0f, 1f))
        b.type == BodyType.PLANETESIMAL -> (0.002f + 0.008f * (b.mass / 1e-5).toFloat().coerceIn(0f, 1f))
        b.type == BodyType.NEBULA_PARTICLE -> b.radius.coerceIn(0.5f, 2.0f)
        else -> when (b.state) {
            StellarState.NEBULA_NORMAL, StellarState.NEBULA_CONTRACTING,
            StellarState.PROTO_DISK, StellarState.PROTO_STELLAR_SYSTEM -> 0.5f + rng.nextFloat() * 0.001f
            StellarState.PROTOSTAR      -> 0.1f
            StellarState.MAIN_SEQUENCE  -> (0.05f + 0.05f * b.mass.toFloat()).coerceAtLeast(0.01f)
            StellarState.RED_GIANT      -> 0.5f
            StellarState.SUPERGIANT     -> 2.0f
            StellarState.WHITE_DWARF    -> 0.01f
            StellarState.SUPERNOVA      -> {
                val progress = (1.0 - b.supernovaTimer / 5e6).toFloat().coerceIn(0f, 1f)
                progress * 3.0f
            }
            StellarState.BLACK_DWARF    -> 0.01f
            StellarState.BLACK_HOLE     -> 0.02f
        }
    }

    private fun computeGlow(b: StellarBody): Float = when (b.state) {
        StellarState.BLACK_HOLE     -> 0.0f
        StellarState.WHITE_DWARF    -> 1.0f
        StellarState.BLACK_DWARF    -> 0.1f
        StellarState.SUPERNOVA      -> 2.0f
        StellarState.MAIN_SEQUENCE  -> (0.6f + b.luminosity.toFloat() / 100f).coerceIn(0.6f, 3f)
        StellarState.RED_GIANT      -> 0.7f
        StellarState.SUPERGIANT     -> 0.9f
        StellarState.PROTOSTAR      -> 0.3f
        else -> if (b.type == BodyType.PLANET || b.type == BodyType.PLANETESIMAL) 0.0f else 0.1f
    }

    private fun bodyColor(b: StellarBody): Triple<Float, Float, Float> {
        if (b.isEarth) return earthColor(b.earthBlueProgress)
        if (b.type == BodyType.PLANET || b.type == BodyType.PLANETESIMAL)
            return Triple(0.45f, 0.40f, 0.35f)
        if (b.type == BodyType.NEBULA_PARTICLE)
            return Triple(0.55f + b.waterUV * 0.2f, 0.35f, 0.70f + b.landUV * 0.2f)
        return blackBodyColor(b.temperature)
    }

    private fun earthColor(progress: Float): Triple<Float, Float, Float> {
        return if (progress < 0.5f) {
            val t = progress * 2f
            Triple(lerp(0.4f, 0.3f, t), lerp(0.35f, 0.45f, t), lerp(0.25f, 0.25f, t))
        } else {
            val t = (progress - 0.5f) * 2f
            Triple(lerp(0.3f, 0.1f, t), lerp(0.45f, 0.35f, t), lerp(0.25f, 0.6f, t))
        }
    }

    private fun blackBodyColor(temp: Float): Triple<Float, Float, Float> {
        val t = temp / 100f
        val r = when {
            t <= 66f -> 1.0f
            else     -> ((329.698727446f * (t - 60f).pow(-0.1332047592f)) / 255f).coerceIn(0f, 1f)
        }
        val g = when {
            t <= 0f  -> 0f
            t <= 66f -> ((99.4708025861f * ln(t.coerceAtLeast(1f)) - 161.1195681661f) / 255f).coerceIn(0f, 1f)
            else     -> ((288.1221695283f * (t - 60f).pow(-0.0755148492f)) / 255f).coerceIn(0f, 1f)
        }
        val b = when {
            t >= 66f -> 1.0f
            t <= 19f -> 0.0f
            else     -> ((138.5177312231f * ln((t - 10f).coerceAtLeast(1f)) - 305.0447927307f) / 255f).coerceIn(0f, 1f)
        }
        return Triple(r, g, b)
    }

    private fun starTemperature(mass: Double): Float =
        (5778.0 * mass.pow(0.5)).toFloat().coerceIn(2000f, 60000f)

    private fun starLuminosity(mass: Double): Double = mass.pow(3.5)

    private fun mainSeqLifetime(mass: Double): Double = 1e10 / mass.pow(2.5)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun bodyTypeKorean(t: BodyType): String = when (t) {
        BodyType.STAR           -> "항성"
        BodyType.PLANET         -> "행성"
        BodyType.PLANETESIMAL   -> "미행성"
        BodyType.NEBULA_PARTICLE -> "성운 입자"
    }

    fun formatSimTime(years: Double): String = when {
        years < 1e3  -> "%.0f 년".format(years)
        years < 1e6  -> "%.1f 천년".format(years / 1e3)
        years < 1e9  -> "%.1f 백만년".format(years / 1e6)
        years < 1e12 -> "%.2f 십억년".format(years / 1e9)
        else         -> "%.2e 년".format(years)
    }

    fun formatTimeScale(yprs: Double): String = when {
        yprs < 1e3  -> "1초 = %.0f 년".format(yprs)
        yprs < 1e6  -> "1초 = %.1f 천년".format(yprs / 1e3)
        yprs < 1e9  -> "1초 = %.1f 백만년".format(yprs / 1e6)
        else        -> "1초 = %.1f 십억년".format(yprs / 1e9)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Cleanup
    // ═══════════════════════════════════════════════════════════════════════════

    fun cleanup() {
        imGuiGl3.dispose()
        imGuiGlfw.dispose()
        ImGui.destroyContext()

        bodySSBO.delete()
        particleSSBO.delete()

        glDeleteProgram(backgroundProgram)
        glDeleteProgram(starProgram)
        glDeleteProgram(particleProgram)
        glDeleteProgram(postProcessProgram)

        glDeleteVertexArrays(dummyVao)
        glDeleteFramebuffers(fbo)
        glDeleteTextures(fboColorTex)
        glDeleteRenderbuffers(fboDepthRbo)
        glDeleteQueries(timerQuery)

        glfwFreeCallbacks(windowHandle)
        glfwDestroyWindow(windowHandle)
        glfwTerminate()
        glfwSetErrorCallback(null)?.free()
    }
}


