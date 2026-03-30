package com.atomengine

/**
 * Application entry point.
 * Note: On macOS the JVM must be started with -XstartOnFirstThread.
 * This is configured in build.gradle.kts.
 */
fun main() {
    val engine = AtomEngine()
    try {
        engine.init()
        engine.run()
    } finally {
        engine.cleanup()
    }
}
