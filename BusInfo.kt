package com.stasmega.strada

import org.osmdroid.util.GeoPoint

data class BusInfo(
    val type: String,
    val lineNumber: String,
    val location: GeoPoint,
    val bearing: Float
)