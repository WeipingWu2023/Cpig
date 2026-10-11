package app.copy.domain

object ClipPolicy {
    const val MAX_IMPORT_BYTES = 2_000_000_000L
    private val formats = mapOf(
        "video/mp4" to "mp4", "video/webm" to "webm", "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/wav" to "wav",
        "audio/x-wav" to "wav", "audio/ogg" to "ogg",
    )
    fun extension(mime: String?): String = formats[mime]
        ?: throw IllegalArgumentException("Use an MP4, WebM, M4A, MP3, WAV or OGG file")
    fun mime(name: String): String? = when (name.substringAfterLast('.', "")) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        else -> null
    }
    const val LEARNING_PROMPT = "Teach me this content as a beginner. Explain the main idea, define technical terms with examples, identify prerequisites in learning order, and walk through it step by step. Reference timestamps where available. Separate the creator's claims from your added explanation, flag uncertainty, and finish with three comprehension questions."
}
