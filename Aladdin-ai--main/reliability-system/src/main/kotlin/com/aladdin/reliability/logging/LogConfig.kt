package com.aladdin.reliability.logging

data class LogConfig(
    val logDir: String = "logs",
    val maxFiles: Int = 10,
    val maxFileSizeBytes: Long = 10 * 1024 * 1024,  // 10 MB
    val compress: Boolean = true,
    val captureLogcat: Boolean = true,
    val rotateDaily: Boolean = true,
    val logcatBuffers: List<String> = listOf("main", "system", "crash")
)
