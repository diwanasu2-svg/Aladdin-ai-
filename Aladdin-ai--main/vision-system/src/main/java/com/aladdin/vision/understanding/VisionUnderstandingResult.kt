package com.aladdin.vision.understanding

data class VisionUnderstandingResult(
    val success: Boolean,
    val text: String,
    val prompt: String,
    val error: String?,
    val cause: Throwable?
) {
    companion object {
        fun success(text: String, prompt: String = "") = VisionUnderstandingResult(
            success = true,
            text = text,
            prompt = prompt,
            error = null,
            cause = null
        )

        fun error(message: String, cause: Throwable? = null) = VisionUnderstandingResult(
            success = false,
            text = "",
            prompt = "",
            error = message,
            cause = cause
        )
    }
}
