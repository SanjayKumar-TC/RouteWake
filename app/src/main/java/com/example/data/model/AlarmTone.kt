package com.example.data.model

enum class AlarmTone(val title: String, val frequencyHz: Int) {
    RADAR_BEEP("Radar Beep", 950),
    GENTLE_BELLS("Gentle Bells", 660),
    INTUITION("Intuition", 852),
    SIREN("Emergency Siren", 880);

    companion object {
        fun fromString(name: String): AlarmTone {
            return entries.find { it.name.equals(name, ignoreCase = true) }
                ?: if (name.contains("852", ignoreCase = true) || name.contains("INTUITION", ignoreCase = true)) {
                    INTUITION
                } else {
                    RADAR_BEEP
                }
        }
    }
}
