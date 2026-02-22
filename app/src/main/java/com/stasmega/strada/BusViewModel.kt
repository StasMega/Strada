package com.stasmega.strada

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

sealed class ScheduleState {
    data class Loading(val progress: String) : ScheduleState()
    data class Success(val schedule: RouteSchedule) : ScheduleState()
    data class Error(val message: String) : ScheduleState()
}

class BusViewModel(application: Application) : AndroidViewModel(application) {
    private val client = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val prefs = application.getSharedPreferences("strada_prefs", Context.MODE_PRIVATE)

    var isMonetEnabled = mutableStateOf(prefs.getBoolean("MONET_ENABLED", false))

    fun toggleMonet(enabled: Boolean) {
        isMonetEnabled.value = enabled
        prefs.edit().putBoolean("MONET_ENABLED", enabled).apply()
    }

    private val iconCache = mutableMapOf<String, android.graphics.Bitmap>()

    fun getIconFromCache(key: String): android.graphics.Bitmap? = iconCache[key]

    fun saveIconToCache(key: String, bitmap: android.graphics.Bitmap) {
        iconCache[key] = bitmap
    }

    fun clearIconCache() {
        iconCache.values.forEach { it.recycle() }
        iconCache.clear()
    }

    private val _recentRoutes = MutableStateFlow<List<RouteInfo>>(emptyList())
    val recentRoutes = _recentRoutes.asStateFlow()

    private val _stopBoardDepartures = MutableStateFlow<List<StopDeparture>>(emptyList())
    val stopBoardDepartures = _stopBoardDepartures.asStateFlow()


    var updateMode = mutableIntStateOf(prefs.getInt("UPDATE_MODE", 0))
    private val _buses = MutableStateFlow<List<BusInfo>>(emptyList())
    val buses = _buses.asStateFlow()
    private val _routes = MutableStateFlow<List<RouteInfo>>(emptyList())
    val routes = _routes.asStateFlow()
    private val _routeShapes = MutableStateFlow<Map<String, RouteShape>>(emptyMap())
    val routeShapes = _routeShapes.asStateFlow()
    private val _scheduleState = MutableStateFlow<ScheduleState>(ScheduleState.Loading("Запуск..."))
    val scheduleState = _scheduleState.asStateFlow()
    private val _stopDepartures = MutableStateFlow<List<StopDeparture>>(emptyList())
    val stopDepartures = _stopDepartures.asStateFlow()
    private val _isGtfsLoaded = MutableStateFlow(false)
    private val _loadingStatus = MutableStateFlow("Инициализация...")
    val loadingStatus = _loadingStatus.asStateFlow()

    var isAmoledEnabled = mutableStateOf(prefs.getBoolean("AMOLED_ENABLED", false))

    fun toggleAmoled(enabled: Boolean) {
        isAmoledEnabled.value = enabled
        prefs.edit().putBoolean("AMOLED_ENABLED", enabled).apply()
    }


    var showBus = mutableStateOf(prefs.getBoolean("SHOW_BUS", true))
    var showTram = mutableStateOf(prefs.getBoolean("SHOW_TRAM", true))
    var themeMode = mutableIntStateOf(prefs.getInt("THEME_MODE", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
    var selectedRouteOnMap = mutableStateOf<String?>(null)

    // Кэши данных
    private val stopTimesCache = HashMap<String, MutableList<StopTimeRow>>(60000)
    private val tripsCache = HashMap<String, TripRow>(25000)
    private val stopsCache = HashMap<String, GtfsStop>(6000)
    private val routeIdToInfo = HashMap<String, RouteInfo>(600)
    private val routeIdToTripIds = HashMap<String, MutableList<String>>(600)
    private val calendarCache = HashMap<String, CalendarRule>(150)

    fun getStopsInBounds(bounds: org.maplibre.android.geometry.LatLngBounds): List<GtfsStop> {
        // Таллин — город компактный, фильтрация 4000 остановок по координатам
        // занимает доли миллисекунды.
        return stopsCache.values.filter { stop ->
            bounds.contains(org.maplibre.android.geometry.LatLng(stop.lat, stop.lon))
        }
    }

    // Кэши для геометрии (Shapes)
    private val rawShapesCache = HashMap<String, List<RoutePoint>>(2000)
    private val routeIdToShapeIds = HashMap<String, MutableSet<String>>(600)

    init {
        viewModelScope.launch { checkAndLoadGtfs() }
        viewModelScope.launch { fetchBuses(); while (isActive) { delay(10000); fetchBuses() } }
    }

    private suspend fun checkAndLoadGtfs() {
        val file = File(getApplication<Application>().filesDir, "gtfs_tallinn.zip")
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastUpdate = prefs.getString("LAST_GTFS_UPDATE", "")

        val needsDownload = when (updateMode.intValue) {
            0 -> lastUpdate != today
            1 -> lastUpdate != today && isWifiConnected()
            else -> !file.exists()
        }

        if (needsDownload) downloadGtfs(file)?.let { prefs.edit().putString("LAST_GTFS_UPDATE", today).apply() }
        if (file.exists()) parseGtfsFile(file)
    }

    private fun isWifiConnected(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
    }

    private suspend fun downloadGtfs(file: File): Boolean? = withContext(Dispatchers.IO) {
        try {
            _loadingStatus.value = "Обновление базы..."
            val response = client.newCall(Request.Builder().url("https://transport.tallinn.ee/data/gtfs.zip").build()).execute()
            if (!response.isSuccessful) return@withContext false
            response.body?.byteStream()?.use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
            true
        } catch (e: Exception) { null }
    }

    private suspend fun parseGtfsFile(file: File) = withContext(Dispatchers.IO) {
        try {
            ZipFile(file).use { zip ->
                _loadingStatus.value = "Парсинг..."
                zip.getEntry("calendar.txt")?.let { parseCalendar(zip.getInputStream(it)) }
                zip.getEntry("routes.txt")?.let { parseGtfsRoutes(zip.getInputStream(it)) }
                zip.getEntry("stops.txt")?.let { parseGtfsStops(zip.getInputStream(it)) }

                // Важно: сначала парсим формы (shapes), потом трипы (trips), чтобы связать их
                zip.getEntry("shapes.txt")?.let { parseGtfsShapes(zip.getInputStream(it)) }
                zip.getEntry("trips.txt")?.let { parseGtfsTrips(zip.getInputStream(it)) }
                zip.getEntry("stop_times.txt")?.let { parseStopTimes(zip.getInputStream(it)) }

                // Собираем итоговую геометрию для карты
                buildRouteShapes()
            }
            _isGtfsLoaded.value = true
            _loadingStatus.value = "Готово"
            loadRecentRoutes()
        } catch (e: Exception) {
            Log.e("BusViewModel", "GTFS Parse error", e)
            _loadingStatus.value = "Ошибка: ${e.message}"
        }
    }

    private fun parseCsvLine(s: String): List<String> {
        val res = mutableListOf<String>()
        var inQuote = false
        val buf = StringBuilder()
        val clean = s.replace("\uFEFF", "")
        for (c in clean) {
            when {
                c == '"' -> inQuote = !inQuote
                c == ',' && !inQuote -> { res.add(buf.toString().trim().removeSurrounding("\"")); buf.clear() }
                else -> buf.append(c)
            }
        }
        res.add(buf.toString().trim().removeSurrounding("\""))
        return res
    }

    private fun parseCalendar(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream))
        val headers = parseCsvLine(reader.readLine() ?: return)
        val sId = headers.indexOf("service_id"); val mId = headers.indexOf("monday")
        reader.forEachLine { line ->
            val p = parseCsvLine(line)
            if (p.size > mId + 6) calendarCache[p[sId]] = CalendarRule(p[sId], p[mId]=="1", p[mId+1]=="1", p[mId+2]=="1", p[mId+3]=="1", p[mId+4]=="1", p[mId+5]=="1", p[mId+6]=="1", "", "")
        }
    }

    private fun parseGtfsRoutes(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream))
        val headers = parseCsvLine(reader.readLine() ?: return)
        val idIdx = headers.indexOf("route_id")
        val shortIdx = headers.indexOf("route_short_name")
        val longIdx = headers.indexOf("route_long_name")
        val typeIdx = headers.indexOf("route_type")
        val descIdx = headers.indexOf("route_desc")

        val list = mutableListOf<RouteInfo>()
        reader.forEachLine { line ->
            val p = parseCsvLine(line)
            if (p.size <= maxOf(idIdx, shortIdx)) return@forEachLine
            val rId = p[idIdx]
            val sName = p[shortIdx]
            if (sName.isBlank()) return@forEachLine

            val finalName = if (longIdx >= 0 && p[longIdx].isNotBlank()) p[longIdx] else p.getOrElse(descIdx){""}.replace("Regional", "").trim()
            val isReg = rId.contains("harju", true)
            val type = when {
                rId.contains("tram", true) || p.getOrNull(typeIdx) == "0" -> "Tram"
                rId.contains("elron", true) || p.getOrNull(typeIdx) == "2" -> "Train"
                else -> "Bus"
            }
            val info = RouteInfo(rId, sName, finalName, type, isReg)
            list.add(info)
            routeIdToInfo[rId] = info
        }
        _routes.value = list.sortedWith(compareBy<RouteInfo>{it.type!="Tram"}.thenBy{it.type!="Bus"}.thenBy{it.isRegional}.thenBy{it.number.toIntOrNull()?:Int.MAX_VALUE}.thenBy{it.number})
    }

    private fun parseGtfsShapes(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream))
        val headers = parseCsvLine(reader.readLine() ?: return)
        val idIdx = headers.indexOf("shape_id")
        val latIdx = headers.indexOf("shape_pt_lat")
        val lonIdx = headers.indexOf("shape_pt_lon")
        val seqIdx = headers.indexOf("shape_pt_sequence")

        val tempMap = HashMap<String, MutableList<Pair<Int, RoutePoint>>>()
        reader.forEachLine { line ->
            val p = parseCsvLine(line)
            if (p.size > maxOf(latIdx, lonIdx)) {
                val sId = p[idIdx]
                val pt = RoutePoint(p[latIdx].toDoubleOrNull() ?: 0.0, p[lonIdx].toDoubleOrNull() ?: 0.0)
                val seq = p[seqIdx].toIntOrNull() ?: 0
                tempMap.getOrPut(sId) { mutableListOf() }.add(seq to pt)
            }
        }
        tempMap.forEach { (id, list) ->
            rawShapesCache[id] = list.sortedBy { it.first }.map { it.second }
        }
    }

    private fun parseGtfsTrips(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream))
        val headers = parseCsvLine(reader.readLine() ?: return)
        val rIdIdx = headers.indexOf("route_id")
        val tIdIdx = headers.indexOf("trip_id")
        val sIdIdx = headers.indexOf("service_id")
        val dirIdx = headers.indexOf("direction_id")
        val hIdx = headers.indexOf("trip_headsign")
        val shIdIdx = headers.indexOf("shape_id")

        reader.forEachLine { line ->
            val p = parseCsvLine(line)
            if (p.size > maxOf(rIdIdx, tIdIdx)) {
                val tId = p[tIdIdx]; val rId = p[rIdIdx]
                val shId = if (shIdIdx >= 0 && p.size > shIdIdx) p[shIdIdx] else ""
                tripsCache[tId] = TripRow(rId, p.getOrElse(hIdx){""}, p.getOrElse(dirIdx){"0"}.toIntOrNull()?:0, p.getOrElse(sIdIdx){""})
                routeIdToTripIds.getOrPut(rId){mutableListOf()}.add(tId)
                if (shId.isNotBlank()) routeIdToShapeIds.getOrPut(rId){mutableSetOf()}.add(shId)
            }
        }
    }

    private fun buildRouteShapes() {
        val result = mutableMapOf<String, RouteShape>()
        routeIdToInfo.forEach { (rId, info) ->
            val shapeIds = routeIdToShapeIds[rId] ?: return@forEach
            val allPoints = mutableListOf<RoutePoint>()
            shapeIds.forEach { sid -> rawShapesCache[sid]?.let { allPoints.addAll(it) } }
            if (allPoints.isNotEmpty()) {
                result[info.number] = RouteShape(info.number, info.type, allPoints, emptyList())
            }
        }
        _routeShapes.value = result
    }

    private fun parseStopTimes(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream))
        val headers = parseCsvLine(reader.readLine() ?: return)
        val tIdx = headers.indexOf("trip_id"); val dIdx = headers.indexOf("departure_time"); val sIdx = headers.indexOf("stop_id"); val seqIdx = headers.indexOf("stop_sequence")
        reader.forEachLine { line ->
            val p = parseCsvLine(line)
            if (p.size > maxOf(tIdx, sIdx)) {
                val tId = p[tIdx]
                if (tripsCache.containsKey(tId)) stopTimesCache.getOrPut(tId){mutableListOf()}.add(StopTimeRow(tId, p[sIdx], p[dIdx], p[seqIdx].toIntOrNull()?:0))
            }
        }
    }

    private fun parseGtfsStops(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream))
        val headers = parseCsvLine(reader.readLine() ?: return)
        val idI = headers.indexOf("stop_id"); val nameI = headers.indexOf("stop_name"); val latI = headers.indexOf("stop_lat"); val lonI = headers.indexOf("stop_lon")
        reader.forEachLine { line ->
            val p = parseCsvLine(line)
            if (p.size > idI) stopsCache[p[idI]] = GtfsStop(p[idI], p.getOrElse(nameI){""}, p.getOrElse(latI){"0"}.toDoubleOrNull()?:0.0, p.getOrElse(lonI){"0"}.toDoubleOrNull()?:0.0)
        }
    }

    // --- Логика работы с UI ---

    fun loadRecentRoutes() {
        val savedIds = prefs.getString("RECENT_ROUTES", "") ?: ""
        if (savedIds.isNotEmpty()) {
            val ids = savedIds.split("|")
            _recentRoutes.value = ids.mapNotNull { routeIdToInfo[it] }
        }
    }

    fun addToRecent(route: RouteInfo) {
        val current = _recentRoutes.value.toMutableList()
        current.removeAll { it.id == route.id }
        current.add(0, route)
        val limited = current.take(5)
        _recentRoutes.value = limited
        prefs.edit().putString("RECENT_ROUTES", limited.joinToString("|") { it.id }).apply()
    }

    fun loadRouteSchedule(routeId: String) {
        _scheduleState.value = ScheduleState.Loading("Загрузка...")
        viewModelScope.launch(Dispatchers.Default) {
            if (!_isGtfsLoaded.value) _isGtfsLoaded.filter { it }.first()
            val route = routeIdToInfo[routeId] ?: return@launch
            val trips = routeIdToTripIds[routeId] ?: emptyList()
            val sch = RouteSchedule(
                routeId, route.number, route.name, route.type,
                mapStops(findLongestTrip(trips.filter{tripsCache[it]?.directionId==0})?.second), tripsCache[findLongestTrip(trips.filter{tripsCache[it]?.directionId==0})?.first]?.headsign ?: "",
                mapStops(findLongestTrip(trips.filter{tripsCache[it]?.directionId==1})?.second), tripsCache[findLongestTrip(trips.filter{tripsCache[it]?.directionId==1})?.first]?.headsign ?: ""
            )
            _scheduleState.value = ScheduleState.Success(sch)
        }
    }

    fun loadStopBoard(stopId: String) {
        _stopBoardDepartures.value = emptyList()
        viewModelScope.launch(Dispatchers.Default) {
            val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            val nowTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            val allDepartures = mutableListOf<StopDeparture>()

            // Проходим по всем трипам, чтобы найти те, что заезжают на эту остановку
            tripsCache.forEach { (tripId, trip) ->
                val rule = calendarCache[trip.serviceId] ?: return@forEach
                val isActiveToday = when(day) {
                    Calendar.MONDAY -> rule.monday; Calendar.TUESDAY -> rule.tuesday
                    Calendar.WEDNESDAY -> rule.wednesday; Calendar.THURSDAY -> rule.thursday
                    Calendar.FRIDAY -> rule.friday; Calendar.SATURDAY -> rule.saturday
                    Calendar.SUNDAY -> rule.sunday; else -> false
                }
                if (!isActiveToday) return@forEach

                val stopTime = stopTimesCache[tripId]?.find { it.stopId == stopId } ?: return@forEach

                // Берем только те, что еще не уехали (или +5 минут запаса)
                if (stopTime.departureTime >= nowTime) {
                    val route = routeIdToInfo[trip.routeId] ?: return@forEach
                    allDepartures.add(StopDeparture(
                        routeNumber = route.number,
                        headsign = trip.headsign,
                        time = stopTime.departureTime,
                        type = route.type
                    ))
                }
            }

            _stopBoardDepartures.value = allDepartures
                .sortedBy { it.time }
                .distinctBy { it.routeNumber + it.time } // Убираем дубликаты
                .take(20) // Показываем ближайшие 20
        }
    }

    private fun findLongestTrip(ids: List<String>): Pair<String, List<StopTimeRow>>? {
        var b: List<StopTimeRow>? = null; var bId = ""
        for (id in ids.take(100)) { val t = stopTimesCache[id] ?: continue; if (t.size > (b?.size?:0)) { b = t; bId = id } }
        return if (b != null) bId to b else null
    }

    private fun mapStops(r: List<StopTimeRow>?): List<StopTime> = r?.sortedBy{it.stopSequence}?.map{StopTime(it.stopId, stopsCache[it.stopId]?.name?:"", it.departureTime, it.departureTime, it.stopSequence)} ?: emptyList()

    fun loadDeparturesForStop(rId: String, sId: String, dId: Int) {
        _stopDepartures.value = emptyList()
        viewModelScope.launch(Dispatchers.Default) {
            val route = routeIdToInfo[rId] ?: return@launch
            val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            val deps = (routeIdToTripIds[rId]?: emptyList()).mapNotNull { tId ->
                val trip = tripsCache[tId] ?: return@mapNotNull null
                if (trip.directionId != dId) return@mapNotNull null
                val rule = calendarCache[trip.serviceId] ?: return@mapNotNull null
                val active = when(day){ Calendar.MONDAY->rule.monday; Calendar.TUESDAY->rule.tuesday; Calendar.WEDNESDAY->rule.wednesday; Calendar.THURSDAY->rule.thursday; Calendar.FRIDAY->rule.friday; Calendar.SATURDAY->rule.saturday; Calendar.SUNDAY->rule.sunday; else->false }
                if (!active) return@mapNotNull null
                val m = stopTimesCache[tId]?.find{it.stopId==sId} ?: return@mapNotNull null
                StopDeparture(route.number, trip.headsign, m.departureTime, route.type)
            }.sortedBy{it.time}.distinctBy{it.time}
            _stopDepartures.value = deps
        }
    }

    fun clearStopDepartures() { _stopDepartures.value = emptyList() }
    fun forceUpdateGtfs() { viewModelScope.launch { val f = File(getApplication<Application>().filesDir, "gtfs_tallinn.zip"); if (downloadGtfs(f)==true) parseGtfsFile(f) } }
    fun setUpdateMode(m: Int) { updateMode.intValue = m; prefs.edit().putInt("UPDATE_MODE", m).apply() }
    fun clearRouteOnMap() { selectedRouteOnMap.value = null; viewModelScope.launch { fetchBuses() } }

    private suspend fun fetchBuses() {
        try {
            val r = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url("https://transport.tallinn.ee/gps.txt").build())
                    .execute().use { it.body?.string() ?: "" }
            }

            // Берем текущий фильтр (например, "5")
            val filterNumber = selectedRouteOnMap.value

            _buses.value = r.lines().mapNotNull { l ->
                val p = l.split(",")
                if (p.size < 7) return@mapNotNull null

                val type = if (p[0] == "3") "Tram" else "Bus"
                val lineNumber = p[1]

                // 1. Если включен фильтр по маршруту
                if (filterNumber != null) {
                    if (lineNumber != filterNumber) return@mapNotNull null
                } else {
                    // 2. Если фильтра нет, работают обычные галочки из настроек
                    if (type == "Bus" && !showBus.value) return@mapNotNull null
                    if (type == "Tram" && !showTram.value) return@mapNotNull null
                }

                BusInfo(p[6], type, lineNumber, p[3].toDouble()/1000000.0, p[2].toDouble()/1000000.0, p[5].toFloat())
            }
        } catch (_: Exception) {}
    }

    fun toggleBus(e: Boolean) { showBus.value = e; prefs.edit().putBoolean("SHOW_BUS", e).apply(); viewModelScope.launch { fetchBuses() } }
    fun toggleTram(e: Boolean) { showTram.value = e; prefs.edit().putBoolean("SHOW_TRAM", e).apply(); viewModelScope.launch { fetchBuses() } }
    fun setTheme(m: Int) { themeMode.intValue = m; prefs.edit().putInt("THEME_MODE", m).apply(); AppCompatDelegate.setDefaultNightMode(m) }
    fun setLanguage(l: String) { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(l)) }
    fun selectRouteOnMap(n: String?) { selectedRouteOnMap.value = n; viewModelScope.launch { fetchBuses() } }

    data class TripRow(val routeId: String, val headsign: String, val directionId: Int, val serviceId: String)
    private data class StopTimeRow(val tripId: String, val stopId: String, val departureTime: String, val stopSequence: Int)

    class Factory(private val a: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(m: Class<T>): T = BusViewModel(a) as T
    }
}