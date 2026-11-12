package br.com.medidor.app.data

import android.content.Context
import br.com.medidor.app.domain.MeasurementPointSize

internal class MeasurementPreferences(context: Context) {
    private val store = LocalDocuments(context, "measurement_options_v1")
    var pointSize: MeasurementPointSize
        get() = MeasurementPointSize.entries.firstOrNull { it.name == store.getString("point_size", null) }
            ?: MeasurementPointSize.NORMAL
        set(value) { check(store.edit().putString("point_size", value.name).commit()) }
}
