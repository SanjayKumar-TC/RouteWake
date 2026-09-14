package com.example.data.model

enum class TransportMode(val label: String, val fallbackSpeedKmh: Double) {
    WALK("Walk", 5.0),
    CYCLE("Cycle", 15.0),
    BUS("Bus", 25.0),
    TRAIN("Train", 40.0),
    CAR("Car", 50.0);

    companion object {
        fun fromString(value: String): TransportMode {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: CAR
        }
    }
}
