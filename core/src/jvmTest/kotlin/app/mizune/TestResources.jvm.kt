package app.mizune

actual fun testResource(path: String): ByteArray =
    checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(path)) { "no test resource $path" }.use { it.readBytes() }
