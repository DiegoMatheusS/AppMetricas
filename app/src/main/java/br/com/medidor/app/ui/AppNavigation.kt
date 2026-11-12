package br.com.medidor.app.ui

internal object AppNavigation {
    fun open(history: List<String>, destination: Destination): List<String> = when {
        history.lastOrNull() == destination.name -> history
        destination == Destination.HOME -> listOf(destination.name)
        else -> history + destination.name
    }

    fun back(history: List<String>): List<String> =
        history.dropLast(1).ifEmpty { listOf(Destination.HOME.name) }
}
