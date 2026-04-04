package com.stasmega.strada

import androidx.compose.ui.graphics.Color
import java.io.Serializable

fun getTransportColor(type: String): Color {
    return when (type) {
        "Tram" -> Color(0xFF4CAF50)
        "Train" -> Color(0xFF2196F3)
        else -> Color(0xFFFF9800)
    }
}

data class BusInfo(
    val id: String,
    val type: String,
    val lineNumber: String,
    val latitude: Double,
    val longitude: Double,
    val bearing: Float
) : Serializable

data class GtfsRoute(
    val id: String, val shortName: String, val longName: String, val type: String, val isRegional: Boolean
) : Serializable

data class RouteInfo(
    val id: String, val number: String, val name: String, val type: String, val isRegional: Boolean
) : Serializable

data class GtfsStop(
    val id: String, val name: String, val lat: Double, val lon: Double
) : Serializable

data class RoutePoint(
    val lat: Double, val lon: Double
) : Serializable

data class RouteShape(
    val routeNumber: String, val type: String, val points: List<RoutePoint>, val stops: List<GtfsStop>
) : Serializable

data class StopTime(
    val stopId: String, val stopName: String, val arrivalTime: String, val departureTime: String, val stopSequence: Int
) : Serializable

data class TripWithStops(
    val tripId: String, val headsign: String, val stops: List<StopTime>
) : Serializable

data class RouteSchedule(
    val routeId: String, val routeNumber: String, val routeName: String, val type: String,
    val stopsDir0: List<StopTime>, val headsign0: String,
    val stopsDir1: List<StopTime>, val headsign1: String
) : Serializable

data class StopDeparture(
    val routeNumber: String, val headsign: String, val time: String, val type: String
) : Serializable

data class CalendarRule(
    val serviceId: String, val monday: Boolean, val tuesday: Boolean, val wednesday: Boolean,
    val thursday: Boolean, val friday: Boolean, val saturday: Boolean, val sunday: Boolean,
    val startDate: String, val endDate: String
) : Serializable