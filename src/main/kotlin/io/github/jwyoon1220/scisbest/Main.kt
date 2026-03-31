package io.github.jwyoon1220.scisbest

/**
 * Application entry point.
 *
 * 1. Shows the Swing-based launcher dialog to select simulation mode.
 * 2. Launches AtomEngine (핵분열, 2D) or StellarEngine (핵융합/별의 일생, 3D).
 *
 * Note: On macOS the JVM must be started with -XstartOnFirstThread.
 *       -Djava.awt.headless=false is also required so Swing can display.
 *       Both flags are set in build.gradle.kts applicationDefaultJvmArgs.
 */
fun main() {
    val mode = SwingLauncher.show() ?: return  // user closed without selecting

    when (mode) {
        SimMode.FISSION -> {
            val engine = AtomEngine()
            try   { engine.init(); engine.run() }
            finally { engine.cleanup() }
        }
        SimMode.FUSION -> {
            val engine = StellarEngine()
            try   { engine.init(); engine.run() }
            finally { engine.cleanup() }
        }
    }
}
