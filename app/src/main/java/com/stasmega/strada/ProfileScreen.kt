package com.stasmega.strada

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: BusViewModel, onBack: () -> Unit, onNavigateToMyTransport: () -> Unit) {
    val isDark = isSystemInDarkTheme() || viewModel.themeMode.intValue == AppCompatDelegate.MODE_NIGHT_YES
    val bgColor = if (isDark) StradaCharcoal else Color(0xFFEFEFEF)
    val cardColor = if (isDark) Color(0xFF45484F) else Color(0xFFC7C7C7)


    var showSettingsSheet by remember { mutableStateOf(false) }
    var showUpdateModeSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val loadingStatus by viewModel.loadingStatus.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.CenterStart) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", modifier = Modifier.size(28.dp)) }
            }

            Spacer(Modifier.height(32.dp))

            Surface(shape = CircleShape, color = StradaBlue, modifier = Modifier.size(120.dp)) {
                Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.padding(24.dp))
            }

            Spacer(Modifier.height(16.dp))
            Text("Hi, ${viewModel.userName.value}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(40.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Используем скругление 28.dp (Extra Large из MD3)
                ProfileMenuButton("Settings", Icons.Default.Settings, cardColor) { showSettingsSheet = true }
                ProfileMenuButton("Offline timetable", Icons.Default.CloudOff, cardColor) { }
                ProfileMenuButton("My transport", Icons.Default.DirectionsBus, cardColor) { onNavigateToMyTransport() }
                ProfileMenuButton("Send feedback", Icons.Default.Feedback, cardColor) { }
                ProfileMenuButton("Profile settings", Icons.Default.PersonOutline, cardColor) { }
            }


            Spacer(Modifier.weight(1f))

            Button(
                onClick = { },
                colors = ButtonDefaults.buttonColors(containerColor = StradaBlue),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 32.dp).navigationBarsPadding()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Get Strada Pro", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }

        // Нижнее меню основных настроек
        if (showSettingsSheet) {
            ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }, sheetState = sheetState) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).navigationBarsPadding()) {
                    Text("Технические настройки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))

                    // --- НОВЫЙ ТУМБЛЕР ДЛЯ ИЗБРАННОГО ---
                    SettingsSwitchRow("Только избранные на карте (Экономия батареи)", viewModel.showOnlyFavorites.value) { viewModel.toggleShowOnlyFavorites(it) }

                    SettingsSwitchRow("Адаптивные цвета (Monet)", viewModel.isMonetEnabled.value) { viewModel.toggleMonet(it) }
                    SettingsSwitchRow("AMOLED черный", viewModel.isAmoledEnabled.value) { viewModel.toggleAmoled(it) }

                    Spacer(Modifier.height(16.dp))
                    Text("Тема", fontWeight = FontWeight.Bold)
                    ThemeOption("Системная", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, viewModel)
                    ThemeOption("Светлая", AppCompatDelegate.MODE_NIGHT_NO, viewModel)
                    ThemeOption("Темная", AppCompatDelegate.MODE_NIGHT_YES, viewModel)

                    Spacer(Modifier.height(16.dp))
                    Text("База данных GTFS", fontWeight = FontWeight.Bold)

                    val updateModeText = when(viewModel.updateMode.intValue) {
                        0 -> "Только Wi-Fi"
                        1 -> "В любом случае"
                        2 -> "Только моб. интернет"
                        3 -> "Отключено (Вручную)"
                        else -> ""
                    }
                    ListItem(
                        headlineContent = { Text("Автообновление") },
                        supportingContent = { Text(updateModeText) },
                        modifier = Modifier.clickable { showUpdateModeSheet = true; showSettingsSheet = false },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    ListItem(
                        headlineContent = { Text("Статус базы") },
                        supportingContent = { Text(loadingStatus) },
                        trailingContent = { Button(onClick = { viewModel.forceUpdateGtfs() }) { Text("Обновить") } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }

        // Нижнее меню для выбора типа сети автообновления
        if (showUpdateModeSheet) {
            ModalBottomSheet(onDismissRequest = { showUpdateModeSheet = false }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).navigationBarsPadding()) {
                    Text("Автообновление базы", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    UpdateModeOption("Только Wi-Fi", 0, viewModel)
                    UpdateModeOption("В любом случае", 1, viewModel)
                    UpdateModeOption("Только моб. интернет", 2, viewModel)
                    UpdateModeOption("Отключено (Вручную)", 3, viewModel)
                }
            }
        }
    }
}

@Composable
fun ProfileMenuButton(text: String, icon: ImageVector, bgColor: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp), // MD3 Expressive: Крупное скругление
        color = bgColor,
        modifier = Modifier.fillMaxWidth().height(64.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(20.dp))
            Text(
                text = text,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(checked) { scale.animateTo(1.15f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)); scale.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)) }
    ListItem(headlineContent = { Text(label) }, trailingContent = { Switch(modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }, checked = checked, onCheckedChange = onCheckedChange) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable
fun ThemeOption(label: String, mode: Int, viewModel: BusViewModel) {
    val isSelected = viewModel.themeMode.intValue == mode
    val scale = remember { Animatable(1f) }
    LaunchedEffect(isSelected) { if (isSelected) { scale.animateTo(1.25f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)); scale.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)) } }
    ListItem(modifier = Modifier.clickable { viewModel.setTheme(mode) }, headlineContent = { Text(label) }, leadingContent = { RadioButton(modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }, selected = isSelected, onClick = { viewModel.setTheme(mode) }) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable
fun UpdateModeOption(label: String, mode: Int, viewModel: BusViewModel) {
    val isSelected = viewModel.updateMode.intValue == mode
    val scale = remember { Animatable(1f) }
    LaunchedEffect(isSelected) { if (isSelected) { scale.animateTo(1.25f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)); scale.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)) } }
    ListItem(modifier = Modifier.clickable { viewModel.setUpdateMode(mode) }, headlineContent = { Text(label) }, leadingContent = { RadioButton(modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }, selected = isSelected, onClick = { viewModel.setUpdateMode(mode) }) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}