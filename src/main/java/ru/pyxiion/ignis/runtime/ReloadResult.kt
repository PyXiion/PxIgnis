package ru.pyxiion.ignis.runtime

/**
 * Outcome of [ru.pyxiion.ignis.IgnisRuntime.reload].
 *
 * @property applied false when the reload was cancelled because of compile errors and the previous scripts kept running.
 * @property errors one "file: message" entry per script that failed to compile or run.
 */
data class ReloadResult(val total: Int, val errors: List<String>, val applied: Boolean) {
    val ok: Boolean get() = errors.isEmpty()

    fun summary(): String = when {
        ok -> "Перезагрузилось ($total скр.)"
        !applied -> "Перезагрузка отменена, работают старые скрипты. Ошибки компиляции:\n" + errors.joinToString("\n")
        else -> "Перезагрузилось с ошибками (${errors.size} из $total):\n" + errors.joinToString("\n")
    }
}
