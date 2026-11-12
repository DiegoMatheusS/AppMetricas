package br.com.medidor.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupZipReaderTest {
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, payload) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(payload)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun withStaging(block: (File) -> Unit) {
        val root = Files.createTempDirectory("medidor-zip-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun readsRoundtripManifestAndImage() = withStaging { dir ->
        val manifest = """{"schema":1,"projects":[]}""".toByteArray()
        val jpg = byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
        val input = archive("manifest.json" to manifest, "images/planta_123.jpg" to jpg)
        val result = BackupZipReader.extract(ByteArrayInputStream(input), dir)
        assertEquals(setOf("manifest.json", "images/planta_123.jpg"), result.keys)
        assertArrayEquals(manifest, result.getValue("manifest.json").readBytes())
        assertArrayEquals(jpg, result.getValue("images/planta_123.jpg").readBytes())
    }

    @Test fun rejectsTraversalWithoutWritingOutsideStaging() = withStaging { dir ->
        val data = archive("manifest.json" to "{}".toByteArray(),
            "images/../../private.jpg" to byteArrayOf(1,2,3))
        assertThrows(IllegalArgumentException::class.java) {
            BackupZipReader.extract(ByteArrayInputStream(data), dir)
        }
        assertTrue(!File(dir.parentFile, "private.jpg").exists())
    }

    @Test fun rejectsUnknownEntries() = withStaging { dir ->
        val input = archive("manifest.json" to byteArrayOf(1),
            "prefs.xml" to byteArrayOf(2))
        assertThrows(IllegalArgumentException::class.java) {
            BackupZipReader.extract(ByteArrayInputStream(input), dir)
        }
    }

    @Test fun rejectsMissingManifest() = withStaging { dir ->
        assertThrows(IllegalArgumentException::class.java) {
            BackupZipReader.extract(ByteArrayInputStream(
                archive("images/planta.jpg" to byteArrayOf(1))), dir)
        }
    }

    @Test fun rejectsOverlargeManifestEvenWhenCompressed() = withStaging { dir ->
        val oversized = ByteArray(2 * 1024 * 1024 + 1) { 'a'.code.toByte() }
        val data = archive("manifest.json" to oversized)
        assertThrows(IllegalArgumentException::class.java) {
            BackupZipReader.extract(ByteArrayInputStream(data), dir)
        }
    }
}
