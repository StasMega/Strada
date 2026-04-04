package com.stasmega.strada

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleBottomSheet(
    viewModel: BusViewModel,
    onRouteClick: (String) -> Unit,
    onFocusSearch: () -> Unit
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filteredRoutes by viewModel.filteredRoutes.collectAsState()
    val recentRoutes by viewModel.recentRoutes.collectAsState()
    val favoriteRoutesIds by viewModel.favoriteRoutes.collectAsState()
    val allRoutes by viewModel.routes.collectAsState()
    val listState = rememberLazyListState()

    val favRoutes = remember(favoriteRoutesIds, allRoutes) {
        allRoutes.filter { favoriteRoutesIds.contains(it.number) }
    }

    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.95f)) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            TextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocusSearch() },
                placeholder = { Text("Куда едем?", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { viewModel.updateSearchQuery("") }) { Icon(Icons.Default.Close, null) } },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.4f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.4f),
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(16.dp), singleLine = true
            )
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 40.dp)) {
            if (searchQuery.isBlank()) {
                if (favRoutes.isNotEmpty()) {
                    item { Text("ИЗБРАННОЕ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp, top = 8.dp)) }
                    items(favRoutes, key = { "fav_${it.id}" }) { route ->
                        RouteTravelItem(route, isFavorite = true, onFavoriteClick = { viewModel.toggleFavorite(route.number) }) { viewModel.addToRecent(route); onRouteClick(route.id) }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }

                if (recentRoutes.isNotEmpty()) {
                    item { Text("НЕДАВНИЕ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp, top = 8.dp)) }
                    items(recentRoutes, key = { "recent_${it.id}" }) { route ->
                        RouteTravelItem(route, isFavorite = favoriteRoutesIds.contains(route.number), onFavoriteClick = { viewModel.toggleFavorite(route.number) }) { viewModel.addToRecent(route); onRouteClick(route.id) }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }

                item { Text("ВСЕ МАРШРУТЫ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)) }
            }
            items(filteredRoutes, key = { it.id }) { route ->
                RouteTravelItem(route, isFavorite = favoriteRoutesIds.contains(route.number), onFavoriteClick = { viewModel.toggleFavorite(route.number) }) { viewModel.addToRecent(route); onRouteClick(route.id) }
            }
        }
    }
}

@Composable
fun RouteTravelItem(route: RouteInfo, isFavorite: Boolean, onFavoriteClick: () -> Unit, onClick: () -> Unit) {
    val color = when {
        route.type == "Tram" -> Color(0xFFFF3025)
        route.isRegional -> Color(0xFF2790E6)
        route.type == "Train" -> Color(0xFFFF6000)
        else -> Color(0xFF2E7D32)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { onClick() },
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Color.Black.copy(0.04f))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).background(color.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) { Text(route.number, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(route.name.ifBlank{"Маршрут ${route.number}"}, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if(route.type=="Tram") "Трамвай" else if(route.type=="Train") "Поезд" else "Автобус", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            IconButton(onClick = onFavoriteClick) {
                Icon(if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = "Favorite", tint = if (isFavorite) Color(0xFFFFD700) else Color.LightGray)
            }
        }
    }
}