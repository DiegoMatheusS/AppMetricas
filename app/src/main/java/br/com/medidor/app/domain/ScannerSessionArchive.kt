package br.com.medidor.app.domain

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ImportedScannerArchive(val name: String, val frames: Map<Int, File>)

/**
 * Standalone v1 scanner-session ZIP. The archive contains an explicit manifest,
 * SHA-256 checksums, and no paths provided by the caller are used for extraction.
 * Image content is validated again by Android before being installed.
 */
object ScannerSessionArchive {
    private const val MAGIC = "MEDIDOR_SCANNER_V1"
    private const val MAX_PHOTO_BYTES = 8L * 1024 * 1024
    private const val MAX_UNCOMPRESSED = 100L * 1024 * 1024
    private const val MAX_MANIFEST = 16L * 1024

    fun write(name: String, frames: Map<Int, File>, output: OutputStream) {
        val title = cleanName(name)
        require(ScannerCaptureGuide.validSlots(frames.keys))
        var total = 0L
        val details = frames.toSortedMap().map { (slot, file) ->
            require(file.isFile && file.length() in 1..MAX_PHOTO_BYTES) {
                "Imagem não encontrada ou maior que 8 MB."
            }
            total += file.length()
            require(total <= MAX_UNCOMPRESSED) { "Backup excede 100 MB." }
            "${slot}:${file.length()}:${digest(file)}"
        }
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(title.toByteArray(Charsets.UTF_8))
        val text = (listOf(MAGIC, "name=$encoded") + details).joinToString("\n")
        val manifest = text.toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.txt"))
            zip.write(manifest)
            zip.closeEntry()
            frames.toSortedMap().forEach { (slot, file) ->
                zip.putNextEntry(ZipEntry("shots/$slot.jpg"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun read(source: InputStream, staging: File): ImportedScannerArchive {
        require(staging.isDirectory)
        val files = mutableMapOf<String, File>()
        var total = 0L
        ZipInputStream(source).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                val number = if (path.startsWith("shots/") && path.endsWith(".jpg"))
                    path.removePrefix("shots/").removeSuffix(".jpg").toIntOrNull() else null
                require(!entry.isDirectory && files.size < ScannerCaptureGuide.REQUIRED_SHOTS + 1 &&
                    (path == "manifest.txt" || number != null &&
                        number in 0 until ScannerCaptureGuide.REQUIRED_SHOTS &&
                        path == "shots/$number.jpg") && path !in files) {
                    "Arquivo ZIP contém nomes inválidos ou duplicados."
                }
                val cap = if (path == "manifest.txt") MAX_MANIFEST else MAX_PHOTO_BYTES
                if (entry.size >= 0L) require(entry.size <= cap)
                val localName = if (path == "manifest.txt") "manifest.txt" else "photo_$number.jpg"
                val file = File(staging, localName)
                require(file.canonicalFile.parentFile == staging.canonicalFile)
                var written = 0L
                file.outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count == -1) break
                        written += count
                        total += count
                        require(written <= cap && total <= MAX_UNCOMPRESSED) {
                            "Backup supera o limite descompactado."
                        }
                        out.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
                files[path] = file
            }
        }
        val manifest = requireNotNull(files["manifest.txt"]) {
            "Arquivo de identificação ausente."
        }.readText(Charsets.UTF_8).split("\n").filter { it.isNotBlank() }
        require(manifest.size in 2..(ScannerCaptureGuide.REQUIRED_SHOTS + 2) &&
            manifest[0] == MAGIC && manifest[1].startsWith("name=")) {
            "Formato da sessão não reconhecido."
        }
        val title = try {
            String(Base64.getUrlDecoder().decode(manifest[1].removePrefix("name=")), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Nome codificado inválido.")
        }
        require(title == cleanName(title))
        val expected = mutableMapOf<Int, Pair<Long, String>>()
        manifest.drop(2).forEach { line ->
            val tokens = line.split(":")
            require(tokens.size == 3)
            val slot = tokens[0].toIntOrNull()
            val size = tokens[1].toLongOrNull()
            val hash = tokens[2]
            require(slot != null && slot in 0 until ScannerCaptureGuide.REQUIRED_SHOTS &&
                size != null && size in 1..MAX_PHOTO_BYTES &&
                hash.matches(Regex("[0-9a-f]{64}")) && slot !in expected) {
                "Metadados da fotografia inválidos."
            }
            expected[slot] = size to hash
        }
        require(files.keys == (setOf("manifest.txt") +
            expected.keys.map { "shots/$it.jpg" }).toSet()) {
            "Backup incompleto ou com imagens extras."
        }
        val verified = expected.mapValues { (slot, info) ->
            val photo = files.getValue("shots/$slot.jpg")
            require(photo.length() == info.first && digest(photo) == info.second) {
                "Fotografia alterada ou corrompida."
            }
            photo
        }
        return ImportedScannerArchive(title, verified)
    }

    private fun cleanName(input: String): String =
        input.trim().also { require(it.length in 1..60 && it == input) {
            "Nome da sessão inválido."
        } }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(16384)
            while (true) {
                val bytes = stream.read(buffer)
                if (bytes < 0) break
                md.update(buffer, 0, bytes)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
