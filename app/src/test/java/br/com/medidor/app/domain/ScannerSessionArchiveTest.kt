package br.com.medidor.app.domain

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

class ScannerSessionArchiveTest {
    private fun withDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("scanner-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun zipWith(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (path, data) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test fun exportsAndRestoresAllPhotoSlotsWithoutModification() = withDir { dir ->
        val first = File(dir, "first.jpg").apply { writeBytes(byteArrayOf(1, 7, 8, 9)) }
        val second = File(dir, "second.jpg").apply { writeBytes(byteArrayOf(5, 4, 3)) }
        val output = ByteArrayOutputStream()
        ScannerSessionArchive.write("Teste bancada", mapOf(0 to first, 17 to second), output)
        val staging = File(dir, "staging").apply { mkdirs() }
        val restored = ScannerSessionArchive.read(
            ByteArrayInputStream(output.toByteArray()), staging
        )
        assertEquals("Teste bancada", restored.name)
        assertEquals(setOf(0, 17), restored.frames.keys)
        assertArrayEquals(first.readBytes(), restored.frames.getValue(0).readBytes())
        assertArrayEquals(second.readBytes(), restored.frames.getValue(17).readBytes())
    }

    @Test fun emptySessionCanBeBackedUp() = withDir { dir ->
        val output = ByteArrayOutputStream()
        ScannerSessionArchive.write("Vazio", emptyMap(), output)
        val staging = File(dir, "staging").apply { mkdirs() }
        val result = ScannerSessionArchive.read(ByteArrayInputStream(output.toByteArray()), staging)
        assertTrue(result.frames.isEmpty())
    }

    @Test fun maliciousOrExtraFileIsRejected() = withDir { dir ->
        val staging = File(dir, "staging").apply { mkdirs() }
        val bad = zipWith(
            "manifest.txt" to "MEDIDOR_SCANNER_V1\nname=VGVzdGU".toByteArray(),
            "shots/../../outside.jpg" to byteArrayOf(1)
        )
        assertThrows(IllegalArgumentException::class.java) {
            ScannerSessionArchive.read(ByteArrayInputStream(bad), staging)
        }
    }

    @Test fun missingManifestAndUnsupportedNameAreRejected() = withDir { dir ->
        val staging = File(dir, "staging").apply { mkdirs() }
        assertThrows(IllegalArgumentException::class.java) {
            ScannerSessionArchive.read(ByteArrayInputStream(zipWith(
                "shots/0.jpg" to byteArrayOf(1)
            )), staging)
        }
    }

    @Test fun rejectsModifiedPhotoWithOriginalDigest() = withDir { dir ->
        val original = File(dir, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val out = ByteArrayOutputStream()
        ScannerSessionArchive.write("Objeto", mapOf(0 to original), out)
        val staging = File(dir, "stage1").apply { mkdirs() }
        val expected = ScannerSessionArchive.read(ByteArrayInputStream(out.toByteArray()), staging)
        val content = expected.frames.getValue(0).readBytes()
        val meta = File(staging, "manifest.txt").readBytes()
        val tampered = zipWith(
            "manifest.txt" to meta,
            "shots/0.jpg" to byteArrayOf(4, 3, 2, 1)
        )
        val target = File(dir, "stage2").apply { mkdirs() }
        assertThrows(IllegalArgumentException::class.java) {
            ScannerSessionArchive.read(ByteArrayInputStream(tampered), target)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), content)
    }
}
