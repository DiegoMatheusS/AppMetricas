package br.com.medidor.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.AtomicFile
import br.com.medidor.app.domain.ArchitectPlan
import br.com.medidor.app.domain.PlanFloor
import br.com.medidor.app.domain.PlanPoint
import br.com.medidor.app.domain.PlanWall
import br.com.medidor.app.domain.PlanOpening
import br.com.medidor.app.domain.OpeningType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Versioned documents in transactional SQLite slots; imported images use
 * atomic, app-private files. Existing preferences are retained during migration.
 */
class ArchitectPlanStore(private val context: Context) {
    private val prefs = LocalDocuments(context, "architect_plan_v1")
    private val folder = File(context.filesDir, "architect").apply { mkdirs() }

    fun load(): ArchitectPlan {
        return try {
        val raw = prefs.getString("plan", null) ?: return ArchitectPlan()
        val obj = JSONObject(raw)
        val items = obj.getJSONArray("floors")
        val floors = (0 until items.length()).map { i ->
            val f = items.getJSONObject(i)
            val wallsJson = f.optJSONArray("walls") ?: JSONArray()
            val walls = (0 until wallsJson.length()).map { j ->
                val wall = wallsJson.getJSONObject(j)
                PlanWall(point(wall.getJSONObject("a"))!!, point(wall.getJSONObject("b"))!!)
            }
            val openingsJson = f.optJSONArray("openings") ?: JSONArray()
            val openings = (0 until openingsJson.length()).mapNotNull { j ->
                runCatching {
                    val op = openingsJson.getJSONObject(j)
                    PlanOpening(
                        wallIndex = op.getInt("wall"),
                        type = OpeningType.valueOf(op.getString("type")),
                        centerFraction = op.getDouble("center"),
                        widthMeters = op.getDouble("width"),
                        sillMeters = op.getDouble("sill"),
                        heightMeters = op.getDouble("height")
                    )
                }.getOrNull()
            }
            PlanFloor(
                title = f.optString("title", "Andar ${i + 1}"),
                imageFile = f.optString("image").ifBlank { null },
                imageWidth = f.optInt("width"),
                imageHeight = f.optInt("height"),
                knownMeters = f.optDouble("knownMeters", 4.0),
                referenceStart = f.optJSONObject("refA")?.let(::point),
                referenceEnd = f.optJSONObject("refB")?.let(::point),
                wallHeightMeters = f.optDouble("wallHeightMeters", 2.7),
                walls = walls,
                openings = openings,
                offsetXMeters = f.optDouble("offsetXMeters", 0.0).takeIf { it.isFinite() } ?: 0.0,
                offsetYMeters = f.optDouble("offsetYMeters", 0.0).takeIf { it.isFinite() } ?: 0.0,
                alignmentPoint = f.optJSONObject("alignmentPoint")?.let(::point),
                secondAlignmentPoint = f.optJSONObject("secondAlignmentPoint")?.let(::point),
                rotationDegrees = f.optDouble("rotationDegrees", 0.0)
                    .takeIf { it.isFinite() && it in -180.0..180.0 } ?: 0.0
            )
        }
        if (floors.isEmpty()) ArchitectPlan() else ArchitectPlan(
            floors, obj.optInt("selected", 0).coerceIn(floors.indices)
        )
        } catch (_: Exception) {
            ArchitectPlan()
        }
    }

    fun save(plan: ArchitectPlan) {
        require(plan.floors.isNotEmpty())
        val obj = JSONObject().apply {
            put("version", 5)
            put("selected", plan.selectedFloor)
            put("floors", JSONArray().also { arr ->
                plan.floors.forEach { f ->
                    arr.put(JSONObject().apply {
                        put("title", f.title)
                        put("image", f.imageFile ?: "")
                        put("width", f.imageWidth)
                        put("height", f.imageHeight)
                        put("knownMeters", f.knownMeters)
                        put("wallHeightMeters", f.wallHeightMeters)
                        put("offsetXMeters", f.offsetXMeters)
                        put("offsetYMeters", f.offsetYMeters)
                        put("rotationDegrees", f.rotationDegrees)
                        if (f.secondAlignmentPoint != null) {
                            put("secondAlignmentPoint", pointJson(f.secondAlignmentPoint))
                        }
                        if (f.alignmentPoint != null) put("alignmentPoint", pointJson(f.alignmentPoint))
                        if (f.referenceStart != null) put("refA", pointJson(f.referenceStart))
                        if (f.referenceEnd != null) put("refB", pointJson(f.referenceEnd))
                        put("walls", JSONArray().also { walls ->
                            f.walls.forEach { wall ->
                                walls.put(JSONObject().apply {
                                    put("a", pointJson(wall.start))
                                    put("b", pointJson(wall.end))
                                })
                            }
                        })
                        put("openings", JSONArray().also { openings ->
                            f.openings.forEach { op ->
                                openings.put(JSONObject().apply {
                                    put("wall", op.wallIndex)
                                    put("type", op.type.name)
                                    put("center", op.centerFraction)
                                    put("width", op.widthMeters)
                                    put("sill", op.sillMeters)
                                    put("height", op.heightMeters)
                                })
                            }
                        })
                    })
                }
            })
        }
        check(prefs.edit().putString("plan", obj.toString()).commit()) {
            "Não foi possível salvar o projeto localmente"
        }
    }

    fun saveImage(bitmap: Bitmap): String {
        // Unique file name; never overwrite a shared image used by a duplicated floor.
        val fileName = "planta_${System.currentTimeMillis()}_${SystemClock.elapsedRealtimeNanos()}.jpg"
        val file = File(folder, fileName)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) { "Falha ao salvar imagem" }
            atomic.finishWrite(stream)
            check(file.isFile && file.length() > 0) { "Não foi possível concluir a gravação da planta." }
        } catch (error: Exception) { atomic.failWrite(stream); throw error }
        return fileName
    }

    fun readImage(fileName: String?): Bitmap? {
        if (fileName == null || fileName != File(fileName).name) return null
        val file = File(folder, fileName)
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun pointJson(p: PlanPoint) = JSONObject().apply {
        put("x", p.x.toDouble())
        put("y", p.y.toDouble())
    }

    private fun point(p: JSONObject): PlanPoint? = try {
        PlanPoint(p.getDouble("x").toFloat(), p.getDouble("y").toFloat())
    } catch (_: Exception) { null }
}
