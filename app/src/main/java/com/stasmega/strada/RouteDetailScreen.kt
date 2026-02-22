package com.stasmega.strada

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(
    viewModel: BusViewModel,
    routeNumber: String,
    onBack: () -> Unit
) {
    val scheduleState by viewModel.scheduleState.collectAsState()
    val stopDepartures by viewModel.stopDepartures.collectAsState()
    var selectedDirection by remember { mutableIntStateOf(0) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var selectedStopName by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    val boldFont = FontFamily(Font(R.font.google_sans_bold, FontWeight.Bold))

    LaunchedEffect(routeNumber) {
        viewModel.loadRouteSchedule(routeNumber)
    }

    LaunchedEffect(showBottomSheet) {
        if (!showBottomSheet) {
            viewModel.clearStopDepartures()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (scheduleState is ScheduleState.Success) {
                        val sch = (scheduleState as ScheduleState.Success).schedule
                        Text("${sch.type} ${sch.routeNumber}", fontFamily = boldFont)
                    } else {
                        Text("Загрузка...")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = scheduleState) {
            is ScheduleState.Loading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(state.progress)
                    }
                }
            }
            is ScheduleState.Error -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is ScheduleState.Success -> {
                val schedule = state.schedule
                val currentStops = if (selectedDirection == 0) schedule.stopsDir0 else schedule.stopsDir1
                // Используем глобальную функцию
                val transportColor = getTransportColor(schedule.type)

                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (schedule.stopsDir1.isNotEmpty()) {
                        TabRow(selectedTabIndex = selectedDirection) {
                            Tab(
                                selected = selectedDirection == 0,
                                onClick = { selectedDirection = 0 },
                                text = { Text("На: ${schedule.headsign0}", maxLines = 1) }
                            )
                            Tab(
                                selected = selectedDirection == 1,
                                onClick = { selectedDirection = 1 },
                                text = { Text("На: ${schedule.headsign1}", maxLines = 1) }
                            )
                        }
                    }

                    LazyColumn(contentPadding = PaddingValues(16.dp)) {
                        items(currentStops) { stop ->
                            RouteStopItem(
                                stopName = stop.stopName,
                                isFirst = stop.stopSequence == currentStops.first().stopSequence,
                                isLast = stop.stopSequence == currentStops.last().stopSequence,
                                color = transportColor,
                                onClick = {
                                    selectedStopName = stop.stopName
                                    viewModel.loadDeparturesForStop(schedule.routeId, stop.stopId, selectedDirection)
                                    showBottomSheet = true
                                }
                            )
                        }
                    }
                }
            }
        }

        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState,
                modifier = Modifier.fillMaxHeight(0.85f)
            ) {
                val listState = rememberLazyListState()
                LaunchedEffect(stopDepartures) {
                    if (stopDepartures.isNotEmpty()) {
                        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                        val index = stopDepartures.indexOfFirst { it.time >= currentTime }
                        if (index >= 0) listState.scrollToItem(index)
                    }
                }

                Column {
                    Text(
                        selectedStopName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = boldFont,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                    if (stopDepartures.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            Text("Нет рейсов на сегодня", color = MaterialTheme.colorScheme.outline)
                        }
                    } else {
                        LazyColumn(state = listState) {
                            items(stopDepartures) { departure ->
                                DepartureItem(departure, getTransportColor(departure.type))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RouteStopItem(stopName: String, isFirst: Boolean, isLast: Boolean, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { onClick() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Canvas(Modifier.fillMaxSize()) {
                val centerX = size.width / 2
                val centerY = size.height / 2
                if (!isFirst) drawLine(color, Offset(centerX, -size.height), Offset(centerX, centerY), 16f, StrokeCap.Butt)
                if (!isLast) drawLine(color, Offset(centerX, centerY), Offset(centerX, size.height * 2), 16f, StrokeCap.Butt)
                drawCircle(color, 24f, Offset(centerX, centerY))
                drawCircle(Color.White, 10f, Offset(centerX, centerY))
            }
        }
        Text(stopName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp))
    }
}