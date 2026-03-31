package io.github.jwyoon1220.scisbest

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.EmptyBorder

/** Mode selected by the user at launch. */
enum class SimMode { FISSION, FUSION }

/**
 * Swing-based launcher dialog shown before the OpenGL window.
 * Lets the user choose between:
 *   • 핵분열 모드 (2D GPU-accelerated fission simulation)
 *   • 핵융합 / 별의 일생 (3D stellar evolution + nuclear fusion)
 */
object SwingLauncher {

    // Palette
    private val BG_DARK      = Color(8, 12, 22)
    private val BG_PANEL     = Color(14, 22, 40)
    private val ACCENT_BLUE  = Color(40, 140, 220)
    private val ACCENT_GOLD  = Color(255, 180, 30)
    private val TEXT_MAIN    = Color(220, 230, 255)
    private val TEXT_SUB     = Color(130, 150, 190)
    private val BORDER_SEL   = Color(60, 180, 255)

    /**
     * Shows the launcher dialog on the Swing EDT, blocks until the user makes
     * a selection, then returns the chosen [SimMode] (or null if window closed).
     */
    fun show(): SimMode? {
        var result: SimMode? = null
        SwingUtilities.invokeAndWait {
            result = buildDialog()
        }
        return result
    }

    // ── Build and display the modal dialog, return chosen mode ──
    private fun buildDialog(): SimMode? {
        // Enable anti-aliasing
        System.setProperty("awt.useSystemAAFontSettings", "on")
        System.setProperty("swing.aatext", "true")

        var chosen: SimMode? = null

        val dialog = object : JDialog(null as Frame?, "핵물리 시뮬레이터", true) {
        }
        dialog.isUndecorated = true
        dialog.background    = BG_DARK
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

        val root = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // Gradient background
                val grad = GradientPaint(0f, 0f, Color(6, 10, 25), width.toFloat(), height.toFloat(), Color(15, 25, 50))
                g2.paint = grad
                g2.fillRoundRect(0, 0, width, height, 20, 20)
                // Subtle star-field dots
                g2.color = Color(255, 255, 255, 40)
                val rng = java.util.Random(42L)
                repeat(120) {
                    val sx = rng.nextInt(width); val sy = rng.nextInt(height)
                    val sz = rng.nextInt(2) + 1
                    g2.fillOval(sx, sy, sz, sz)
                }
            }
        }
        root.layout = BorderLayout(0, 0)
        root.border = EmptyBorder(32, 40, 32, 40)
        root.isOpaque = false

        // ── Title ──────────────────────────────────────────────
        val titlePanel = JPanel(BorderLayout())
        titlePanel.isOpaque = false
        titlePanel.border = EmptyBorder(0, 0, 24, 0)

        val title = makeLabel("⚛  핵물리 시뮬레이터", Font("Malgun Gothic", Font.BOLD, 28), TEXT_MAIN)
        val subtitle = makeLabel("시뮬레이션 모드를 선택하세요", Font("Malgun Gothic", Font.PLAIN, 14), TEXT_SUB)
        titlePanel.add(title, BorderLayout.NORTH)
        titlePanel.add(subtitle, BorderLayout.SOUTH)

        // ── Mode cards ─────────────────────────────────────────
        val cardsPanel = JPanel(GridLayout(1, 2, 20, 0))
        cardsPanel.isOpaque = false

        var fissionCard: ModeCard? = null
        var fusionCard:  ModeCard? = null

        fissionCard = ModeCard(
            icon       = "☢",
            title      = "핵분열 모드",
            subtitle   = "2D  |  GPU 가속",
            desc       = "<html><center>우라늄·플루토늄 핵분열 시뮬레이션<br>" +
                         "중성자 최대 2,000,000개<br>" +
                         "제논 독 · 온도 · 방사선 열지도</center></html>",
            accentColor = ACCENT_BLUE
        ) {
            chosen = SimMode.FISSION
            dialog.dispose()
        }

        fusionCard = ModeCard(
            icon       = "✨",
            title      = "핵융합 / 별의 일생",
            subtitle   = "3D  |  관찰자 시점",
            desc       = "<html><center>성운 → 원시성 → 주계열성 → 적색거성<br>" +
                         "초신성 폭발  |  블랙홀 (인터스텔라 효과)<br>" +
                         "H→He→C→O→S→Fe 핵융합 반응</center></html>",
            accentColor = ACCENT_GOLD
        ) {
            chosen = SimMode.FUSION
            dialog.dispose()
        }

        cardsPanel.add(fissionCard)
        cardsPanel.add(fusionCard)

        // ── Bottom hint ────────────────────────────────────────
        val hintPanel = JPanel(FlowLayout(FlowLayout.CENTER))
        hintPanel.isOpaque = false
        hintPanel.border = EmptyBorder(20, 0, 0, 0)
        val hint = makeLabel("카드를 클릭하거나 더블클릭해서 시작하세요  •  ESC로 종료", Font("Malgun Gothic", Font.PLAIN, 12), TEXT_SUB)
        hintPanel.add(hint)

        root.add(titlePanel, BorderLayout.NORTH)
        root.add(cardsPanel, BorderLayout.CENTER)
        root.add(hintPanel,  BorderLayout.SOUTH)

        dialog.contentPane = root
        dialog.setSize(720, 400)
        dialog.setLocationRelativeTo(null)
        dialog.shape = RoundRectangle2D.Double(0.0, 0.0, 720.0, 400.0, 20.0, 20.0)

        // ESC closes without selection
        val escAction = object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) { dialog.dispose() }
        }
        dialog.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("ESCAPE"), "close")
        dialog.rootPane.actionMap.put("close", escAction)

        // Drag to move (since undecorated)
        var dragX = 0; var dragY = 0
        root.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { dragX = e.x; dragY = e.y }
        })
        root.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val loc = dialog.location
                dialog.setLocation(loc.x + e.x - dragX, loc.y + e.y - dragY)
            }
        })

        dialog.isVisible = true  // blocks (modal) until disposed
        return chosen
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun makeLabel(text: String, font: Font, color: Color): JLabel {
        val lbl = JLabel(text, SwingConstants.CENTER)
        lbl.font = font; lbl.foreground = color; lbl.isOpaque = false
        return lbl
    }

    // ── Mode selection card ───────────────────────────────────

    private class ModeCard(
        icon: String,
        title: String,
        subtitle: String,
        desc: String,
        private val accentColor: Color,
        private val onSelect: () -> Unit
    ) : JPanel(BorderLayout(0, 8)) {

        private var hovered = false
        private var pressed = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = EmptyBorder(20, 20, 20, 20)

            val iconLbl = makeLabel(icon, Font("Segoe UI Emoji", Font.PLAIN, 42), Color.WHITE)
            val titleLbl = makeLabel(title, Font("Malgun Gothic", Font.BOLD, 18), Color(220, 230, 255))
            val subLbl   = makeLabel(subtitle, Font("Malgun Gothic", Font.PLAIN, 12), accentColor)
            val descLbl  = JLabel(desc, SwingConstants.CENTER)
            descLbl.font = Font("Malgun Gothic", Font.PLAIN, 12)
            descLbl.foreground = Color(160, 170, 200)

            val top = JPanel(BorderLayout(0, 4)).also { it.isOpaque = false }
            top.add(iconLbl, BorderLayout.NORTH)
            top.add(titleLbl, BorderLayout.CENTER)
            top.add(subLbl, BorderLayout.SOUTH)

            add(top, BorderLayout.NORTH)
            add(descLbl, BorderLayout.CENTER)

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                override fun mouseExited(e: MouseEvent)  { hovered = false; pressed = false; repaint() }
                override fun mousePressed(e: MouseEvent)  { pressed = true; repaint() }
                override fun mouseReleased(e: MouseEvent) { pressed = false; repaint() }
                override fun mouseClicked(e: MouseEvent)  { onSelect() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val alpha = when {
                pressed -> 210
                hovered -> 180
                else    -> 140
            }
            g2.color = Color(20, 35, 65, alpha)
            g2.fillRoundRect(0, 0, width, height, 16, 16)

            // Accent border
            val borderAlpha = if (hovered || pressed) 255 else 80
            g2.color = Color(accentColor.red, accentColor.green, accentColor.blue, borderAlpha)
            g2.stroke = BasicStroke(if (hovered) 2.5f else 1.5f)
            g2.drawRoundRect(1, 1, width - 2, height - 2, 16, 16)

            if (hovered) {
                // Subtle glow
                val glow = RadialGradientPaint(
                    width / 2f, height / 2f, width / 1.5f,
                    floatArrayOf(0f, 1f),
                    arrayOf(Color(accentColor.red, accentColor.green, accentColor.blue, 30), Color(0, 0, 0, 0))
                )
                g2.paint = glow
                g2.fillRoundRect(0, 0, width, height, 16, 16)
            }

            super.paintComponent(g)
        }
    }
}
