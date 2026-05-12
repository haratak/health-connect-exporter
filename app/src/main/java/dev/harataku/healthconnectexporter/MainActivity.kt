package dev.harataku.healthconnectexporter

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>
    private lateinit var statusView: TextView
    private lateinit var versionView: TextView
    private lateinit var selectedPeriodView: TextView
    private lateinit var rangeView: TextView
    private lateinit var activeCaloriesView: TextView
    private lateinit var totalCaloriesView: TextView
    private lateinit var stepsView: TextView
    private lateinit var dailyRowsView: TextView
    private lateinit var detailView: TextView
    private lateinit var stepSourceModeView: TextView
    private lateinit var stepSourceSummaryView: TextView
    private lateinit var updateStatusView: TextView
    private lateinit var permissionButton: Button
    private lateinit var refreshButton: Button
    private lateinit var shareButton: Button
    private lateinit var copyButton: Button
    private lateinit var stepSourcePickerButton: Button
    private lateinit var healthConnectUpdateButton: Button
    private lateinit var checkAppUpdateButton: Button
    private lateinit var openAppUpdateButton: Button
    private var latestSnapshot: HealthSnapshot? = null
    private var latestRelease: AppRelease? = null
    private var downloadedUpdateApk: File? = null
    private var isAppUpdateDownloadRunning: Boolean = false
    private var selectedDateRange: LocalDateRange = LocalDateRange.lastDays(7)
    private var selectedStepSourcePackage: String? = null
    private var latestStepSourceSummaries: List<StepSourceSummary> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            if (granted.contains(ACTIVE_CALORIES_PERMISSION)) {
                readHealthConnectData()
            } else {
                renderPermissionMissing(granted)
            }
        }

        setContentView(buildContentView())
        checkAvailabilityAndPermissions()
    }

    private fun buildContentView(): ScrollView {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(padding, padding, padding, padding)
        }

        content.addView(TextView(this).apply {
            text = "Health Connect Exporter"
            textSize = 24f
        })

        statusView = content.label("Status: checking Health Connect")
        versionView = content.label("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        selectedPeriodView = content.label("Selected period: ${selectedDateRange.displayLabel()}")
        rangeView = content.label("Range: not queried yet")
        activeCaloriesView = content.label("Active calories: not loaded")
        totalCaloriesView = content.label("Total calories: not loaded")
        stepsView = content.label("Steps: not loaded")
        dailyRowsView = content.label("Daily rows: not loaded")
        stepSourceModeView = content.label("Step source mode: All sources")
        stepSourceSummaryView = content.label("Step source breakdown: not loaded")
        detailView = content.label("Details: waiting")
        updateStatusView = content.label("App update: not checked")

        permissionButton = content.button("Request Health Connect permissions") {
            requestPermissions.launch(PERMISSIONS)
        }
        refreshButton = content.button("Refresh selected period") {
            readHealthConnectData()
        }
        stepSourcePickerButton = content.button("Select step source") {
            showStepSourcePicker()
        }
        content.button("Today") {
            selectDateRange(LocalDateRange.today(), refresh = true)
        }
        content.button("Yesterday") {
            selectDateRange(LocalDateRange.yesterday(), refresh = true)
        }
        content.button("Last 7 days") {
            selectDateRange(LocalDateRange.lastDays(7), refresh = true)
        }
        content.button("Last 30 days") {
            selectDateRange(LocalDateRange.lastDays(30), refresh = true)
        }
        content.button("Custom date range") {
            pickCustomDateRange()
        }
        shareButton = content.button("Share latest summary") {
            shareLatestSnapshot()
        }
        copyButton = content.button("Copy latest summary") {
            copyLatestSnapshot()
        }
        healthConnectUpdateButton = content.button("Open Health Connect in Play Store") {
            openHealthConnectInstallOrUpdate()
        }
        checkAppUpdateButton = content.button("Check for app update") {
            checkForAppUpdate()
        }
        openAppUpdateButton = content.button("Download and install update") {
            downloadAndInstallLatestAppUpdate()
        }

        shareButton.isEnabled = false
        copyButton.isEnabled = false
        stepSourcePickerButton.isEnabled = false
        openAppUpdateButton.isEnabled = false

        return ScrollView(this).apply {
            clipToPadding = false
            addView(content)
            applySystemBarInsets(padding)
        }
    }

    private fun ScrollView.applySystemBarInsets(basePadding: Int) {
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    private fun LinearLayout.label(textValue: String): TextView {
        val view = TextView(context).apply {
            text = textValue
            textSize = 16f
            setPadding(0, 14, 0, 0)
            setLineSpacing(0f, 1.12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        addView(view)
        return view
    }

    private fun LinearLayout.button(textValue: String, onClick: () -> Unit): Button {
        val button = Button(context).apply {
            text = textValue
            setAllCaps(false)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 18
            }
        }
        addView(button)
        return button
    }

    private fun checkAvailabilityAndPermissions() {
        when (HealthConnectClient.getSdkStatus(this, HEALTH_CONNECT_PROVIDER_PACKAGE)) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                statusView.text = "Status: Health Connect is unavailable on this device."
                permissionButton.isEnabled = false
                refreshButton.isEnabled = false
                healthConnectUpdateButton.isEnabled = false
            }

            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                statusView.text = "Status: Health Connect needs to be installed or updated."
                permissionButton.isEnabled = false
                refreshButton.isEnabled = false
                healthConnectUpdateButton.isEnabled = true
            }

            HealthConnectClient.SDK_AVAILABLE -> {
                healthConnectUpdateButton.isEnabled = false
                lifecycleScope.launch {
                    val client = HealthConnectClient.getOrCreate(this@MainActivity)
                    val granted = client.permissionController.getGrantedPermissions()
                    if (granted.contains(ACTIVE_CALORIES_PERMISSION)) {
                        readHealthConnectData()
                    } else {
                        renderPermissionMissing(granted)
                    }
                }
            }
        }
    }

    private fun selectDateRange(range: LocalDateRange, refresh: Boolean) {
        selectedDateRange = range
        selectedPeriodView.text = "Selected period: ${range.displayLabel()}"
        if (refresh) {
            readHealthConnectData()
        }
    }

    private fun pickCustomDateRange() {
        val currentStart = selectedDateRange.startDate
        showDatePicker("Start date", currentStart) { startDate ->
            val suggestedEnd = maxOf(selectedDateRange.endDate, startDate)
            showDatePicker("End date", suggestedEnd) { endDate ->
                if (endDate.isBefore(startDate)) {
                    statusView.text = "Status: custom range end date must be on or after start date."
                    return@showDatePicker
                }
                selectDateRange(LocalDateRange(startDate, endDate), refresh = true)
            }
        }
    }

    private fun showDatePicker(title: String, initialDate: LocalDate, onSelected: (LocalDate) -> Unit) {
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                onSelected(LocalDate.of(year, month + 1, dayOfMonth))
            },
            initialDate.year,
            initialDate.monthValue - 1,
            initialDate.dayOfMonth
        ).apply {
            setTitle(title)
            show()
        }
    }

    private fun readHealthConnectData() {
        lifecycleScope.launch {
            statusView.text = "Status: reading Health Connect for ${selectedDateRange.displayLabel()}"
            refreshButton.isEnabled = false
            shareButton.isEnabled = false
            copyButton.isEnabled = false

            when (HealthConnectClient.getSdkStatus(this@MainActivity, HEALTH_CONNECT_PROVIDER_PACKAGE)) {
                HealthConnectClient.SDK_UNAVAILABLE -> {
                    statusView.text = "Status: Health Connect is unavailable on this device."
                    refreshButton.isEnabled = false
                    return@launch
                }

                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    statusView.text = "Status: Health Connect needs to be installed or updated."
                    refreshButton.isEnabled = false
                    healthConnectUpdateButton.isEnabled = true
                    return@launch
                }
            }

            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.contains(ACTIVE_CALORIES_PERMISSION)) {
                renderPermissionMissing(granted)
                refreshButton.isEnabled = true
                return@launch
            }

            try {
                val snapshot = readSnapshot(client, granted, selectedDateRange)
                renderSnapshot(snapshot)
            } catch (securityException: SecurityException) {
                statusView.text = "Status: permission was revoked before reading."
                detailView.text = "Details: ${securityException.message ?: "SecurityException"}"
            } catch (ioException: IOException) {
                statusView.text = "Status: Health Connect storage read failed."
                detailView.text = "Details: ${ioException.message ?: "IOException"}"
            } catch (illegalStateException: IllegalStateException) {
                statusView.text = "Status: Health Connect cannot serve this request."
                detailView.text = "Details: ${illegalStateException.message ?: "IllegalStateException"}"
            } catch (exception: Exception) {
                statusView.text = "Status: unexpected Health Connect error."
                detailView.text = "Details: ${exception.javaClass.simpleName}: ${exception.message ?: "no message"}"
            } finally {
                refreshButton.isEnabled = true
            }
        }
    }

    private suspend fun readSnapshot(
        client: HealthConnectClient,
        granted: Set<String>,
        dateRange: LocalDateRange
    ): HealthSnapshot {
        val zoneId = ZoneId.systemDefault()
        val requestedStepSource = selectedStepSourcePackage
        val stepSourceSummaries = if (granted.contains(STEPS_PERMISSION)) {
            readStepSourceSummaries(client, dateRange, zoneId)
        } else {
            emptyList()
        }
        val dailyRows = dateRange.dates().map { date ->
            readDailyRow(client, granted, date, zoneId, requestedStepSource)
        }

        return HealthSnapshot(
            dateRange = dateRange,
            startTime = dateRange.startInstant(zoneId),
            endTime = dateRange.endInstant(zoneId),
            stepSourceSummaries = stepSourceSummaries,
            selectedStepSourcePackage = requestedStepSource,
            dailyRows = dailyRows,
            activeAggregateKilocalories = dailyRows.sumDoubleOrNull { it.activeAggregateKilocalories },
            activeRecordKilocalories = dailyRows.sumOf { it.activeRecordKilocalories },
            activeRecordCount = dailyRows.sumOf { it.activeRecordCount },
            totalKilocalories = if (granted.contains(TOTAL_CALORIES_PERMISSION)) {
                dailyRows.sumDoubleOrNull { it.totalKilocalories }
            } else {
                null
            },
            totalCaloriesPermissionGranted = granted.contains(TOTAL_CALORIES_PERMISSION),
            steps = if (granted.contains(STEPS_PERMISSION)) {
                dailyRows.sumLongOrNull { it.steps }
            } else {
                null
            },
            stepsPermissionGranted = granted.contains(STEPS_PERMISSION)
        )
    }

    private suspend fun readDailyRow(
        client: HealthConnectClient,
        granted: Set<String>,
        date: LocalDate,
        zoneId: ZoneId,
        stepSourcePackage: String?
    ): DailyHealthRow {
        val startTime = date.atStartOfDay(zoneId).toInstant()
        val endTime = date.plusDays(1).atStartOfDay(zoneId).toInstant()
        var pageToken: String? = null
        var activeRecordCount = 0
        var activeRecordKilocalories = 0.0
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = ActiveCaloriesBurnedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    pageToken = pageToken
                )
            )
            activeRecordCount += response.records.size
            activeRecordKilocalories += response.records.sumOf { it.energy.inKilocalories }
            pageToken = response.pageToken
        } while (pageToken != null)

        val baseMetrics = buildSet {
            add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (granted.contains(TOTAL_CALORIES_PERMISSION)) {
                add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            }
        }

        val aggregate = client.aggregate(
            AggregateRequest(
                metrics = baseMetrics,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
        )
        val stepAggregate = if (granted.contains(STEPS_PERMISSION)) {
            if (stepSourcePackage != null) {
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                        dataOriginFilter = setOf(DataOrigin(stepSourcePackage))
                    )
                )
            } else {
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )
                )
            }
        } else {
            null
        }
        val stepsValue = if (granted.contains(STEPS_PERMISSION)) {
            stepAggregate?.get(StepsRecord.COUNT_TOTAL)
        } else {
            null
        }

        return DailyHealthRow(
            date = date,
            activeAggregateKilocalories = aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
            activeRecordKilocalories = activeRecordKilocalories,
            activeRecordCount = activeRecordCount,
            totalKilocalories = if (granted.contains(TOTAL_CALORIES_PERMISSION)) {
                aggregate[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
            } else {
                null
            },
            steps = if (granted.contains(STEPS_PERMISSION)) {
                stepsValue
            } else {
                null
            }
        )
    }

    private suspend fun readStepSourceSummaries(
        client: HealthConnectClient,
        dateRange: LocalDateRange,
        zoneId: ZoneId
    ): List<StepSourceSummary> {
        val startTime = dateRange.startInstant(zoneId)
        val endTime = dateRange.endInstant(zoneId)
        val summaries = linkedMapOf<String, StepSourceSummary>()
        var pageToken: String? = null

        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    pageToken = pageToken
                )
            )

            response.records.forEach { record ->
                val packageName = record.metadata.dataOrigin.packageName.ifBlank { "Unknown package" }
                val existing = summaries[packageName]
                summaries[packageName] = StepSourceSummary(
                    packageName = packageName,
                    displayLabel = resolveStepSourceDisplayLabel(packageName),
                    stepCount = (existing?.stepCount ?: 0L) + record.count,
                    recordCount = (existing?.recordCount ?: 0) + 1
                )
            }
            pageToken = response.pageToken
        } while (pageToken != null)

        return summaries.values.sortedWith(
            compareByDescending<StepSourceSummary> { it.stepCount }.thenBy { it.displayLabel }
        )
    }

    private fun renderSnapshot(snapshot: HealthSnapshot) {
        latestSnapshot = snapshot
        latestStepSourceSummaries = snapshot.stepSourceSummaries
        selectedStepSourcePackage = snapshot.selectedStepSourcePackage
        shareButton.isEnabled = true
        copyButton.isEnabled = true

        val formatter = displayFormatter()
        val activeText = snapshot.activeAggregateKilocalories?.let { formatNumber(it) } ?: "no aggregate data"
        val totalText = when {
            !snapshot.totalCaloriesPermissionGranted -> "permission not granted"
            snapshot.totalKilocalories == null -> "no aggregate data"
            else -> "${formatNumber(snapshot.totalKilocalories)} kcal"
        }
        val stepsText = when {
            !snapshot.stepsPermissionGranted -> "permission not granted"
            snapshot.steps == null -> "no aggregate data"
            snapshot.steps == 0L && snapshot.hasSelectedStepSourceNoData() -> "0 (selected source has no records)"
            else -> "%,d".format(snapshot.steps)
        }
        val stepSourceMode = selectedStepSourceModeLabel(snapshot)
        val stepSourceBreakdown = snapshot.stepSourceSummaryText()

        statusView.text = "Status: read complete"
        selectedPeriodView.text = "Selected period: ${snapshot.dateRange.displayLabel()}"
        rangeView.text = "Range: ${formatter.format(snapshot.startTime)} to ${formatter.format(snapshot.endTime)}"
        activeCaloriesView.text = "Active calories: $activeText kcal"
        totalCaloriesView.text = "Total calories: $totalText"
        stepsView.text = "Steps ($stepSourceMode): $stepsText"
        dailyRowsView.text = snapshot.dailyRowsText(includeHeader = true)
        detailView.text = if (snapshot.activeRecordCount == 0) {
            "Details: no ActiveCaloriesBurnedRecord entries found in this range."
        } else if (snapshot.stepsPermissionGranted && snapshot.selectedStepSourcePackage != null && snapshot.hasSelectedStepSourceNoData()) {
            "Details: ${snapshot.activeRecordCount} active calorie records read; raw record sum ${formatNumber(snapshot.activeRecordKilocalories)} kcal. Selected step source has no records in this period."
        } else if (snapshot.stepsPermissionGranted && snapshot.stepSourceSummaries.isEmpty()) {
            "Details: ${snapshot.activeRecordCount} active calorie records read; raw record sum ${formatNumber(snapshot.activeRecordKilocalories)} kcal. No step records found in this period."
        } else {
            "Details: ${snapshot.activeRecordCount} active calorie records read; raw record sum ${formatNumber(snapshot.activeRecordKilocalories)} kcal."
        }
        stepSourceModeView.text = "Step source mode: $stepSourceMode"
        stepSourceSummaryView.text = if (snapshot.stepsPermissionGranted) {
            "Step source breakdown:\n$stepSourceBreakdown"
        } else {
            "Step source breakdown: steps permission not granted"
        }
        stepSourcePickerButton.isEnabled = snapshot.stepsPermissionGranted && snapshot.stepSourceSummaries.isNotEmpty()
        stepSourcePickerButton.text = if (snapshot.selectedStepSourcePackage == null) {
            "Select step source (all)"
        } else {
            "Select step source"
        }
    }

    private fun renderPermissionMissing(granted: Set<String>) {
        statusView.text = "Status: active calories permission is required."
        detailView.text = "Details: granted ${granted.size} of ${PERMISSIONS.size} requested Health Connect permissions."
        permissionButton.isEnabled = true
        refreshButton.isEnabled = false
        healthConnectUpdateButton.isEnabled = false
        shareButton.isEnabled = latestSnapshot != null
        copyButton.isEnabled = latestSnapshot != null
        stepSourceModeView.text = "Step source mode: All sources"
        stepSourceSummaryView.text = "Step source breakdown: steps permission required"
        stepSourcePickerButton.isEnabled = false
        latestStepSourceSummaries = emptyList()
        if (selectedStepSourcePackage != null) {
            selectedStepSourcePackage = null
        }
    }

    private fun showStepSourcePicker() {
        if (latestStepSourceSummaries.isEmpty()) {
            statusView.text = "Status: no step source data is available."
            return
        }

        val sourceLabels = latestStepSourceSummaries.map { it.displayLabel }
        val selectable = listOf<String?>(null) + latestStepSourceSummaries.map { it.packageName }
        var selectedIndex = selectable.indexOf(selectedStepSourcePackage).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this).apply {
            setTitle("Select step source")
            setSingleChoiceItems(
                (listOf("All sources") + sourceLabels).toTypedArray(),
                selectedIndex
            ) { _, which ->
                selectedIndex = which
            }
            setPositiveButton("Apply") { _, _ ->
                val newSelection = selectable[selectedIndex]
                if (newSelection != selectedStepSourcePackage) {
                    selectedStepSourcePackage = newSelection
                    readHealthConnectData()
                }
            }
            setNegativeButton("Cancel", null)
        }.show()
    }

    private fun resolveStepSourceDisplayLabel(packageName: String): String {
        if (packageName == "Unknown package") {
            return packageName
        }
        val appLabel = runCatching {
            packageManager.getApplicationInfo(packageName, 0).let {
                packageManager.getApplicationLabel(it).toString()
            }
        }.getOrNull()
        return if (appLabel.isNullOrBlank() || appLabel == packageName) {
            packageName
        } else {
            "$appLabel ($packageName)"
        }
    }

    private fun openHealthConnectInstallOrUpdate() {
        val uri = Uri.parse("market://details?id=$HEALTH_CONNECT_PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
        startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.android.vending")
            putExtra("overlay", true)
            putExtra("callerId", packageName)
        })
    }

    private fun checkForAppUpdate() {
        lifecycleScope.launch {
            updateStatusView.text = "App update: checking GitHub Releases"
            checkAppUpdateButton.isEnabled = false
            openAppUpdateButton.isEnabled = false
            downloadedUpdateApk = null

            runCatching { fetchLatestRelease() }
                .onSuccess { release ->
                    latestRelease = release
                    val comparison = compareVersionNames(release.versionName, BuildConfig.VERSION_NAME)
                    updateStatusView.text = when {
                        comparison > 0 -> "App update: ${release.versionName} is available. Current version is ${BuildConfig.VERSION_NAME}."
                        comparison == 0 -> "App update: ${release.versionName} is the current version."
                        else -> "App update: latest release ${release.versionName} is older than this build (${BuildConfig.VERSION_NAME})."
                    }
                    openAppUpdateButton.isEnabled = comparison > 0 && release.apkUrl.isNotBlank() && !isAppUpdateDownloadRunning
                }
                .onFailure { error ->
                    latestRelease = null
                    updateStatusView.text = "App update: check failed (${error.message ?: error.javaClass.simpleName})."
                }

            checkAppUpdateButton.isEnabled = true
        }
    }

    private suspend fun fetchLatestRelease(): AppRelease = withContext(Dispatchers.IO) {
        val connection = (URL(LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "HealthConnectExporter/${BuildConfig.VERSION_NAME}")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("GitHub returned HTTP $responseCode")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val assets = json.getJSONArray("assets")
            val apkAsset = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { asset -> asset.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: throw IOException("latest release has no APK asset")

            AppRelease(
                versionName = json.optString("tag_name").removePrefix("v"),
                releaseUrl = json.optString("html_url"),
                apkUrl = apkAsset.optString("browser_download_url"),
                apkName = apkAsset.optString("name"),
                apkDigest = apkAsset.optString("digest").takeIf { it.isNotBlank() }
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAndInstallLatestAppUpdate() {
        val release = latestRelease ?: return
        val existingApk = downloadedUpdateApk
        if (existingApk != null && existingApk.isFile) {
            startPackageInstaller(existingApk, release)
            return
        }
        if (isAppUpdateDownloadRunning) {
            return
        }

        lifecycleScope.launch {
            isAppUpdateDownloadRunning = true
            openAppUpdateButton.isEnabled = false
            checkAppUpdateButton.isEnabled = false
            updateStatusView.text = "App update: downloading ${release.apkName}"

            runCatching { downloadReleaseApk(release) }
                .onSuccess { apkFile ->
                    downloadedUpdateApk = apkFile
                    updateStatusView.text = "App update: downloaded ${release.apkName}. Opening Android installer."
                    startPackageInstaller(apkFile, release)
                }
                .onFailure { error ->
                    downloadedUpdateApk = null
                    updateStatusView.text = "App update: download failed (${error.message ?: error.javaClass.simpleName})."
                }

            isAppUpdateDownloadRunning = false
            checkAppUpdateButton.isEnabled = true
            openAppUpdateButton.isEnabled = latestRelease == release
        }
    }

    private suspend fun downloadReleaseApk(release: AppRelease): File = withContext(Dispatchers.IO) {
        val updateDirectory = File(cacheDir, APK_UPDATE_CACHE_DIRECTORY).apply {
            mkdirs()
        }
        updateDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.extension.equals("apk", ignoreCase = true)) {
                file.delete()
            }
        }

        val targetFile = File(updateDirectory, release.apkName.safeApkFileName())
        val temporaryFile = File(updateDirectory, "${targetFile.name}.download")
        val digest = MessageDigest.getInstance("SHA-256")

        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "HealthConnectExporter/${BuildConfig.VERSION_NAME}")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("download returned HTTP $responseCode")
            }

            val contentLength = connection.contentLengthLong
            var downloadedBytes = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            connection.inputStream.use { input ->
                temporaryFile.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) {
                            break
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloadedBytes += read
                        if (contentLength > 0) {
                            val percent = ((downloadedBytes * 100) / contentLength).coerceIn(0, 100)
                            withContext(Dispatchers.Main) {
                                updateStatusView.text = "App update: downloading ${release.apkName} ($percent%)."
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                updateStatusView.text = "App update: downloading ${release.apkName} (${downloadedBytes.toHumanSize()})."
                            }
                        }
                    }
                }
            }

            val expectedSha256 = release.apkDigest?.removePrefix("sha256:")
            if (!expectedSha256.isNullOrBlank()) {
                val actualSha256 = digest.digest().toHexString()
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    temporaryFile.delete()
                    throw IOException("downloaded APK SHA-256 did not match GitHub release metadata")
                }
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!temporaryFile.renameTo(targetFile)) {
                temporaryFile.copyTo(targetFile, overwrite = true)
                temporaryFile.delete()
            }
            targetFile
        } finally {
            connection.disconnect()
            if (temporaryFile.exists()) {
                temporaryFile.delete()
            }
        }
    }

    private fun startPackageInstaller(apkFile: File, release: AppRelease) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            updateStatusView.text = "App update: downloaded ${release.versionName}. Allow this app to install unknown apps, then tap Download and install update again."
            openUnknownAppInstallSettings()
            return
        }

        val apkUri = FileProvider.getUriForFile(this, "$packageName.apkprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(installIntent)
        } catch (exception: ActivityNotFoundException) {
            updateStatusView.text = "App update: Android package installer was not available (${exception.message ?: "ActivityNotFoundException"})."
        }
    }

    private fun openUnknownAppInstallSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(settingsIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    private fun shareLatestSnapshot() {
        val summary = latestSnapshot?.toShareText() ?: return
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Health Connect summary")
            putExtra(Intent.EXTRA_TEXT, summary)
        }
        startActivity(Intent.createChooser(sendIntent, "Share Health Connect summary"))
    }

    private fun copyLatestSnapshot() {
        val summary = latestSnapshot?.toShareText() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Health Connect summary", summary))
        statusView.text = "Status: latest summary copied"
    }

    private fun HealthSnapshot.toShareText(): String {
        val formatter = displayFormatter()
        val activeText = activeAggregateKilocalories?.let { "${formatNumber(it)} kcal" } ?: "no aggregate data"
        val totalText = when {
            !totalCaloriesPermissionGranted -> "permission not granted"
            totalKilocalories == null -> "no aggregate data"
            else -> "${formatNumber(totalKilocalories)} kcal"
        }
        val stepsText = when {
            !stepsPermissionGranted -> "permission not granted"
            steps == null -> "no aggregate data"
            else -> "%,d".format(steps)
        }
        val stepSourceMode = selectedStepSourceModeLabel(this)
        val stepSourceBreakdown = stepSourceSummaryTextForShare()

        return """
            Health Connect summary
            Period: ${dateRange.displayLabel()}
            Range: ${formatter.format(startTime)} to ${formatter.format(endTime)}

            Step source mode: $stepSourceMode
            Step source breakdown:
            $stepSourceBreakdown

            Daily rows:
            ${dailyRowsText(includeHeader = false)}

            Period totals:
            Active calories: $activeText
            Total calories: $totalText
            Steps: $stepsText
            Active calorie records: $activeRecordCount
        """.trimIndent()
    }

    private fun selectedStepSourceModeLabel(snapshot: HealthSnapshot): String {
        val selectedPackage = snapshot.selectedStepSourcePackage ?: return "All sources"
        val summary = snapshot.stepSourceSummaries.firstOrNull { it.packageName == selectedPackage }
        return summary?.displayLabel ?: resolveStepSourceDisplayLabel(selectedPackage)
    }

    private fun HealthSnapshot.hasSelectedStepSourceNoData(): Boolean {
        val selectedPackage = selectedStepSourcePackage ?: return false
        return stepSourceSummaries.none { it.packageName == selectedPackage }
    }

    private fun HealthSnapshot.stepSourceSummaryText(): String {
        if (!stepsPermissionGranted) {
            return "permission not granted"
        }
        if (stepSourceSummaries.isEmpty()) {
            return if (selectedStepSourcePackage == null) {
                "No step records found for selected period."
            } else {
                "Selected source has no records for this period."
            }
        }
        return stepSourceSummaries.joinToString("\n") { summary ->
            "${summary.displayLabel}: ${formatNumber(summary.stepCount.toDouble())} steps (${summary.recordCount} records)"
        }
    }

    private fun HealthSnapshot.stepSourceSummaryTextForShare(): String {
        if (!stepsPermissionGranted) {
            return "steps permission not granted"
        }
        if (stepSourceSummaries.isEmpty()) {
            return if (selectedStepSourcePackage == null) {
                "No step records found for selected period."
            } else {
                "Selected source has no records for this period."
            }
        }
        return stepSourceSummaryText()
    }

    private fun compareVersionNames(left: String, right: String): Int {
        val leftParts = left.versionParts()
        val rightParts = right.versionParts()
        val maxSize = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until maxSize) {
            val difference = (leftParts.getOrNull(index) ?: 0) - (rightParts.getOrNull(index) ?: 0)
            if (difference != 0) {
                return difference
            }
        }
        return 0
    }

    private fun String.versionParts(): List<Int> =
        trim().removePrefix("v").split(".", "-", "_").mapNotNull { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull()
        }

    private fun displayFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    private fun dateFormatter(): DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private fun formatNumber(value: Double): String = "%,.1f".format(value)

    private fun String.safeApkFileName(): String {
        val sanitized = replace(Regex("[^A-Za-z0-9._-]"), "_")
        return sanitized.takeIf { it.endsWith(".apk", ignoreCase = true) } ?: "health-connect-exporter-update.apk"
    }

    private fun Long.toHumanSize(): String =
        if (this >= 1_048_576L) {
            "%.1f MB".format(this / 1_048_576.0)
        } else {
            "%.1f KB".format(this / 1_024.0)
        }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun List<DailyHealthRow>.sumDoubleOrNull(selector: (DailyHealthRow) -> Double?): Double? {
        val values = mapNotNull(selector)
        return values.takeIf { it.isNotEmpty() }?.sum()
    }

    private fun List<DailyHealthRow>.sumLongOrNull(selector: (DailyHealthRow) -> Long?): Long? {
        val values = mapNotNull(selector)
        return values.takeIf { it.isNotEmpty() }?.sum()
    }

    private fun HealthSnapshot.dailyRowsText(includeHeader: Boolean): String {
        val rows = dailyRows.joinToString(separator = "\n") { row ->
            val active = row.activeAggregateKilocalories?.let { "${formatNumber(it)} kcal" } ?: "0.0 kcal (no data)"
            val total = when {
                !totalCaloriesPermissionGranted -> "permission not granted"
                row.totalKilocalories == null -> "0.0 kcal (no data)"
                else -> "${formatNumber(row.totalKilocalories)} kcal"
            }
            val stepsText = when {
                !stepsPermissionGranted -> "permission not granted"
                row.steps == null -> "0 (no data)"
                else -> "%,d".format(row.steps)
            }
            "${dateFormatter().format(row.date)} | active $active | total $total | steps $stepsText | active records ${row.activeRecordCount}"
        }

        return if (includeHeader) {
            "Daily rows:\n$rows"
        } else {
            rows
        }
    }

    private data class AppRelease(
        val versionName: String,
        val releaseUrl: String,
        val apkUrl: String,
        val apkName: String,
        val apkDigest: String?
    )

    private data class HealthSnapshot(
        val dateRange: LocalDateRange,
        val startTime: Instant,
        val endTime: Instant,
        val stepSourceSummaries: List<StepSourceSummary>,
        val selectedStepSourcePackage: String?,
        val dailyRows: List<DailyHealthRow>,
        val activeAggregateKilocalories: Double?,
        val activeRecordKilocalories: Double,
        val activeRecordCount: Int,
        val totalKilocalories: Double?,
        val totalCaloriesPermissionGranted: Boolean,
        val steps: Long?,
        val stepsPermissionGranted: Boolean
    )

    private data class StepSourceSummary(
        val packageName: String,
        val displayLabel: String,
        val stepCount: Long,
        val recordCount: Int
    )

    private data class DailyHealthRow(
        val date: LocalDate,
        val activeAggregateKilocalories: Double?,
        val activeRecordKilocalories: Double,
        val activeRecordCount: Int,
        val totalKilocalories: Double?,
        val steps: Long?
    )

    private data class LocalDateRange(
        val startDate: LocalDate,
        val endDate: LocalDate
    ) {
        fun startInstant(zoneId: ZoneId): Instant = startDate.atStartOfDay(zoneId).toInstant()

        fun endInstant(zoneId: ZoneId): Instant = endDate.plusDays(1).atStartOfDay(zoneId).toInstant()

        fun displayLabel(): String {
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE
            return if (startDate == endDate) {
                formatter.format(startDate)
            } else {
                "${formatter.format(startDate)} to ${formatter.format(endDate)}"
            }
        }

        fun dates(): List<LocalDate> {
            val dates = mutableListOf<LocalDate>()
            var current = startDate
            while (!current.isAfter(endDate)) {
                dates += current
                current = current.plusDays(1)
            }
            return dates
        }

        companion object {
            fun today(): LocalDateRange {
                val today = LocalDate.now()
                return LocalDateRange(today, today)
            }

            fun yesterday(): LocalDateRange {
                val yesterday = LocalDate.now().minusDays(1)
                return LocalDateRange(yesterday, yesterday)
            }

            fun lastDays(days: Long): LocalDateRange {
                val today = LocalDate.now()
                return LocalDateRange(today.minusDays(days - 1), today)
            }
        }
    }

    private companion object {
        private const val HEALTH_CONNECT_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/haratak/health-connect-exporter/releases/latest"
        private const val APK_UPDATE_CACHE_DIRECTORY = "apk-updates"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private val ACTIVE_CALORIES_PERMISSION = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
        private val TOTAL_CALORIES_PERMISSION = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
        private val STEPS_PERMISSION = HealthPermission.getReadPermission(StepsRecord::class)
        private val PERMISSIONS = setOf(
            ACTIVE_CALORIES_PERMISSION,
            TOTAL_CALORIES_PERMISSION,
            STEPS_PERMISSION
        )
    }
}
