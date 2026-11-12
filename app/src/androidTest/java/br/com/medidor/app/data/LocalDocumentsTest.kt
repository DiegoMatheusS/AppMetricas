package br.com.medidor.app.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.medidor.app.domain.ArchitectPlan
import br.com.medidor.app.domain.PlanFloor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDocumentsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun namespace() = "test_" + UUID.randomUUID().toString().replace("-", "")

    @Test fun legacyValuesMigrateOnceWithoutRemovingTheOriginal() {
        val name = namespace()
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        legacy.edit().putString("records", "original").commit()
        val docs = LocalDocuments(context, name)
        try {
            assertEquals("original", docs.getString("records", null))
            assertEquals("original", legacy.getString("records", null))
            assertTrue(docs.edit().clear().commit())
            assertNull(LocalDocuments(context, name).getString("records", null))
        } finally { docs.edit().clear().commit(); legacy.edit().clear().commit() }
    }

    @Test fun failedRestoreRollsBackBothNamespaces() {
        val a = LocalDocuments(context, namespace())
        val b = LocalDocuments(context, namespace())
        try {
            a.edit().putString("plan", "before").commit()
            b.edit().putString("records", "before").commit()
            assertFalse(a.commitTogether(a.edit().putString("plan", "after"),
                b.edit().putString("records", "after").putString("x".repeat(129), "constraint failure")))
            assertEquals("before", a.getString("plan", null))
            assertEquals("before", b.getString("records", null))
        } finally { a.edit().clear().commit(); b.edit().clear().commit() }
    }

    @Test fun concurrentReadModifyWriteDoesNotLoseRecords() {
        val docs = LocalDocuments(context, namespace())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        docs.edit().putString("count", "0").commit()
        try {
            val jobs = (0 until 4).map { pool.submit {
                start.await()
                repeat(10) { docs.transaction {
                    val value = docs.getString("count", "0")!!.toInt()
                    check(docs.edit().putString("count", (value + 1).toString()).commit())
                } }
            } }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals("40", docs.getString("count", null))
        } finally { pool.shutdownNow(); docs.edit().clear().commit() }
    }

    @Test fun backupRoundTripAndInvalidRestorePreserveProjectsAndHistory() {
        val projects = LocalDocuments(context, "architect_plan_v1")
        val history = LocalDocuments(context, "measurements_v1")
        val previousProjects = projects.all
        val previousHistory = history.all
        val folder = File(context.filesDir, "architect").apply { mkdirs() }
        val previousFiles = folder.listFiles().orEmpty().map { it.name }.toSet()
        try {
            check(projects.commitTogether(projects.edit().clear(), history.edit().clear()))
            val manager = ArchitectProjectManager(context)
            manager.rename(manager.activeId(), "Planta A")
            val store = ArchitectPlanStore(context)
            val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
            val image = try { store.saveImage(bitmap) } finally { bitmap.recycle() }
            store.save(ArchitectPlan(listOf(PlanFloor("Piso", imageFile = image, imageWidth = 40, imageHeight = 30))))
            manager.create("Planta B", true)
            LocalHistory(context).save("Régua", "20 cm")
            val bytes = ByteArrayOutputStream().also { ArchitectBackupManager(context).export(it) }.toByteArray()
            manager.create("Temporário", false)
            LocalHistory(context).clear()
            assertEquals(2, ArchitectBackupManager(context).restore(ByteArrayInputStream(bytes)))
            assertEquals(setOf("Planta A", "Planta B"), manager.list().map { it.name }.toSet())
            assertEquals("20 cm", LocalHistory(context).load().single().details)
            assertNotNull(store.readImage(store.load().floors.single().imageFile))
            val currentProjects = projects.all
            val currentHistory = history.all
            val invalid = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write("{\"schema\":999}".toByteArray()); zip.closeEntry()
                }
            }.toByteArray()
            assertTrue(runCatching { ArchitectBackupManager(context).restore(ByteArrayInputStream(invalid)) }.isFailure)
            assertEquals(currentProjects, projects.all)
            assertEquals(currentHistory, history.all)
        } finally {
            val p = projects.edit().clear(); previousProjects.forEach { (key, value) -> p.putString(key, value) }
            val h = history.edit().clear(); previousHistory.forEach { (key, value) -> h.putString(key, value) }
            check(projects.commitTogether(p, h))
            folder.listFiles().orEmpty().filter { it.name !in previousFiles }.forEach { it.delete() }
        }
    }

    @Test fun encryptedReceiptCacheRejectsTampering() {
        val prefs = context.getSharedPreferences("play_receipt_v1", Context.MODE_PRIVATE)
        val before = prefs.getString("receipt", null)
        val cache = ProReceiptCache(context)
        try {
            cache.save("signed receipt", "signature")
            assertEquals("signed receipt" to "signature", cache.read())
            val stored = prefs.getString("receipt", null)!!
            assertFalse(stored.contains("signed receipt"))
            val bytes = java.util.Base64.getDecoder().decode(stored)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            prefs.edit().putString("receipt", java.util.Base64.getEncoder().encodeToString(bytes)).commit()
            assertNull(cache.read())
        } finally { prefs.edit().putString("receipt", before).commit() }
    }
}
