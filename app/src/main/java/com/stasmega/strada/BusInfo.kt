package com.stasmega.strada


import androidx.compose.ui.graphics.Color

fun getTransportColor(type: String): Color {
    return when (type) {
        "Tram" -> Color(0xFF4CAF50)  // Зеленый для трамваев
        "Train" -> Color(0xFF2196F3) // Синий для поездов
        else -> Color(0xFFFF9800)    // Оранжевый для автобусов
    }
}
data class BusInfo(
    val id: String,
    val type: String,
    val lineNumber: String,
    val latitude: Double,
    val longitude: Double,
    val bearing: Float
)

data class GtfsRoute(
    val id: String,
    val shortName: String,
    val longName: String,
    val type: String,
    val isRegional: Boolean
)

data class RouteInfo(
    val id: String,         // ВАЖНО: Уникальный ID (tlt-bus-1)
    val number: String,     // Визуальный номер (1)
    val name: String,
    val type: String,
    val isRegional: Boolean
)

data class GtfsStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double
)

// Простой класс для координат (БЕЗ зависимости от MapLibre)
data class RoutePoint(
    val lat: Double,
    val lon: Double
)

// Геометрия маршрута
data class RouteShape(
    val routeNumber: String,
    val type: String,
    val points: List<RoutePoint>,
    val stops: List<GtfsStop>
)

// НОВЫЕ МОДЕЛИ ДЛЯ РАСПИСАНИЯ
data class StopTime(
    val stopId: String,
    val stopName: String,
    val arrivalTime: String,
    val departureTime: String,
    val stopSequence: Int
)

data class TripWithStops(
    val tripId: String,
    val headsign: String,
    val stops: List<StopTime>
)

data class RouteSchedule(
    val routeId: String,
    val routeNumber: String,
    val routeName: String,
    val type: String,
    val stopsDir0: List<StopTime>,
    val headsign0: String,
    val stopsDir1: List<StopTime>,
    val headsign1: String
)

data class StopDeparture(
    val routeNumber: String,
    val headsign: String,
    val time: String,
    val type: String // "Bus", "Tram", etc.
)

data class CalendarRule(
    val serviceId: String,
    val monday: Boolean,
    val tuesday: Boolean,
    val wednesday: Boolean,
    val thursday: Boolean,
    val friday: Boolean,
    val saturday: Boolean,
    val sunday: Boolean,
    val startDate: String,
    val endDate: String
)