package br.com.medidor.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ArchitectProjectItem(val id: String, val name: String)

/**
 * Non-destructive multi-project layer over ArchitectPlanStore's original
 * "plan" document slot. That slot is always the active document,
 * including for the AR Activity, while inactive documents are snapshots.
 *
 * The old single-document key is NOT deleted during migration. Project
 * switching copies the active JSON and changes the active ID in one commit.
 * Project images are app-private and shared by duplicates, so deleting a
 * project intentionally leaves orphaned image files for a later safe GC.
 */
class ArchitectProjectManager(context: Context) {
    private val prefs = LocalDocuments(context, "architect_plan_v1")
    private val indexKey = "project_index_v1"
    private val activeKey = "active_project_v1"
    private fun archive(id: String) = "project_snapshot_$id"

    private fun parseIndex(json: String): List<ArchitectProjectItem> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            ArchitectProjectItem(entry.getString("id"), entry.getString("name"))
        }
    }

    private fun serializeIndex(items: List<ArchitectProjectItem>): String =
        JSONArray().also { array ->
            items.forEach { item ->
                array.put(JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                })
            }
        }.toString()

    private fun initialize() = prefs.transaction {
        if (prefs.contains(indexKey)) return@transaction
        val initial = listOf(ArchitectProjectItem("default", "Meu primeiro projeto"))
        check(prefs.edit().putString(indexKey, serializeIndex(initial))
            .putString(activeKey, "default").commit()) {
            "Não foi possível preparar a lista de projetos."
        }
    }

    fun list(): List<ArchitectProjectItem> {
        initialize()
        return parseIndex(requireNotNull(prefs.getString(indexKey, null)))
    }

    fun activeId(): String {
        val items = list()
        val value = prefs.getString(activeKey, "default")
        return items.firstOrNull { it.id == value }?.id
            ?: error("Projeto ativo não encontrado: $value")
    }

    private fun cleanName(value: String): String =
        value.trim().also { require(it.isNotBlank() && it.length <= 60) {
            "Use um nome de projeto entre 1 e 60 caracteres."
        } }

    fun rename(id: String, name: String) = prefs.transaction {
        val safe = cleanName(name)
        val items = list()
        require(items.any { it.id == id }) { "Projeto não encontrado." }
        val updated = items.map { if (it.id == id) it.copy(name = safe) else it }
        check(prefs.edit().putString(indexKey, serializeIndex(updated)).commit()) {
            "Não foi possível renomear o projeto."
        }
    }

    /** Creates a new empty project or an exact copy of the current project's JSON. */
    fun create(name: String, copyCurrent: Boolean): ArchitectProjectItem = prefs.transaction {
        val safe = cleanName(name)
        val items = list()
        require(items.size < 20) { "Limite de 20 projetos locais atingido." }
        val previous = activeId()
        val next = ArchitectProjectItem(UUID.randomUUID().toString(), safe)
        val currentPayload = prefs.getString("plan", null)
        val editor = prefs.edit()
            .putString(indexKey, serializeIndex(items + next))
            .putString(activeKey, next.id)
        if (currentPayload == null) editor.remove(archive(previous))
        else editor.putString(archive(previous), currentPayload)
        if (copyCurrent && currentPayload != null) editor.putString("plan", currentPayload)
        else editor.remove("plan")
        check(editor.commit()) { "Não foi possível criar o projeto." }
        next
    }

    fun select(id: String) = prefs.transaction {
        if (id == activeId()) return@transaction
        val items = list()
        require(items.any { it.id == id }) { "Projeto não encontrado." }
        val previous = activeId()
        val currentPayload = prefs.getString("plan", null)
        val nextPayload = prefs.getString(archive(id), null)
        val editor = prefs.edit().putString(activeKey, id)
        if (currentPayload == null) editor.remove(archive(previous))
        else editor.putString(archive(previous), currentPayload)
        if (nextPayload == null) editor.remove("plan")
        else editor.putString("plan", nextPayload)
        check(editor.commit()) { "Não foi possível abrir o projeto." }
    }

    fun delete(id: String) = prefs.transaction {
        val items = list()
        require(items.size > 1) { "Não é permitido apagar o último projeto." }
        require(items.any { it.id == id }) { "Projeto não encontrado." }
        val updated = items.filterNot { it.id == id }
        val editor = prefs.edit()
            .putString(indexKey, serializeIndex(updated))
            .remove(archive(id))
        if (id == activeId()) {
            val next = updated.first()
            val snapshot = prefs.getString(archive(next.id), null)
            editor.putString(activeKey, next.id)
            if (snapshot != null) editor.putString("plan", snapshot)
            else editor.remove("plan")
        }
        check(editor.commit()) { "Não foi possível excluir o projeto." }
    }
}
