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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*

val StradaBlue = Color(0xFF0A48FF)
val StradaCharcoal = Color(0xFF31343B)
val ProfileBgColor = Color(0xFFEFEFEF)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val vm: BusViewModel = viewModel(factory = BusViewModel.Factory(application))
            val nav = rememberNavController()

            StradaTheme(viewModel = vm) {
                NavHost(
                    navController = nav,
                    // Проверяем первый запуск для выбора стартового экрана
                    startDestination = if (vm.isFirstLaunch.value) "welcome" else "map"
                ) {
                    // 1. Экран приветствия
                    composable("welcome") {
                        WelcomeScreen { name ->
                            vm.setUserName(name)
                            nav.navigate("map") { popUpTo("welcome") { inclusive = true } }
                        }
                    }

                    // 2. Главный экран (Карта)
                    composable("map",
                        enterTransition = { fadeIn(tween(200)) },
                        exitTransition = { fadeOut(tween(200)) }
                    ) {
                        MapScreen(
                            viewModel = vm,
                            onNavigateToProfile = { nav.navigate("profile") },
                            onNavigateToRouteDetail = { id -> nav.navigate("route_detail/$id") }
                        )
                    }

                    // 3. Экран профиля
                    composable("profile",
                        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) },
                        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) }
                    ) {
                        ProfileScreen(
                            viewModel = vm,
                            onBack = { nav.popBackStack() },
                            onNavigateToMyTransport = { nav.navigate("my_transport") }
                        )
                    }

                    // 4. Экран "Мой транспорт"
                    composable("my_transport") {
                        MyTransportScreen(vm, onBack = { nav.popBackStack() })
                    }

                    // 5. Детали маршрута
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

fun Modifier.bounceClick() = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = ""
    )
    this.graphicsLayer(scaleX = scale, scaleY = scale)
        .pointerInput(Unit) { detectTapGestures(onPress = { isPressed = true; tryAwaitRelease(); isPressed = false }) }
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
    val backgroundCol = if (useAmoled) Color.Black else if (isDark) StradaCharcoal else ProfileBgColor

    val colorScheme = when {
        viewModel.isMonetEnabled.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val base = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            base.copy(background = backgroundCol, surface = backgroundCol)
        }
        isDark -> darkColorScheme(primary = StradaBlue, background = backgroundCol, surface = backgroundCol, secondaryContainer = StradaBlue.copy(0.2f))
        else -> lightColorScheme(primary = StradaBlue, background = backgroundCol, surface = backgroundCol, secondaryContainer = StradaBlue.copy(0.1f))
    }

    // Переименовали в маленькую букву geist, чтобы линтер не ругался
    val geist = FontFamily(Font(R.font.geist_medium, FontWeight.Medium), Font(R.font.geist_bold, FontWeight.Bold))

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            displaySmall = TextStyle(fontFamily = geist, fontWeight = FontWeight.Bold, fontSize = 32.sp),
            titleLarge = TextStyle(fontFamily = geist, fontWeight = FontWeight.Bold, fontSize = 20.sp),
            titleMedium = TextStyle(fontFamily = geist, fontWeight = FontWeight.Medium, fontSize = 17.sp),
            bodyLarge = TextStyle(fontFamily = geist, fontWeight = FontWeight.Medium, fontSize = 16.sp),
            labelLarge = TextStyle(fontFamily = geist, fontWeight = FontWeight.Bold),
            labelSmall = TextStyle(fontFamily = geist, fontWeight = FontWeight.Medium)
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