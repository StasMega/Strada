package com.stasmega.strada

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
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
    fun toggleMonet(enabled: Boolean) { isMonetEnabled.value = enabled; prefs.edit().putBoolean("MONET_ENABLED", enabled).apply() }

    private val iconCache = mutableMapOf<String, android.graphics.Bitmap>()
    fun getIconFromCache(key: String): android.graphics.Bitmap? = iconCache[key]
    fun saveIconToCache(key: String, bitmap: android.graphics.Bitmap) { iconCache[key] = bitmap }
    fun clearIconCache() { iconCache.values.forEach { it.recycle() }; iconCache.clear() }

    private val _recentRoutes = MutableStateFlow<List<RouteInfo>>(emptyList())
    val recentRoutes = _recentRoutes.asStateFlow()

    // --- ИЗБРАННЫЕ МАРШРУТЫ ---
    private val _favoriteRoutes = MutableStateFlow<Set<String>>(prefs.getStringSet("FAVORITE_ROUTES", emptySet()) ?: emptySet())
    val favoriteRoutes = _favoriteRoutes.asStateFlow()
    var showOnlyFavorites = mutableStateOf(prefs.getBoolean("SHOW_ONLY_FAVS", false))

    fun toggleFavorite(routeNumber: String) {
        val current = _favoriteRoutes.value.toMutableSet()
        if (current.contains(routeNumber)) current.remove(routeNumber) else current.add(routeNumber)
        _favoriteRoutes.value = current
        prefs.edit().putStringSet("FAVORITE_ROUTES", current).apply()
        viewModelScope.launch { fetchBuses() }
    }

    fun toggleShowOnlyFavorites(enabled: Boolean) {
        showOnlyFavorites.value = enabled
        prefs.edit().putBoolean("SHOW_ONLY_FAVS", enabled).apply()
        viewModelScope.launch { fetchBuses() }
    }

    private val _stopBoardDepartures = MutableStateFlow<List<StopDeparture>>(emptyList())
    val stopBoardDepartures = _stopBoardDepartures.asStateFlow()

    var updateMode = mutableIntStateOf(prefs.getInt("UPDATE_MODE", 0))

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    fun updateSearchQuery(q: String) { _searchQuery.value = q }

    private val _routes = MutableStateFlow<List<RouteInfo>>(emptyList())
    val routes = _routes.asStateFlow()

    val filteredRoutes = combine(_routes, _searchQuery) { routesList, query ->
        if (query.isBlank()) routesList
        else routesList.filter { it.number.contains(query, true) || it.name.contains(query, true) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _buses = MutableStateFlow<List<BusInfo>>(emptyList())
    val buses = _buses.asStateFlow()
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
    fun toggleAmoled(enabled: Boolean) { isAmoledEnabled.value = enabled; prefs.edit().putBoolean("AMOLED_ENABLED", enabled).apply() }

    var showBus = mutableStateOf(prefs.getBoolean("SHOW_BUS", true))
    var showTram = mutableStateOf(prefs.getBoolean("SHOW_TRAM", true))
    var themeMode = mutableIntStateOf(prefs.getInt("THEME_MODE", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
    var selectedRouteOnMap = mutableStateOf<String?>(null)

    val busMarkerStates = mutableStateMapOf<String, BusMarkerState>()

    private val stopTimesCache = HashMap<String, ArrayList<StopTimeRow>>(40_000)
    private val tripsCache = HashMap<String, TripRow>(40_000)
    private val stopsCache = HashMap<String, GtfsStop>(5_000)
    private val routeIdToInfo = HashMap<String, RouteInfo>(600)
    private val routeIdToTripIds = HashMap<String, MutableList<String>>(600)
    private val calendarCache = HashMap<String, CalendarRule>(500)

    private val rawShapesCache = HashMap<String, List<RoutePoint>>(2_000)
    private val routeIdToShapeIds = HashMap<String, MutableSet<String>>(600)

    var userName = mutableStateOf(prefs.getString("USER_NAME", "") ?: "")
    var isFirstLaunch = mutableStateOf(prefs.getBoolean("IS_FIRST_LAUNCH", true))

    fun setUserName(name: String) {
        userName.value = name
        isFirstLaunch.value = false
        prefs.edit()
            .putString("USER_NAME", name)
            .putBoolean("IS_FIRST_LAUNCH", false)
            .apply()
    }

    // Добавим список RouteInfo для избранного (для экрана My Transport)
    val favoriteRoutesList = combine(_routes, _favoriteRoutes) { all, favIds ->
        all.filter { favIds.contains(it.number) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun getStopsInBounds(bounds: org.maplibre.android.geometry.LatLngBounds): List<GtfsStop> {
        return stopsCache.values.filter { stop -> bounds.contains(org.maplibre.android.geometry.LatLng(stop.lat, stop.lon)) }
    }

    init {
        viewModelScope.launch { checkAndLoadGtfs() }
        viewModelScope.launch { fetchBuses(); while (isActive) { delay(10000); fetchBuses() } }
    }

    private fun isWifiConnected(): Boolean { val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager; return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false }
    private fun isMobileDataConnected(): Boolean { val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager; return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false }
    private fun isNetworkConnected(): Boolean { val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager; return cm.activeNetwork != null }

    private suspend fun checkAndLoadGtfs() {
        val gtfsFile = File(getApplication<Application>().filesDir, "gtfs_tallinn.zip")
        val cacheFile = File(getApplication<Application>().filesDir, "strada_cache.bin")
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastUpdate = prefs.getString("LAST_GTFS_UPDATE", "")

        val needsDownload = if (!gtfsFile.exists()) {
            isNetworkConnected()
        } else if (lastUpdate != today) {
            when (updateMode.intValue) { 0 -> isWifiConnected(); 1 -> isNetworkConnected(); 2 -> isMobileDataConnected(); else -> false }
        } else false

        if (needsDownload) {
            if (downloadGtfs(gtfsFile) == true) {
                prefs.edit().putString("LAST_GTFS_UPDATE", today).apply()
                parseGtfsFile(gtfsFile)
                return
            }
        }

        // --- БИНАРНОЕ КЭШИРОВАНИЕ (Мгновенный запуск) ---
        if (cacheFile.exists()) {
            if (loadFromBinaryCache(cacheFile)) {
                _isGtfsLoaded.value = true
                _loadingStatus.value = "Готово"
                loadRecentRoutes()
                return
            }
        }

        if (gtfsFile.exists()) parseGtfsFile(gtfsFile)
    }

    private suspend fun loadFromBinaryCache(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            _loadingStatus.value = "Загрузка кэша..."
            ObjectInputStream(BufferedInputStream(FileInputStream(file))).use { ois ->
                val loadedRoutes = ois.readObject() as List<RouteInfo>
                _routes.value = loadedRoutes
                loadedRoutes.forEach { routeIdToInfo[it.id] = it }
                stopsCache.putAll(ois.readObject() as Map<String, GtfsStop>)
                tripsCache.putAll(ois.readObject() as Map<String, TripRow>)
                stopTimesCache.putAll(ois.readObject() as Map<String, ArrayList<StopTimeRow>>)
                calendarCache.putAll(ois.readObject() as Map<String, CalendarRule>)
                _routeShapes.value = ois.readObject() as Map<String, RouteShape>
                routeIdToTripIds.putAll(ois.readObject() as Map<String, MutableList<String>>)
            }
            true
        } catch (e: Exception) { Log.e("BusViewModel", "Cache load failed", e); false }
    }

    private suspend fun saveToBinaryCache() = withContext(Dispatchers.IO) {
        try {
            val file = File(getApplication<Application>().filesDir, "strada_cache.bin")
            ObjectOutputStream(BufferedOutputStream(FileOutputStream(file))).use { oos ->
                oos.writeObject(_routes.value)
                oos.writeObject(stopsCache)
                oos.writeObject(tripsCache)
                oos.writeObject(stopTimesCache)
                oos.writeObject(calendarCache)
                oos.writeObject(_routeShapes.value)
                oos.writeObject(routeIdToTripIds)
            }
        } catch (e: Exception) { Log.e("BusViewModel", "Cache save failed", e) }
    }

    private suspend fun downloadGtfs(file: File): Boolean? = withContext(Dispatchers.IO) {
        try {
            _loadingStatus.value = "Загрузка базы (20+ МБ)..."
            val response = client.newCall(Request.Builder().url("https://eu-gtfs.remix.com/tallinn.zip").build()).execute()
            if (!response.isSuccessful) return@withContext false
            response.body?.byteStream()?.use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
            true
        } catch (e: Exception) { null }
    }

    private suspend fun parseGtfsFile(file: File) = withContext(Dispatchers.IO) {
        try {
            ZipFile(file).use { zip ->
                _loadingStatus.value = "1/6 Чтение маршрутов..."
                zip.getEntry("routes.txt")?.let { parseGtfsRoutes(zip.getInputStream(it)) }

                _loadingStatus.value = "2/6 Чтение рейсов..."
                zip.getEntry("trips.txt")?.let { parseGtfsTrips(zip.getInputStream(it)) }

                _loadingStatus.value = "3/6 Чтение геометрии..."
                zip.getEntry("shapes.txt")?.let { parseGtfsShapes(zip.getInputStream(it)) }

                _loadingStatus.value = "4/6 Чтение остановок..."
                zip.getEntry("stops.txt")?.let { parseGtfsStops(zip.getInputStream(it)) }

                _loadingStatus.value = "5/6 Чтение расписаний..."
                zip.getEntry("stop_times.txt")?.let { parseStopTimes(zip.getInputStream(it)) }

                _loadingStatus.value = "6/6 Сборка данных..."
                zip.getEntry("calendar.txt")?.let { parseCalendar(zip.getInputStream(it)) }

                buildRouteShapes()
            }
            saveToBinaryCache() // Сохраняем в мгновенный кэш
            _isGtfsLoaded.value = true
            _loadingStatus.value = "Готово"
            loadRecentRoutes()
        } catch (e: Exception) {
            Log.e("BusViewModel", "GTFS Parse error", e)
            _loadingStatus.value = "Ошибка: ${e.message}"
        }
    }

    private fun fastParseCsvToArray(lineStr: String, csvRow: Array<String>): Int {
        var colCount = 0; var start = if (lineStr.startsWith("\uFEFF")) 1 else 0
        var inQuotes = false; val len = lineStr.length; val maxCols = csvRow.size
        for (i in start until len) {
            val c = lineStr[i]
            if (c == '"') { inQuotes = !inQuotes }
            else if (c == ',' && !inQuotes) {
                if (colCount < maxCols) {
                    var s = start; var e = i
                    while (s < e && lineStr[s] == '"') s++; while (e > s && lineStr[e - 1] == '"') e--
                    csvRow[colCount++] = lineStr.substring(s, e).trim()
                }
                start = i + 1
            }
        }
        if (colCount < maxCols) {
            var s = start; var e = len
            while (s < e && lineStr[s] == '"') s++; while (e > s && lineStr[e - 1] == '"') e--
            csvRow[colCount++] = lineStr.substring(s, e).trim()
        }
        return colCount
    }

    private fun parseCalendar(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream)); val csvRow = Array(40) { "" }
        val cols = fastParseCsvToArray(reader.readLine() ?: return, csvRow)
        var sId = -1; var mId = -1
        for (i in 0 until cols) { when (csvRow[i]) { "service_id" -> sId = i; "monday" -> mId = i } }
        if (sId == -1 || mId == -1) return
        reader.forEachLine { line ->
            val count = fastParseCsvToArray(line, csvRow)
            val serviceId = if (sId in 0 until count) csvRow[sId] else ""
            if (serviceId.isNotBlank()) {
                val m0 = if (mId in 0 until count) csvRow[mId] else "0"
                val m1 = if (mId+1 in 0 until count) csvRow[mId+1] else "0"
                val m2 = if (mId+2 in 0 until count) csvRow[mId+2] else "0"
                val m3 = if (mId+3 in 0 until count) csvRow[mId+3] else "0"
                val m4 = if (mId+4 in 0 until count) csvRow[mId+4] else "0"
                val m5 = if (mId+5 in 0 until count) csvRow[mId+5] else "0"
                val m6 = if (mId+6 in 0 until count) csvRow[mId+6] else "0"
                calendarCache[serviceId] = CalendarRule(serviceId, m0=="1", m1=="1", m2=="1", m3=="1", m4=="1", m5=="1", m6=="1", "", "")
            }
        }
    }

    private fun parseGtfsRoutes(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream)); val csvRow = Array(40) { "" }
        val cols = fastParseCsvToArray(reader.readLine() ?: return, csvRow)
        var idIdx = -1; var shortIdx = -1; var longIdx = -1; var typeIdx = -1
        for (i in 0 until cols) { when (csvRow[i]) { "route_id" -> idIdx = i; "route_short_name" -> shortIdx = i; "route_long_name" -> longIdx = i; "route_type" -> typeIdx = i } }
        val list = mutableListOf<RouteInfo>()
        reader.forEachLine { line ->
            val count = fastParseCsvToArray(line, csvRow)
            val rId = if (idIdx in 0 until count) csvRow[idIdx] else ""
            if (rId.isBlank()) return@forEachLine

            // ПУЛЕНЕПРОБИВАЕМЫЙ ПАРСЕР ИМЕН (Найдет поезда и загородные автобусы 100%)
            var sName = if (shortIdx in 0 until count) csvRow[shortIdx] else ""
            val lName = if (longIdx in 0 until count) csvRow[longIdx] else ""

            if (sName.isBlank()) { val match = Regex("(?i)liin\\s*([\\w]+)").find(lName); sName = match?.groupValues?.get(1) ?: lName.split(" ").firstOrNull() ?: "" }
            if (sName.isBlank()) sName = lName
            if (sName.isBlank()) sName = Regex("\\d+[A-Za-z]?").find(rId)?.value ?: ""
            if (sName.isBlank()) sName = rId.substringAfterLast('-')
            if (sName.isBlank()) sName = rId

            val isReg = rId.contains("harju", true) || rId.contains("regional", true)
            val routeType = if (typeIdx in 0 until count) csvRow[typeIdx] else ""
            val type = when {
                rId.contains("tram", true) || routeType == "0" || routeType == "900" -> "Tram"
                rId.contains("elron", true) || routeType == "2" || routeType in listOf("100","101","102","103","104","105","106","107","108","109","110","111","112","113","114","115","116","117") -> "Train"
                else -> "Bus"
            }
            val info = RouteInfo(rId, sName, lName, type, isReg)
            list.add(info)
            routeIdToInfo[rId] = info
        }
        _routes.value = list.sortedWith(compareBy<RouteInfo>{it.type!="Tram"}.thenBy{it.type!="Bus"}.thenBy{it.isRegional}.thenBy{it.number.toIntOrNull()?:Int.MAX_VALUE}.thenBy{it.number})
    }

    private fun parseGtfsTrips(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream)); val csvRow = Array(40) { "" }
        val cols = fastParseCsvToArray(reader.readLine() ?: return, csvRow)
        var rIdIdx = -1; var tIdIdx = -1; var sIdIdx = -1; var dirIdx = -1; var hIdx = -1; var shIdIdx = -1
        for (i in 0 until cols) { when (csvRow[i]) { "route_id" -> rIdIdx = i; "trip_id" -> tIdIdx = i; "service_id" -> sIdIdx = i; "direction_id" -> dirIdx = i; "trip_headsign" -> hIdx = i; "shape_id" -> shIdIdx = i } }
        reader.forEachLine { line ->
            val count = fastParseCsvToArray(line, csvRow)
            val rId = if (rIdIdx in 0 until count) csvRow[rIdIdx] else ""
            if (rId.isNotBlank() && routeIdToInfo.containsKey(rId)) {
                val tId = if (tIdIdx in 0 until count) csvRow[tIdIdx] else ""
                if (tId.isNotBlank()) {
                    val shId = if (shIdIdx in 0 until count) csvRow[shIdIdx] else ""
                    val hSign = if (hIdx in 0 until count) csvRow[hIdx] else ""
                    val dirStr = if (dirIdx in 0 until count) csvRow[dirIdx] else "0"
                    val sId = if (sIdIdx in 0 until count) csvRow[sIdIdx] else ""
                    tripsCache[tId] = TripRow(rId, hSign, dirStr.toIntOrNull() ?: 0, sId)
                    routeIdToTripIds.getOrPut(rId) { ArrayList() }.add(tId)
                    if (shId.isNotBlank()) routeIdToShapeIds.getOrPut(rId) { mutableSetOf() }.add(shId)
                }
            }
        }
    }

    private fun parseGtfsShapes(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream)); val csvRow = Array(40) { "" }
        val cols = fastParseCsvToArray(reader.readLine() ?: return, csvRow)
        var idIdx = -1; var latIdx = -1; var lonIdx = -1; var seqIdx = -1
        for (i in 0 until cols) { when (csvRow[i]) { "shape_id" -> idIdx = i; "shape_pt_lat" -> latIdx = i; "shape_pt_lon" -> lonIdx = i; "shape_pt_sequence" -> seqIdx = i } }
        val neededShapes = routeIdToShapeIds.values.flatten().toSet()
        val tempMap = HashMap<String, ArrayList<Pair<Int, RoutePoint>>>()
        reader.forEachLine { line ->
            val count = fastParseCsvToArray(line, csvRow)
            val sId = if (idIdx in 0 until count) csvRow[idIdx] else ""
            if (sId.isNotBlank() && neededShapes.contains(sId)) {
                val lat = (if (latIdx in 0 until count) csvRow[latIdx] else "").toDoubleOrNull()
                val lon = (if (lonIdx in 0 until count) csvRow[lonIdx] else "").toDoubleOrNull()
                val seq = (if (seqIdx in 0 until count) csvRow[seqIdx] else "").toIntOrNull()
                if (lat != null && lon != null && seq != null) { tempMap.getOrPut(sId) { ArrayList(200) }.add(seq to RoutePoint(lat, lon)) }
            }
        }
        tempMap.forEach { (id, list) -> list.sortBy { it.first }; rawShapesCache[id] = list.map { it.second } }
    }

    private fun parseStopTimes(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream)); val csvRow = Array(40) { "" }
        val cols = fastParseCsvToArray(reader.readLine() ?: return, csvRow)
        var tIdx = -1; var dIdx = -1; var sIdx = -1; var seqIdx = -1
        for (i in 0 until cols) { when (csvRow[i]) { "trip_id" -> tIdx = i; "departure_time" -> dIdx = i; "stop_id" -> sIdx = i; "stop_sequence" -> seqIdx = i } }
        val timePool = HashMap<String, String>(2_000); val stopPool = HashMap<String, String>(5_000)
        reader.forEachLine { line ->
            val count = fastParseCsvToArray(line, csvRow)
            val tId = if (tIdx in 0 until count) csvRow[tIdx] else ""
            if (tId.isNotBlank() && tripsCache.containsKey(tId)) {
                val sId = if (sIdx in 0 until count) csvRow[sIdx] else ""
                if (sId.isNotBlank()) {
                    val dTime = if (dIdx in 0 until count) csvRow[dIdx] else ""
                    val seqStr = if (seqIdx in 0 until count) csvRow[seqIdx] else "0"
                    stopTimesCache.getOrPut(tId) { ArrayList(35) }.add(StopTimeRow(stopPool.getOrPut(sId){sId}, timePool.getOrPut(dTime){dTime}, seqStr.toIntOrNull() ?: 0))
                }
            }
        }
    }

    private fun parseGtfsStops(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream)); val csvRow = Array(40) { "" }
        val cols = fastParseCsvToArray(reader.readLine() ?: return, csvRow)
        var idI = -1; var nameI = -1; var latI = -1; var lonI = -1
        for (i in 0 until cols) { when (csvRow[i]) { "stop_id" -> idI = i; "stop_name" -> nameI = i; "stop_lat" -> latI = i; "stop_lon" -> lonI = i } }
        reader.forEachLine { line ->
            val count = fastParseCsvToArray(line, csvRow)
            val stopId = if (idI in 0 until count) csvRow[idI] else ""
            if (stopId.isNotBlank()) {
                val name = if (nameI in 0 until count) csvRow[nameI] else ""
                val lat = (if (latI in 0 until count) csvRow[latI] else "").toDoubleOrNull()
                val lon = (if (lonI in 0 until count) csvRow[lonI] else "").toDoubleOrNull()
                if (lat != null && lon != null) { stopsCache[stopId] = GtfsStop(stopId, name, lat, lon) }
            }
        }
    }

    private fun buildRouteShapes() {
        val result = mutableMapOf<String, RouteShape>()
        routeIdToInfo.forEach { (rId, info) ->
            val shapeIds = routeIdToShapeIds[rId] ?: return@forEach
            val allPoints = mutableListOf<RoutePoint>()
            shapeIds.forEach { sid -> rawShapesCache[sid]?.let { allPoints.addAll(it) } }
            if (allPoints.isNotEmpty()) result[info.number] = RouteShape(info.number, info.type, allPoints, emptyList())
        }
        _routeShapes.value = result
        rawShapesCache.clear(); routeIdToShapeIds.clear()
    }

    fun loadRecentRoutes() {
        val savedIds = prefs.getString("RECENT_ROUTES", "") ?: ""
        if (savedIds.isNotEmpty()) {
            val ids = savedIds.split("|")
            _recentRoutes.value = ids.mapNotNull { routeIdToInfo[it] }
        }
    }

    fun addToRecent(route: RouteInfo) {
        val current = _recentRoutes.value.toMutableList()
        current.removeAll { it.id == route.id }; current.add(0, route)
        val limited = current.take(5)
        _recentRoutes.value = limited
        prefs.edit().putString("RECENT_ROUTES", limited.joinToString("|") { it.id }).apply()
    }

    fun loadRouteSchedule(routeId: String) {
        _scheduleState.value = ScheduleState.Loading("Загрузка расписания...")
        viewModelScope.launch(Dispatchers.Default) {
            if (!_isGtfsLoaded.value) _isGtfsLoaded.filter { it }.first()
            val route = routeIdToInfo[routeId] ?: run { _scheduleState.value = ScheduleState.Error("Маршрут не найден"); return@launch }
            val trips = routeIdToTripIds[routeId] ?: emptyList()
            val longestTripDir0 = findLongestTrip(trips.filter{ tripsCache[it]?.directionId == 0 })
            val longestTripDir1 = findLongestTrip(trips.filter{ tripsCache[it]?.directionId == 1 })
            val sch = RouteSchedule(
                routeId, route.number, route.name, route.type,
                mapStops(longestTripDir0?.second), tripsCache[longestTripDir0?.first]?.headsign ?: "",
                mapStops(longestTripDir1?.second), tripsCache[longestTripDir1?.first]?.headsign ?: ""
            )
            _scheduleState.value = ScheduleState.Success(sch)
        }
    }

    fun loadStopBoard(stopId: String) {
        _stopBoardDepartures.value = emptyList()
        viewModelScope.launch(Dispatchers.Default) {
            if (!_isGtfsLoaded.value) _isGtfsLoaded.filter { it }.first()
            val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            val nowTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val allDepartures = mutableListOf<StopDeparture>()
            tripsCache.forEach { (tripId, trip) ->
                val rule = calendarCache[trip.serviceId] ?: return@forEach
                val isActiveToday = when(day) { Calendar.MONDAY -> rule.monday; Calendar.TUESDAY -> rule.tuesday; Calendar.WEDNESDAY -> rule.wednesday; Calendar.THURSDAY -> rule.thursday; Calendar.FRIDAY -> rule.friday; Calendar.SATURDAY -> rule.saturday; Calendar.SUNDAY -> rule.sunday; else -> false }
                if (!isActiveToday) return@forEach
                val stopTime = stopTimesCache[tripId]?.find { it.stopId == stopId } ?: return@forEach
                if (stopTime.departureTime >= nowTime) {
                    val route = routeIdToInfo[trip.routeId] ?: return@forEach
                    allDepartures.add(StopDeparture(route.number, trip.headsign, stopTime.departureTime, route.type))
                }
            }
            _stopBoardDepartures.value = allDepartures.sortedBy { it.time }.distinctBy { it.routeNumber + it.headsign + it.time }.take(20)
        }
    }

    private fun findLongestTrip(ids: List<String>): Pair<String, List<StopTimeRow>>? {
        var longestTripId: String? = null; var longestTripStops: List<StopTimeRow>? = null
        for (id in ids.take(200)) {
            val tripStops = stopTimesCache[id] ?: continue
            if (tripStops.size > (longestTripStops?.size ?: 0)) { longestTripStops = tripStops; longestTripId = id }
        }
        return if (longestTripId != null && longestTripStops != null) longestTripId to longestTripStops else null
    }

    private fun mapStops(r: List<StopTimeRow>?): List<StopTime> = r?.sortedBy{it.stopSequence}?.map{StopTime(it.stopId, stopsCache[it.stopId]?.name?:"", it.departureTime, it.departureTime, it.stopSequence)} ?: emptyList()

    fun loadDeparturesForStop(rId: String, sId: String, dId: Int) {
        _stopDepartures.value = emptyList()
        viewModelScope.launch(Dispatchers.Default) {
            if (!_isGtfsLoaded.value) _isGtfsLoaded.filter { it }.first()
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
    fun forceUpdateGtfs() { viewModelScope.launch { val f = File(getApplication<Application>().filesDir, "gtfs_tallinn.zip"); File(getApplication<Application>().filesDir, "strada_cache.bin").delete(); if (downloadGtfs(f)==true) parseGtfsFile(f) } }
    fun setUpdateMode(m: Int) { updateMode.intValue = m; prefs.edit().putInt("UPDATE_MODE", m).apply() }
    fun clearRouteOnMap() { selectedRouteOnMap.value = null; viewModelScope.launch { fetchBuses() } }

    private suspend fun fetchBuses() {
        try {
            val r = withContext(Dispatchers.IO) { client.newCall(Request.Builder().url("https://transport.tallinn.ee/gps.txt").build()).execute().use { it.body?.string() ?: "" } }
            val filterNumber = selectedRouteOnMap.value
            val favs = _favoriteRoutes.value
            val onlyFavs = showOnlyFavorites.value

            _buses.value = r.lines().mapNotNull { l ->
                val p = l.split(",")
                if (p.size < 7) return@mapNotNull null
                val type = if (p.getOrElse(0) { "" } == "3") "Tram" else "Bus"
                val lineNumber = p.getOrElse(1) { "" }
                if (lineNumber.isBlank()) return@mapNotNull null

                // Фильтр избранных
                if (onlyFavs && !favs.contains(lineNumber)) return@mapNotNull null

                val lat = p.getOrElse(3) { "" }.toDoubleOrNull()?.div(1_000_000.0) ?: return@mapNotNull null
                val lon = p.getOrElse(2) { "" }.toDoubleOrNull()?.div(1_000_000.0) ?: return@mapNotNull null
                val bearing = p.getOrElse(5) { "" }.toFloatOrNull() ?: 0f
                if (lat !in 57.0..60.5 || lon !in 21.0..28.5) return@mapNotNull null
                if (filterNumber != null) { if (lineNumber != filterNumber) return@mapNotNull null }
                else { if (type == "Bus" && !showBus.value) return@mapNotNull null; if (type == "Tram" && !showTram.value) return@mapNotNull null }
                BusInfo(p.getOrElse(6){""}, type, lineNumber, lat, lon, bearing)
            }
        } catch (e: Exception) { Log.e("BusViewModel", "fetchBuses error", e) }
    }

    fun toggleBus(e: Boolean) { showBus.value = e; prefs.edit().putBoolean("SHOW_BUS", e).apply(); viewModelScope.launch { fetchBuses() } }
    fun toggleTram(e: Boolean) { showTram.value = e; prefs.edit().putBoolean("SHOW_TRAM", e).apply(); viewModelScope.launch { fetchBuses() } }
    fun setTheme(m: Int) { themeMode.intValue = m; prefs.edit().putInt("THEME_MODE", m).apply(); AppCompatDelegate.setDefaultNightMode(m) }
    fun setLanguage(l: String) { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(l)) }
    fun selectRouteOnMap(n: String?) { selectedRouteOnMap.value = n; viewModelScope.launch { fetchBuses() } }

    data class TripRow(val routeId: String, val headsign: String, val directionId: Int, val serviceId: String) : Serializable
    private class StopTimeRow(val stopId: String, val departureTime: String, val stopSequence: Int) : Serializable
    class Factory(private val a: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(m: Class<T>): T = BusViewModel(a) as T
    }
}