package com.example.data.model

enum class AlarmTone(val title: String, val frequencyHz: Int) {
    RADAR_BEEP("Radar Beep", 950),
    GENTLE_BELLS("Gentle Bells", 660),
    HZ_396("396 Hz (Liberation)", 396),
    HZ_417("417 Hz (Change)", 417),
    HZ_528("528 Hz (Transformation)", 528),
    HZ_639("639 Hz (Connection)", 639),
    HZ_741("741 Hz (Awakening)", 741),
    HZ_852("852 Hz (Intuition)", 852),
    SIREN("Emergency Siren", 880);

    companion object {
        fun fromString(name: String): AlarmTone {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: RADAR_BEEP
        }
    }
}
