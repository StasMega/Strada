package com.stasmega.strada

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(viewModel: BusViewModel, onRouteClick: (String) -> Unit) {
    val isDark = isSystemInDarkTheme() || viewModel.themeMode.intValue == AppCompatDelegate.MODE_NIGHT_YES
    val isMonet = viewModel.isMonetEnabled.value

    val headerColor = when {
        isMonet -> MaterialTheme.colorScheme.primary
        isDark -> MaterialTheme.colorScheme.surface
        else -> StradaBlue
    }

    var searchQuery by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(false) }
    val allRoutes by viewModel.routes.collectAsState()
    val recentRoutes by viewModel.recentRoutes.collectAsState()
    val loadingStatus by viewModel.loadingStatus.collectAsState()
    val listState = rememberLazyListState()

    val filteredRoutes by remember(searchQuery, allRoutes) {
        derivedStateOf {
            if (searchQuery.isBlank()) allRoutes
            else allRoutes.filter { it.number.contains(searchQuery, true) || it.name.contains(searchQuery, true) }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 240.dp, bottom = 100.dp)) {
            items(filteredRoutes, key = { it.id }) { route ->
                RouteTravelItem(route) { viewModel.addToRecent(route); onRouteClick(route.id) }
            }
        }

        if (active) {
            Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { active = false }) })
        }

        Surface(modifier = Modifier.fillMaxWidth(), color = headerColor, shadowElevation = if (isDark && !isMonet) 0.dp else 8.dp) {
            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 20.dp)) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp)) {
                    Text("Куда едем?", style = MaterialTheme.typography.displaySmall, color = Color.White)
                    Text("Весь транспорт Таллина под рукой", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(0.7f))
                }

                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    DockedSearchBar(
                        modifier = Modifier.fillMaxWidth(),
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { active = false },
                        active = active,
                        onActiveChange = { active = it },
                        placeholder = { Text("Номер маршрута или остановка") },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null) } },
                        shape = RoundedCornerShape(24.dp),
                        colors = SearchBarDefaults.colors(containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(0.4f) else Color.White)
                    ) {
                        if (searchQuery.isBlank() && recentRoutes.isNotEmpty()) {
                            Text("НЕДАВНИЕ ПОИСКИ", style = MaterialTheme.typography.labelLarge,  color = MaterialTheme.colorScheme.primary,  letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
                            recentRoutes.forEach { route ->
                                ListItem(headlineContent = { Text(route.name) }, supportingContent = { Text("№${route.number}") }, leadingContent = { Icon(Icons.Default.History, null) }, modifier = Modifier.clickable { active = false; viewModel.addToRecent(route); onRouteClick(route.id) })
                            }
                        } else {
                            filteredRoutes.take(10).forEach { route ->
                                ListItem(headlineContent = { Text(route.name) }, supportingContent = { Text("№${route.number}") }, modifier = Modifier.clickable { active = false; viewModel.addToRecent(route); onRouteClick(route.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RouteTravelItem(route: RouteInfo, onClick: () -> Unit) {
    val color = when {
        route.type == "Tram" -> Color(0xFFFF3025)
        route.isRegional -> Color(0xFF2790E6)
        route.type == "Train" -> Color(0xFFFF6000)
        else -> Color(0xFF2E7D32)
    }
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).bounceClick().clickable { onClick() }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Color.Black.copy(0.04f))) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).background(color.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) { Text(route.number, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { Text(route.name.ifBlank{"Маршрут ${route.number}"}, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if(route.type=="Tram") "Трамвай" else if(route.type=="Train") "Поезд" else "Автобус", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.LightGray, modifier = Modifier.size(20.dp))
        }
    }
}