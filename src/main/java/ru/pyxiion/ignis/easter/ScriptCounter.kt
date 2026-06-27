package ru.pyxiion.ignis.easter

object ScriptCounter {
    private val milestones = listOf(1, 10, 25, 50, 100, 250, 500)
    var count = 0
        private set
    var lastMilestone = 0
        private set

    fun reset() {
        count = 0
    }

    fun add(n: Int) {
        count += n
    }

    fun pollMessage(): String? {
        val threshold = milestones.lastOrNull { count >= it && it > lastMilestone } ?: return null
        lastMilestone = threshold
        return when (threshold) {
            1 -> "First script loaded! Let the chaos begin!"
            10 -> "OMG YOU LOADED $count SCRIPTS! I AM VERY IMPRESSED!"
            25 -> "$count scripts! Is this a modpack yet?"
            50 -> "$count SCRIPTS! I WORK COMPLETELY FINE!"
            100 -> "$count scripts! I have no idea what I'm doing."
            250 -> "$count scripts. We're in too deep."
            500 -> "$count SCRIPTS! Someone call the fire department!"
            else -> "$threshold scripts loaded!"
        }
    }
}
