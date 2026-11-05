package br.com.medidor.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import br.com.medidor.app.domain.ScannerCaptureGuide
import br.com.medidor.app.domain.ScannerSessionArchive
import br.com.medidor.app.domain.ScannerPhotoQuality
import br.com.medidor.app.domain.ScannerPhotoCheck
import br.com.medidor.app.domain.ScannerQualityReport
import br.com.medidor.app.domain.ScannerFeatureMatching
import br.com.medidor.app.domain.ScanCorrespondenceReport
import br.com.medidor.app.domain.ImageFeatureSet
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ScannerSession(
    val id: String,
    val name: String,
    val createdAt: Long,
    val frames: Map<Int, String>
) {
    val capturedCount: Int get() = frames.size
    val isComplete: Boolean get() = ScannerCaptureGuide.complete(frames.keys)
}

/**
 * Keeps capture sessions in app-private storage and retains the original photographs.
 * Photos are unique to their slots. No image is uploaded or sent to third parties.
 */
class ScannerSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext
        .getSharedPreferences("scanner_capture_v1", Context.MODE_PRIVATE)
    private val folder = File(appContext.filesDir, "scanner").apply { mkdirs() }

    @Synchronized fun list(): List<ScannerSession> {
        val json = prefs.getString("sessions", "[]") ?: "[]"
        val array = JSONArray(json)
        require(array.length() <= ScannerCaptureGuide.MAX_SESSIONS) { "Sessões além do limite." }
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val photos = obj.optJSONObject("frames") ?: JSONObject()
            val frames = mutableMapOf<Int, String>()
            val keys = photos.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val slot = key.toIntOrNull()
                require(slot != null && slot in 0 until ScannerCaptureGuide.REQUIRED_SHOTS) {
                    "Posição inválida na sessão."
                }
                val filename = photos.getString(key)
                require(isValidFilename(filename)) { "Nome de foto inválido." }
                frames[slot] = filename
            }
            ScannerSession(
                id = obj.getString("id"),
                name = obj.getString("name"),
                createdAt = obj.getLong("createdAt"),
                frames = frames
            )
        }
    }

    @Synchronized fun create(name: String): ScannerSession {
        val normalized = name.trim()
        require(normalized.length in 1..60) { "Nome deve conter entre 1 e 60 caracteres." }
        val sessions = list()
        require(sessions.size < ScannerCaptureGuide.MAX_SESSIONS) {
            "Limite de 8 sessões. Exclua uma sessão antes de criar outra."
        }
        val session = ScannerSession(UUID.randomUUID().toString(), normalized,
            System.currentTimeMillis(), emptyMap())
        persist(listOf(session) + sessions)
        return session
    }

    @Synchronized fun rename(id: String, name: String) {
        val normalized = name.trim()
        require(normalized.length in 1..60) { "Nome inválido." }
        val sessions = list()
        require(sessions.any { it.id == id }) { "Sessão não encontrada." }
        persist(sessions.map { if (it.id == id) it.copy(name = normalized) else it })
    }

    /**
     * Replace a shot only after the new image is written AND the session index
     * has been committed; previous image stays recoverable until then.
     */
    @Synchronized fun saveShot(id: String, position: Int, bitmap: Bitmap): ScannerSession {
        require(position in 0 until ScannerCaptureGuide.REQUIRED_SHOTS)
        require(bitmap.width in 1..4096 && bitmap.height in 1..4096) {
            "Imagem inválida ou grande demais."
        }
        val sessions = list()
        val session = sessions.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Sessão não encontrada.")
        val filename = "scan_${UUID.randomUUID()}.jpg"
        val target = File(folder, filename)
        try {
            target.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 86, stream)) {
                    "Não foi possível salvar a foto."
                }
            }
            require(target.length() in 1..MAX_SHOT_BYTES) {
                "Fotografia grande demais (máximo 8 MB)."
            }
            val updated = session.copy(frames = session.frames + (position to filename))
            persist(sessions.map { if (it.id == id) updated else it })
            session.frames[position]?.let { oldName -> safeFile(oldName)?.delete() }
            return updated
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    @Synchronized fun deleteShot(id: String, position: Int): ScannerSession {
        val sessions = list()
        val session = sessions.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Sessão não encontrada.")
        val old = session.frames[position] ?: return session
        val updated = session.copy(frames = session.frames - position)
        persist(sessions.map { if (it.id == id) updated else it })
        safeFile(old)?.delete()
        return updated
    }

    @Synchronized fun deleteSession(id: String) {
        val sessions = list()
        val session = sessions.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Sessão não encontrada.")
        persist(sessions.filterNot { it.id == id })
        ScannerMeshStore(appContext).delete(id)
        session.frames.values.forEach { safeFile(it)?.delete() }
    }

    /** Export a single session without modifying any saved photographs. */
    @Synchronized fun exportSession(id: String, output: OutputStream) {
        val session = list().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Sessão não encontrada.")
        val photos = session.frames.mapValues { (_, name) ->
            requireNotNull(safeFile(name)) { "Imagem inválida." }
        }
        ScannerSessionArchive.write(session.name, photos, output)
    }

    /**
     * Restore a ZIP as a separate new session, never overwriting existing data.
     * Validate hashes, ZIP paths and all JPEG dimensions before installing.
     */
    @Synchronized fun importSession(source: InputStream): ScannerSession {
        val staging = File(appContext.cacheDir, "scanner_restore_${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Falha ao criar diretório temporário." }
        val createdFiles = mutableListOf<File>()
        try {
            val restored = ScannerSessionArchive.read(source, staging)
            val existing = list()
            require(existing.size < ScannerCaptureGuide.MAX_SESSIONS) {
                "Limite de sessões atingido. Exclua uma sessão antes de importar."
            }
            for (photo in restored.frames.values) {
                val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(photo.absolutePath, dimensions)
                require(dimensions.outWidth in 1..4096 && dimensions.outHeight in 1..4096) {
                    "Foto JPEG inválida ou com resolução acima de 4096 px."
                }
            }
            val frames = restored.frames.mapValues { (_, sourcePhoto) ->
                val name = "scan_${UUID.randomUUID()}.jpg"
                val file = File(folder, name)
                createdFiles += file
                sourcePhoto.copyTo(file)
                name
            }
            val result = ScannerSession(
                UUID.randomUUID().toString(), restored.name,
                System.currentTimeMillis(), frames
            )
            persist(listOf(result) + existing)
            return result
        } catch (e: Exception) {
            createdFiles.forEach { it.delete() }
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Analyze small, bounded thumbnails sequentially; no original full-size
     * bitmap is retained. This is a rough photo-quality check, not 3D solving.
     */
    fun analyzeSession(id: String): ScannerQualityReport {
        val session = list().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Sessão não encontrada.")
        val checks = session.frames.toSortedMap().map { (slot, name) ->
            val file = safeFile(name)
            if (file == null || !file.isFile || file.length() !in 1..MAX_SHOT_BYTES) {
                ScannerPhotoCheck(slot, null, listOf("Fotografia indisponível ou inválida"))
            } else {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) {
                        "Resolução inválida."
                    }
                    var sample = 1
                    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 192) {
                        sample *= 2
                    }
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: error("Erro ao carregar imagem.")
                    val thumbnail = Bitmap.createScaledBitmap(
                        bitmap, ScannerPhotoQuality.THUMB_SIZE,
                        ScannerPhotoQuality.THUMB_SIZE, true
                    )
                    val pixels = IntArray(ScannerPhotoQuality.THUMB_SIZE * ScannerPhotoQuality.THUMB_SIZE)
                    try {
                        thumbnail.getPixels(pixels, 0, ScannerPhotoQuality.THUMB_SIZE,
                            0, 0, ScannerPhotoQuality.THUMB_SIZE, ScannerPhotoQuality.THUMB_SIZE)
                    } finally {
                        if (thumbnail !== bitmap) thumbnail.recycle()
                        bitmap.recycle()
                    }
                    val metrics = ScannerPhotoQuality.evaluate(
                        pixels, ScannerPhotoQuality.THUMB_SIZE, ScannerPhotoQuality.THUMB_SIZE
                    )
                    ScannerPhotoCheck(slot, metrics, metrics.warnings())
                } catch (e: Exception) {
                    ScannerPhotoCheck(slot, null,
                        listOf("Não foi possível analisar a foto: ${e.localizedMessage}"))
                }
            }
        }
        return ScannerPhotoQuality.summarize(checks)
    }

    /**
     * First offline visual-correspondence prototype. Reads at most one bounded
     * Bitmap at a time and keeps ONLY 120 feature descriptors per photo.
     *
     * Results are 2D image-space correspondences, not camera poses or a mesh.
     */
    fun compareSessionPhotos(id: String): ScanCorrespondenceReport {
        val session = list().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Sessão não encontrada.")
        val descriptors = linkedMapOf<Int, ImageFeatureSet>()
        val unreadable = mutableListOf<Int>()
        for ((slot, name) in session.frames.toSortedMap()) {
            val photo = safeFile(name)
            if (photo == null || !photo.isFile || photo.length() !in 1..MAX_SHOT_BYTES) {
                unreadable += slot
                continue
            }
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(photo.absolutePath, bounds)
                require(bounds.outWidth in 40..4096 && bounds.outHeight in 40..4096) {
                    "Dimensões de imagem inválidas"
                }
                var sample = 1
                while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 384) {
                    sample *= 2
                }
                val bitmap = BitmapFactory.decodeFile(photo.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: error("Fotografia não pode ser decodificada")
                val size = ScannerFeatureMatching.IMAGE_SIZE
                val resized = try {
                    Bitmap.createScaledBitmap(bitmap, size, size, true)
                } catch (error: Exception) {
                    bitmap.recycle()
                    throw error
                }
                val pixels = IntArray(size * size)
                try {
                    resized.getPixels(pixels, 0, size, 0, 0, size, size)
                } finally {
                    if (resized !== bitmap) resized.recycle()
                    bitmap.recycle()
                }
                // Use a stable luminance conversion before feature detection.
                val gray = IntArray(size * size) { index ->
                    val color = pixels[index]
                    val r = (color ushr 16) and 255
                    val g = (color ushr 8) and 255
                    val b = color and 255
                    (299 * r + 587 * g + 114 * b) / 1000
                }
                descriptors[slot] = ScannerFeatureMatching.detect(gray, size, size)
            } catch (_: Exception) {
                unreadable += slot
            }
        }
        val availablePairs = ScannerFeatureMatching.recommendedPairs()
            .filter { (first, second) -> first in descriptors && second in descriptors }
        val results = availablePairs.map { (first, second) ->
            ScannerFeatureMatching.compare(
                first, second, descriptors.getValue(first), descriptors.getValue(second)
            )
        }
        return ScanCorrespondenceReport(
            pairs = results,
            missingSlots = (0 until ScannerCaptureGuide.REQUIRED_SHOTS)
                .filterNot { it in session.frames.keys },
            unavailableSlots = unreadable
        )
    }

    fun thumbnail(fileName: String?): Bitmap? {
        val file = safeFile(fileName) ?: return null
        if (!file.isFile) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (maxOf(options.outWidth / sample, options.outHeight / sample) > 512) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun safeFile(name: String?): File? =
        name?.takeIf(::isValidFilename)?.let { File(folder, it) }

    private fun isValidFilename(name: String): Boolean =
        name.startsWith("scan_") && name.endsWith(".jpg") &&
            name.length in 12..90 &&
            name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' } &&
            !name.contains("..")

    private fun persist(sessions: List<ScannerSession>) {
        require(sessions.size <= ScannerCaptureGuide.MAX_SESSIONS)
        val array = JSONArray().also { collection ->
            sessions.forEach { session ->
                require(ScannerCaptureGuide.validSlots(session.frames.keys))
                collection.put(JSONObject().apply {
                    put("id", session.id)
                    put("name", session.name)
                    put("createdAt", session.createdAt)
                    put("frames", JSONObject().also { photos ->
                        session.frames.forEach { (position, name) ->
                            photos.put(position.toString(), name)
                        }
                    })
                })
            }
        }
        check(prefs.edit().putString("sessions", array.toString()).commit()) {
            "Falha ao salvar sessão de scanner."
        }
    }

    companion object { private const val MAX_SHOT_BYTES = 8L * 1024 * 1024 }
}
