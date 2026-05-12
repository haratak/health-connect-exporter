package dev.harataku.healthconnectexporter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>
    private lateinit var statusView: TextView
    private lateinit var rangeView: TextView
    private lateinit var activeCaloriesView: TextView
    private lateinit var totalCaloriesView: TextView
    private lateinit var stepsView: TextView
    private lateinit var detailView: TextView
    private lateinit var permissionButton: Button
    private lateinit var refreshButton: Button
    private lateinit var updateButton: Button

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
        rangeView = content.label("Range: not queried yet")
        activeCaloriesView = content.label("Active calories: not loaded")
        totalCaloriesView = content.label("Total calories: not loaded")
        stepsView = content.label("Steps: not loaded")
        detailView = content.label("Details: waiting")

        permissionButton = content.button("Request Health Connect permissions") {
            requestPermissions.launch(PERMISSIONS)
        }
        refreshButton = content.button("Refresh last 7 days") {
            readHealthConnectData()
        }
        updateButton = content.button("Open Health Connect in Play Store") {
            openHealthConnectInstallOrUpdate()
        }

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
                updateButton.isEnabled = false
            }

            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                statusView.text = "Status: Health Connect needs to be installed or updated."
                permissionButton.isEnabled = false
                refreshButton.isEnabled = false
                updateButton.isEnabled = true
            }

            HealthConnectClient.SDK_AVAILABLE -> {
                updateButton.isEnabled = false
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

    private fun readHealthConnectData() {
        lifecycleScope.launch {
            statusView.text = "Status: reading Health Connect"
            refreshButton.isEnabled = false

            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.contains(ACTIVE_CALORIES_PERMISSION)) {
                renderPermissionMissing(granted)
                refreshButton.isEnabled = true
                return@launch
            }

            val endTime = Instant.now()
            val startTime = endTime.minus(Duration.ofDays(7))

            try {
                val snapshot = readSnapshot(client, granted, startTime, endTime)
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
        startTime: Instant,
        endTime: Instant
    ): HealthSnapshot {
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

        val metrics = buildSet {
            add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (granted.contains(TOTAL_CALORIES_PERMISSION)) {
                add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            }
            if (granted.contains(STEPS_PERMISSION)) {
                add(StepsRecord.COUNT_TOTAL)
            }
        }

        val aggregate = client.aggregate(
            AggregateRequest(
                metrics = metrics,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
        )

        return HealthSnapshot(
            startTime = startTime,
            endTime = endTime,
            activeAggregateKilocalories = aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
            activeRecordKilocalories = activeRecordKilocalories,
            activeRecordCount = activeRecordCount,
            totalKilocalories = if (granted.contains(TOTAL_CALORIES_PERMISSION)) {
                aggregate[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
            } else {
                null
            },
            totalCaloriesPermissionGranted = granted.contains(TOTAL_CALORIES_PERMISSION),
            steps = if (granted.contains(STEPS_PERMISSION)) {
                aggregate[StepsRecord.COUNT_TOTAL]
            } else {
                null
            },
            stepsPermissionGranted = granted.contains(STEPS_PERMISSION)
        )
    }

    private fun renderSnapshot(snapshot: HealthSnapshot) {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        val activeText = snapshot.activeAggregateKilocalories?.let { formatNumber(it) } ?: "no aggregate data"
        val totalText = when {
            !snapshot.totalCaloriesPermissionGranted -> "permission not granted"
            snapshot.totalKilocalories == null -> "no aggregate data"
            else -> "${formatNumber(snapshot.totalKilocalories)} kcal"
        }
        val stepsText = when {
            !snapshot.stepsPermissionGranted -> "permission not granted"
            snapshot.steps == null -> "no aggregate data"
            else -> "%,d".format(snapshot.steps)
        }

        statusView.text = "Status: read complete"
        rangeView.text = "Range: ${formatter.format(snapshot.startTime)} to ${formatter.format(snapshot.endTime)}"
        activeCaloriesView.text = "Active calories: $activeText kcal"
        totalCaloriesView.text = "Total calories: $totalText"
        stepsView.text = "Steps: $stepsText"
        detailView.text = if (snapshot.activeRecordCount == 0) {
            "Details: no ActiveCaloriesBurnedRecord entries found in this range."
        } else {
            "Details: ${snapshot.activeRecordCount} active calorie records read; raw record sum ${formatNumber(snapshot.activeRecordKilocalories)} kcal."
        }
    }

    private fun renderPermissionMissing(granted: Set<String>) {
        statusView.text = "Status: active calories permission is required."
        detailView.text = "Details: granted ${granted.size} of ${PERMISSIONS.size} requested Health Connect permissions."
        permissionButton.isEnabled = true
        refreshButton.isEnabled = false
        updateButton.isEnabled = false
    }

    private fun openHealthConnectInstallOrUpdate() {
        val uri = Uri.parse("market://details?id=$HEALTH_CONNECT_PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
        startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.android.vending")
            putExtra("overlay", true)
            putExtra("callerId", packageName)
        })
    }

    private fun formatNumber(value: Double): String = "%,.1f".format(value)

    private data class HealthSnapshot(
        val startTime: Instant,
        val endTime: Instant,
        val activeAggregateKilocalories: Double?,
        val activeRecordKilocalories: Double,
        val activeRecordCount: Int,
        val totalKilocalories: Double?,
        val totalCaloriesPermissionGranted: Boolean,
        val steps: Long?,
        val stepsPermissionGranted: Boolean
    )

    private companion object {
        private const val HEALTH_CONNECT_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
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
