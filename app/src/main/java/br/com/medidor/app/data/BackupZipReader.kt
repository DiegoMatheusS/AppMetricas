package br.com.medidor.app.data

import br.com.medidor.app.domain.BackupLimits
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Extract only allowed archive entries to a fresh app-private staging directory.
 * Bounded by total uncompressed bytes, individual size and entry count, even when
 * an archive lies about size metadata. Never trusts the ZIP path as a disk path.
 */
internal object BackupZipReader {
    fun extract(source: InputStream, staging: File): Map<String, File> {
        require(staging.isDirectory) { "Diretório temporário indisponível." }
        val collected = linkedMapOf<String, File>()
        var total = 0L
        ZipInputStream(source).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(collected.size < BackupLimits.MAX_ENTRY_COUNT &&
                    !entry.isDirectory &&
                    BackupLimits.validEntry(entry.name) &&
                    entry.name !in collected) {
                    "Backup contém nomes de arquivo inválidos ou duplicados."
                }
                val filename = if (entry.name == "manifest.json") "manifest.json"
                    else entry.name.removePrefix("images/")
                val target = File(staging, filename)
                // The allowed format excludes traversal, slashes, and subdirectories.
                require(target.canonicalFile.parentFile == staging.canonicalFile) {
                    "Arquivo fora do diretório temporário."
                }
                val max = BackupLimits.allowedSize(entry.name)
                if (entry.size >= 0L) {
                    require(entry.size in 0..max) { "Arquivo ZIP excede o tamanho permitido." }
                }
                var countWritten = 0L
                target.outputStream().use { output ->
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        val count = zip.read(chunk)
                        if (count == -1) break
                        countWritten += count.toLong()
                        total += count.toLong()
                        require(countWritten <= max && total <= BackupLimits.MAX_TOTAL_BYTES) {
                            "Backup excede o limite de 100 MB descompactados."
                        }
                        output.write(chunk, 0, count)
                    }
                }
                zip.closeEntry()
                collected[entry.name] = target
            }
        }
        require("manifest.json" in collected) { "Manifesto do backup não encontrado." }
        return collected
    }
}
