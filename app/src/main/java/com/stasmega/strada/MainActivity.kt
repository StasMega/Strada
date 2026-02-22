package com.stasmega.strada

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*

// --- КОНСТАНТЫ СТИЛЯ ---
val StradaBlue = Color(0xFF0A48FF)
val StradaCharcoal = Color(0xFF31343B)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val vm: BusViewModel = viewModel(factory = BusViewModel.Factory(application))
            val nav = rememberNavController()

            StradaTheme(viewModel = vm) {
                val navBackStackEntry by nav.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val isDetail = currentRoute?.startsWith("route_detail") ?: false

                Scaffold(
                    bottomBar = {
                        if (!isDetail) {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                                val tabs = listOf(
                                    Triple("map", Icons.Default.Map, R.string.nav_map),
                                    Triple("schedule", Icons.Default.List, R.string.nav_schedule),
                                    Triple("settings", Icons.Default.Settings, R.string.nav_settings)
                                )
                                tabs.forEach { (route, icon, labelRes) ->
                                    NavigationBarItem(
                                        selected = currentRoute == route,
                                        icon = { Icon(icon, null) },
                                        label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)) },
                                        onClick = {
                                            if (currentRoute != route) nav.navigate(route) {
                                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                ) { padding ->
                    NavHost(
                        navController = nav,
                        startDestination = "map",
                        modifier = Modifier.padding(bottom = if (isDetail) 0.dp else padding.calculateBottomPadding())
                    ) {
                        composable("map",
                            enterTransition = { fadeIn(tween(150)) },
                            exitTransition = { fadeOut(tween(150)) }
                        ) { MapScreen(vm) }

                        composable("schedule",
                            enterTransition = { fadeIn(tween(150)) },
                            exitTransition = { fadeOut(tween(150)) }
                        ) { ScheduleScreen(vm) { id -> nav.navigate("route_detail/$id") } }

                        composable("settings",
                            enterTransition = { fadeIn(tween(150)) },
                            exitTransition = { fadeOut(tween(150)) }
                        ) { SettingsScreen(vm) }

                        composable("route_detail/{id}",
                            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(200)) },
                            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(200)) }
                        ) { backStackEntry ->
                            val id = backStackEntry.arguments?.getString("id") ?: ""
                            RouteDetailScreen(vm, id, onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

/**
 * ПРУЖИННАЯ АНИМАЦИЯ (Material Expressive)
 */
fun Modifier.bounceClick() = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = ""
    )
    this.graphicsLayer(scaleX = scale, scaleY = scale)
        .pointerInput(Unit) {
            detectTapGestures(onPress = { isPressed = true; tryAwaitRelease(); isPressed = false })
        }
}

@Composable
fun StradaTheme(viewModel: BusViewModel, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isDark = when (viewModel.themeMode.intValue) {
        AppCompatDelegate.MODE_NIGHT_YES -> true
        AppCompatDelegate.MODE_NIGHT_NO -> false
        else -> isSystemInDarkTheme()
    }

    val useAmoled = viewModel.isAmoledEnabled.value && isDark
    val backgroundCol = if (useAmoled) Color.Black else if (isDark) StradaCharcoal else Color(0xFFF5F5F7)

    val colorScheme = when {
        viewModel.isMonetEnabled.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val base = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            base.copy(background = backgroundCol, surface = backgroundCol)
        }
        isDark -> darkColorScheme(primary = StradaBlue, background = backgroundCol, surface = backgroundCol, secondaryContainer = StradaBlue.copy(0.2f))
        else -> lightColorScheme(primary = StradaBlue, background = backgroundCol, surface = backgroundCol, secondaryContainer = StradaBlue.copy(0.1f))
    }

    val Geist = FontFamily(Font(R.font.geist_medium, FontWeight.Medium), Font(R.font.geist_bold, FontWeight.Bold))

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            displaySmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 32.sp),
            titleLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp),
            titleMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 17.sp),
            bodyLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 16.sp),
            labelLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Bold),
            labelSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium)
        ),
        content = content
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }
}