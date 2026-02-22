package com.stasmega.strada

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

/**
 * Компонент для отображения одной записи расписания
 * Используется в BottomSheet остановок
 */
@Composable
fun DepartureItem(departure: StopDeparture, color: Color) {
    val icon = when (departure.type) {
        "Tram" -> Icons.Default.Tram
        "Train" -> Icons.Default.Train
        else -> Icons.Default.DirectionsBus
    }
    
    val formattedTime = departure.time.substringBeforeLast(":")
    val relativeTime = calculateMinutesUntil(departure.time)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Иконка транспорта
        Surface(
            color = color,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        
        Spacer(Modifier.width(16.dp))
        
        // Номер маршрута и направление
        Column(Modifier.weight(1f)) {
            Text(
                departure.routeNumber,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                departure.headsign,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
        }
        
        // Время отправления
        Column(horizontalAlignment = Alignment.End) {
            relativeTime?.let {
                Text(
                    it,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                formattedTime,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Вычисляет относительное время до отправления
 * Возвращает "сейчас", "через X мин" или null если отправление не скоро
 */
@Composable
fun calculateMinutesUntil(departureTime: String): String? {
    try {
        val now = Calendar.getInstance()
        val nowTotal = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        
        val parts = departureTime.split(":")
        if (parts.size < 2) return null
        
        val depTotal = parts[0].toInt() * 60 + parts[1].toInt()
        val diff = depTotal - nowTotal
        
        return when {
            diff < 0 -> null // Уже ушел
            diff == 0 -> "сейчас"
            diff < 60 -> "через $diff мин"
            else -> null // Больше часа - не показываем
        }
    } catch (e: Exception) {
        return null
    }
}
