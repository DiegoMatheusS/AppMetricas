package br.com.medidor.app.data

import android.content.Context
import android.graphics.BitmapFactory
import br.com.medidor.app.domain.BackupLimits
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Explicit offline backups, selected through the Android system document picker.
 * Never restores arbitrary file paths: every archive entry and cross-reference is
 * checked before replacing SharedPreferences. All imported images are assigned
 * fresh app-private filenames, avoiding collisions with existing projects.
 */
class ArchitectBackupManager(private val context: Context) {
    private val projects = LocalDocuments(context, "architect_plan_v1")
    private val measurements = LocalDocuments(context, "measurements_v1")
    private val imageFolder = File(context.filesDir, "architect").apply { mkdirs() }

    fun export(target: OutputStream): Int {
        val manager = ArchitectProjectManager(context)
        val index = manager.list()
        val active = manager.activeId()
        val entries = JSONArray()
        val references = linkedSetOf<String>()
        index.forEach { project ->
            val json = projects.getString(
                if (project.id == active) "plan" else "project_snapshot_${project.id}", null
            )
            val plan = json?.let(::JSONObject)
            if (plan != null) {
                validatePlan(plan, references)
            }
            entries.put(JSONObject().apply {
                put("id", project.id)
                put("name", project.name)
                put("plan", plan ?: JSONObject.NULL)
            })
        }
        require(references.size <= BackupLimits.MAX_ENTRY_COUNT - 1) {
            "Há imagens demais para este formato de backup."
        }
        val imageFiles = references.associateWith { name ->
            require(BackupLimits.safeImageName(name)) { "Nome de imagem inválido." }
            val file = File(imageFolder, name)
            require(file.isFile && file.length() in 1..BackupLimits.MAX_IMAGE_BYTES) {
                "Imagem ausente ou muito grande: $name"
            }
            file
        }
        val sizeTotal = imageFiles.values.sumOf { it.length() }
        require(sizeTotal <= BackupLimits.MAX_TOTAL_BYTES - BackupLimits.MAX_METADATA_BYTES) {
            "Backup excede 100 MB nesta versão."
        }
        val manifest = JSONObject().apply {
            put("schema", 1)
            put("active", active)
            put("projects", entries)
            put("measurements", JSONArray(measurements.getString("records", "[]") ?: "[]"))
            put("images", JSONArray().also { array ->
                imageFiles.forEach { (name, file) ->
                    array.put(JSONObject().apply {
                        put("name", name)
                        put("size", file.length())
                        put("sha256", digest(file))
                    })
                }
            })
        }
        val metadata = manifest.toString().toByteArray(Charsets.UTF_8)
        require(metadata.size <= BackupLimits.MAX_METADATA_BYTES) { "Metadados muito grandes." }
        ZipOutputStream(target).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(metadata)
            zip.closeEntry()
            imageFiles.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry("images/$name"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return index.size
    }

    /**
     * Replacement restore, *never* an implicit merge. Call only after an explicit
     * confirmation. The archive is validated and extracted to cache first; the
     * existing project index is untouched until the entire archive passes.
     */
    fun restore(source: InputStream): Int {
        val staging = File(context.cacheDir, "restore_${UUID.randomUUID()}").apply {
            check(mkdirs()) { "Não foi possível preparar a restauração." }
        }
        val installed = mutableListOf<File>()
        try {
            val collected = BackupZipReader.extract(source, staging)
            val manifestFile = requireNotNull(collected["manifest.json"]) {
                "Manifesto do backup não encontrado."
            }
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            require(manifest.getInt("schema") == 1) { "Formato de backup incompatível." }
            val inputProjects = manifest.getJSONArray("projects")
            require(inputProjects.length() in 1..BackupLimits.MAX_PROJECTS) {
                "Quantidade inválida de projetos."
            }
            val active = manifest.getString("active")
            val names = mutableSetOf<String>()
            val referencedImages = linkedSetOf<String>()
            val plans = (0 until inputProjects.length()).map { index ->
                val entry = inputProjects.getJSONObject(index)
                val originalId = entry.getString("id")
                require(originalId.isNotBlank() && originalId.length <= 90 && names.add(originalId)) {
                    "Identificadores duplicados no backup."
                }
                val name = entry.getString("name")
                require(name == name.trim() && name.length in 1..60) {
                    "Nome de projeto inválido."
                }
                val plan = if (entry.isNull("plan")) null else entry.getJSONObject("plan")
                if (plan != null) validatePlan(plan, referencedImages)
                Triple(originalId, name, plan)
            }
            require(names.contains(active)) { "Projeto ativo inexistente no backup." }

            val listedImages = manifest.getJSONArray("images")
            val received = mutableSetOf<String>()
            require(listedImages.length() == referencedImages.size) {
                "O backup não contém todas as imagens referenciadas."
            }
            for (i in 0 until listedImages.length()) {
                val item = listedImages.getJSONObject(i)
                val name = item.getString("name")
                require(BackupLimits.safeImageName(name) && received.add(name) &&
                    name in referencedImages) { "Lista de imagens inválida." }
                val file = requireNotNull(collected["images/$name"]) {
                    "Fotografia ausente no backup: $name"
                }
                require(file.length() == item.getLong("size") &&
                    digest(file).equals(item.getString("sha256"), ignoreCase = true)) {
                    "Imagem alterada ou corrompida: $name"
                }
                val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, dimensions)
                require(dimensions.outWidth in 1..8192 && dimensions.outHeight in 1..8192) {
                    "Imagem JPEG inválida ou com resolução excessiva."
                }
                // The JPEG header and each floor's stated dimensions must agree.
                plans.forEach { (_, _, project) ->
                    if (project != null) {
                        val floors = project.getJSONArray("floors")
                        for (floorIndex in 0 until floors.length()) {
                            val floor = floors.getJSONObject(floorIndex)
                            if (floor.optString("image") == name) {
                                require(floor.optInt("width") == dimensions.outWidth &&
                                    floor.optInt("height") == dimensions.outHeight) {
                                    "Dimensões da planta incompatíveis com a imagem: $name"
                                }
                            }
                        }
                    }
                }
            }
            require(received == referencedImages && collected.size == received.size + 1) {
                "Backup contém arquivos adicionais ou está incompleto."
            }
            val savedMeasurements = manifest.getJSONArray("measurements")
            require(savedMeasurements.length() <= 100) { "Histórico excede o limite." }
            for (i in 0 until savedMeasurements.length()) {
                val record = savedMeasurements.getJSONObject(i)
                require(record.getString("title").length <= 200 &&
                    record.getString("details").length <= 2000 &&
                    record.getString("createdAt").length <= 100) {
                    "Registro de medição inválido."
                }
            }

            // Fresh names avoid overwriting images held by an older/duplicated project.
            val newImages = received.associateWith { "planta_${UUID.randomUUID()}.jpg" }
            for ((oldName, newName) in newImages) {
                val destination = File(imageFolder, newName)
                check(!destination.exists()) { "Nome de arquivo duplicado." }
                installed += destination
                java.io.FileOutputStream(destination).use { output ->
                    collected.getValue("images/$oldName").inputStream().use { it.copyTo(output) }
                    output.fd.sync()
                }
            }
            // Re-map floor image references before writing any project preferences.
            val newIds = plans.associate { it.first to UUID.randomUUID().toString() }
            val normalizedProjects = plans.map { (oldId, name, original) ->
                val updated = original?.let { JSONObject(it.toString()) }
                if (updated != null) {
                    val floors = updated.getJSONArray("floors")
                    for (i in 0 until floors.length()) {
                        val floor = floors.getJSONObject(i)
                        val image = floor.optString("image")
                        if (image.isNotEmpty()) floor.put("image", newImages.getValue(image))
                    }
                }
                Triple(newIds.getValue(oldId), name, updated)
            }
            val targetId = newIds.getValue(active)
            val index = JSONArray()
            val editor = projects.edit().clear()
            for ((id, name, plan) in normalizedProjects) {
                index.put(JSONObject().apply { put("id", id); put("name", name) })
                val key = if (id == targetId) "plan" else "project_snapshot_$id"
                if (plan != null) editor.putString(key, plan.toString())
            }
            editor.putString("project_index_v1", index.toString())
                .putString("active_project_v1", targetId)
            check(projects.commitTogether(editor,
                measurements.edit().putString("records", savedMeasurements.toString()))) {
                "Falha ao restaurar os dados. Os projetos e o histórico anteriores foram preservados."
            }
            return normalizedProjects.size
        } catch (error: Exception) {
            installed.forEach { it.delete() }
            throw error
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun validatePlan(plan: JSONObject, images: MutableSet<String>) {
        val floors = plan.getJSONArray("floors")
        require(floors.length() in 1..6 &&
            plan.optInt("selected", 0) in 0 until floors.length()) { "Planta inválida." }
        for (i in 0 until floors.length()) {
            val floor = floors.getJSONObject(i)
            require(floor.getString("title").length in 1..100) { "Pavimento inválido." }
            val image = floor.optString("image")
            if (image.isNotEmpty()) {
                require(BackupLimits.safeImageName(image)) { "Caminho de imagem inválido." }
                images += image
            }
            val width = floor.optInt("width")
            val height = floor.optInt("height")
            require(width in 0..8192 && height in 0..8192) { "Resolução inválida." }
            if (image.isNotEmpty()) require(width > 0 && height > 0)
            for (name in listOf("refA","refB","alignmentPoint","secondAlignmentPoint")) {
                if (floor.has(name) && !floor.isNull(name)) validatePoint(floor.getJSONObject(name))
            }
            val walls = floor.optJSONArray("walls") ?: JSONArray()
            val openings = floor.optJSONArray("openings") ?: JSONArray()
            require(walls.length() <= 1000 && openings.length() <= 2000) {
                "Pavimento possui elementos em excesso."
            }
            for (j in 0 until walls.length()) {
                val wall = walls.getJSONObject(j)
                validatePoint(wall.getJSONObject("a"))
                validatePoint(wall.getJSONObject("b"))
            }
            for (j in 0 until openings.length()) {
                val opening = openings.getJSONObject(j)
                require(opening.getInt("wall") in 0 until walls.length() &&
                    opening.getString("type") in listOf("DOOR", "WINDOW") &&
                    opening.getDouble("center").isFinite() &&
                    opening.getDouble("width").isFinite() &&
                    opening.getDouble("height").isFinite() &&
                    opening.getDouble("sill").isFinite()) {
                    "Porta ou janela inválida."
                }
            }
        }
    }

    private fun validatePoint(obj: JSONObject) {
        require(obj.getDouble("x") in 0.0..1.0 && obj.getDouble("y") in 0.0..1.0) {
            "Coordenada fora da planta."
        }
    }

    private fun digest(file: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                d.update(bytes, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
