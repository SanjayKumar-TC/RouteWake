package com.example.data.model

data class Destination(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val category: String = "Custom"
)
