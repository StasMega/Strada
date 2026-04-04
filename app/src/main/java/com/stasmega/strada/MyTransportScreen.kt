package com.stasmega.strada

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTransportScreen(viewModel: BusViewModel, onBack: () -> Unit) {
    val favorites by viewModel.favoriteRoutesList.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My transport", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (favorites.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No favorite routes yet", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(favorites, key = { it.id }) { route ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = {
                            if (it == SwipeToDismissBoxValue.EndToStart) {
                                viewModel.toggleFavorite(route.number)
                                true
                            } else false
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            val color by animateColorAsState(
                                when (dismissState.targetValue) {
                                    SwipeToDismissBoxValue.EndToStart -> Color(0xFFFF6B6B) // Красный как в будильнике
                                    else -> Color.Transparent
                                }
                            )
                            Box(
                                Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(Icons.Default.Delete, "Delete", tint = Color.White)
                            }
                        }
                    ) {
                        // Используем твой стандартный RouteTravelItem, но без кнопки звезды (т.к. мы в списке избранного)
                        RouteTravelItem(
                            route = route,
                            isFavorite = true,
                            onFavoriteClick = { viewModel.toggleFavorite(route.number) },
                            onClick = { /* Можно открыть детали */ }
                        )
                    }
                }
            }
        }
    }
}