package br.com.medidor.app.domain

/** Independent safety limits for untrusted ZIP entries and JSON metadata. */
object BackupLimits {
    const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
    const val MAX_IMAGE_BYTES = 12L * 1024 * 1024
    const val MAX_METADATA_BYTES = 2L * 1024 * 1024
    const val MAX_ENTRY_COUNT = 160
    const val MAX_PROJECTS = 20

    fun safeImageName(name: String): Boolean =
        name.length in 6..120 &&
            name.endsWith(".jpg", ignoreCase = true) &&
            name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' } &&
            name != "." && name != ".." &&
            !name.contains("..")

    fun validEntry(name: String): Boolean =
        name == "manifest.json" ||
            (name.startsWith("images/") && safeImageName(name.removePrefix("images/")))

    fun allowedSize(name: String): Long =
        if (name == "manifest.json") MAX_METADATA_BYTES else MAX_IMAGE_BYTES
}
