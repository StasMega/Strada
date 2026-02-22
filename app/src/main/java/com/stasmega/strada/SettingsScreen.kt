package com.stasmega.strada

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(viewModel: BusViewModel) {
    val loadingStatus by viewModel.loadingStatus.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            // Мягкий градиент в шапке при скролле
            stickyHeader {
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, Color.Transparent)))
                    .statusBarsPadding()
                    .height(12.dp))
            }

            item {
                Text(
                    stringResource(R.string.nav_settings),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 24.dp)
                )
            }

            // РАЗДЕЛ: КАРТА
            item {
                SettingsCard(title = "Карта", icon = Icons.Default.Map) {
                    SettingsSwitchRow("Показывать автобусы", viewModel.showBus.value) { viewModel.toggleBus(it) }
                    SettingsSwitchRow("Показывать трамваи", viewModel.showTram.value) { viewModel.toggleTram(it) }
                }
            }

            // РАЗДЕЛ: ВНЕШНИЙ ВИД
            item {
                SettingsCard(title = "Внешний вид", icon = Icons.Default.Palette) {
                    SettingsSwitchRow("Адаптивные цвета (Monet)", viewModel.isMonetEnabled.value) { viewModel.toggleMonet(it) }

                    Spacer(Modifier.height(8.dp))

                    ThemeOption("Системная тема", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, viewModel)
                    ThemeOption("Светлая", AppCompatDelegate.MODE_NIGHT_NO, viewModel)
                    ThemeOption("Темная", AppCompatDelegate.MODE_NIGHT_YES, viewModel)

                    Spacer(Modifier.height(8.dp))

                    SettingsSwitchRow("AMOLED черный", viewModel.isAmoledEnabled.value) {
                        viewModel.toggleAmoled(it)
                    }
                }
            }

            // РАЗДЕЛ: ДАННЫЕ
            item {
                SettingsCard(title = "Данные", icon = Icons.Default.CloudDownload) {
                    ListItem(
                        headlineContent = { Text("Статус базы") },
                        supportingContent = { Text(loadingStatus) },
                        trailingContent = {
                            Button(onClick = { viewModel.forceUpdateGtfs() }) {
                                Text("Обновить")
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
    }
}

/**
 * Карточка группы настроек с динамическим цветом иконки
 */
@Composable
fun SettingsCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    // Используем системный primary вместо жесткого StradaBlue для поддержки Monet
    val accentColor = MaterialTheme.colorScheme.primary

    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        )
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = accentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
            }
            content()
        }
    }
}

/**
 * Переключатель с импульсной пружинной анимацией (Expressive Motion)
 */
@Composable
fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val scale = remember { Animatable(1f) }

    // Эффект пружины при переключении
    LaunchedEffect(checked) {
        scale.animateTo(1.15f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
        scale.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow))
    }

    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Switch(
                modifier = Modifier.graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/**
 * Радиокнопка выбора темы с пружинной анимацией
 */
@Composable
fun ThemeOption(label: String, mode: Int, viewModel: BusViewModel) {
    val isSelected = viewModel.themeMode.intValue == mode
    val scale = remember { Animatable(1f) }

    // Эффект "пуньк" при выборе
    LaunchedEffect(isSelected) {
        if (isSelected) {
            scale.animateTo(1.25f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
            scale.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow))
        }
    }

    ListItem(
        modifier = Modifier.clickable { viewModel.setTheme(mode) },
        headlineContent = { Text(label) },
        leadingContent = {
            RadioButton(
                modifier = Modifier.graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
                selected = isSelected,
                onClick = { viewModel.setTheme(mode) }
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}