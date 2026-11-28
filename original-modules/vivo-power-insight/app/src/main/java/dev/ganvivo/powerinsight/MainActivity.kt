package dev.ganvivo.powerinsight

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ganvivo.powerinsight.data.DailyTrendRecord
import dev.ganvivo.powerinsight.data.PiePowerCollector
import dev.ganvivo.powerinsight.data.PowerHistoryStore
import dev.ganvivo.powerinsight.data.PowerSnapshot
import dev.ganvivo.powerinsight.data.SnapshotMeta
import dev.ganvivo.powerinsight.export.ReportExporter
import dev.ganvivo.powerinsight.ui.screens.AppsAndTrafficScreen
import dev.ganvivo.powerinsight.ui.screens.ChargeAndHealthScreen
import dev.ganvivo.powerinsight.ui.screens.OverviewScreen
import dev.ganvivo.powerinsight.ui.screens.TemperatureScreen
import dev.ganvivo.powerinsight.ui.screens.TrendAndHistoryScreen
import dev.ganvivo.powerinsight.ui.theme.PowerInsightTheme
import dev.ganvivo.powerinsight.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import kotlin.math.hypot
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            val systemDark = isSystemInDarkTheme()
            val effectiveDark = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            LaunchedEffect(effectiveDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (effectiveDark) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    },
                    navigationBarStyle = if (effectiveDark) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    }
                )
            }

            // 以日/夜间图标为圆心向四周晕染扩散的 Circular Reveal 转场容器
            val graphicsLayer = rememberGraphicsLayer()
            val scope = rememberCoroutineScope()
            var overlayBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
            var revealOrigin by remember { mutableStateOf(Offset.Zero) }
            val revealProgress = remember { Animatable(0f) }

            fun triggerCircularThemeSwitch(targetMode: ThemeMode, origin: Offset) {
                if (overlayBitmap != null) return
                val targetDark = when (targetMode) {
                    ThemeMode.SYSTEM -> systemDark
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                if (targetDark == effectiveDark) {
                    themeMode = targetMode
                    return
                }
                scope.launch {
                    val captured = runCatching { graphicsLayer.toImageBitmap() }.getOrNull()
                    if (captured == null) {
                        themeMode = targetMode
                        return@launch
                    }
                    revealOrigin = origin
                    revealProgress.snapTo(0f)
                    overlayBitmap = captured
                    themeMode = targetMode
                    // 等待新主题完成首帧布局后再平滑晕染扩散，避免任何闪烁
                    withFrameNanos { }
                    revealProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = 560,
                            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
                        )
                    )
                    overlayBitmap = null
                }
            }

            PowerInsightTheme(themeMode = themeMode, dynamicColor = true) {
                val ringTint = MaterialTheme.colorScheme.primary
                Box(modifier = Modifier.fillMaxSize()) {
                    // 底层：实时渲染当前（或刚切换后的新）主题 UI，并同步写入 GraphicsLayer 供下次截帧
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                graphicsLayer.record {
                                    this@drawWithContent.drawContent()
                                }
                                drawLayer(graphicsLayer)
                            }
                    ) {
                        PowerInsightApp(
                            effectiveDark = effectiveDark,
                            themeMode = themeMode,
                            onToggleDayNight = { iconCenter ->
                                val next = if (effectiveDark) ThemeMode.LIGHT else ThemeMode.DARK
                                triggerCircularThemeSwitch(next, iconCenter)
                            },
                            onFollowSystemTheme = { iconCenter ->
                                triggerCircularThemeSwitch(ThemeMode.SYSTEM, iconCenter)
                            }
                        )
                    }

                    // 顶层晕染遮罩：将旧主题截图以图标中心为圆心做反向圆形裁切，让新主题从图标向四周柔顺扩散
                    overlayBitmap?.let { oldScreen ->
                        val p = revealProgress.value
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cx = if (revealOrigin == Offset.Zero) size.width * 0.85f else revealOrigin.x
                            val cy = if (revealOrigin == Offset.Zero) 80.dp.toPx() else revealOrigin.y
                            val maxRadius = max(
                                max(hypot(cx, cy), hypot(size.width - cx, cy)),
                                max(hypot(cx, size.height - cy), hypot(size.width - cx, size.height - cy))
                            ) * 1.04f
                            val radius = maxRadius * p

                            val holePath = Path().apply {
                                if (radius > 0.5f) {
                                    addOval(Rect(center = Offset(cx, cy), radius = radius))
                                }
                            }
                            clipPath(path = holePath, clipOp = ClipOp.Difference) {
                                drawImage(image = oldScreen)
                            }
                            if (radius > 4f && p < 0.99f) {
                                val fade = (1f - p).coerceIn(0f, 1f)
                                // 柔和羽化径向渐变晕染波纹（以图标为中心向四周渐隐扩散）
                                val spread = (46.dp.toPx() * (0.35f + 0.65f * fade)).coerceAtLeast(1f)
                                val outerR = radius + spread
                                if (outerR > 1f) {
                                    val innerStop = ((radius - spread * 0.6f) / outerR).coerceIn(0f, 0.94f)
                                    val peakStop = (radius / outerR).coerceIn(innerStop + 0.01f, 0.98f)
                                    val outerMidStop = ((radius + spread * 0.35f) / outerR).coerceIn(peakStop + 0.005f, 0.995f)
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colorStops = arrayOf(
                                                0f to Color.Transparent,
                                                innerStop to Color.Transparent,
                                                peakStop to ringTint.copy(alpha = fade * 0.34f),
                                                outerMidStop to Color.White.copy(alpha = fade * 0.16f),
                                                1f to Color.Transparent
                                            ),
                                            center = Offset(cx, cy),
                                            radius = outerR
                                        ),
                                        radius = outerR,
                                        center = Offset(cx, cy)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class MainTab(val label: String, val icon: ImageVector) {
    OVERVIEW("总览", Icons.Rounded.Speed),
    APPS("应用流量", Icons.Rounded.Apps),
    CHARGE("充电健康", Icons.Rounded.BatteryChargingFull),
    TEMP("温度采样", Icons.Rounded.DeviceThermostat),
    HISTORY("趋势历史", Icons.Rounded.History)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PowerInsightApp(
    effectiveDark: Boolean,
    themeMode: ThemeMode,
    onToggleDayNight: (Offset) -> Unit,
    onFollowSystemTheme: (Offset) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PowerHistoryStore(context.applicationContext) }
    val collector = remember { PiePowerCollector(context.applicationContext) }
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by remember { mutableIntStateOf(0) }

    // 明确分离「实时数据」与「历史快照回看」两个独立状态，避免混淆
    var liveSnapshot by remember { mutableStateOf<PowerSnapshot?>(null) }
    var viewingHistorySnapshot by remember { mutableStateOf<PowerSnapshot?>(null) }

    var trends by remember { mutableStateOf<List<DailyTrendRecord>>(emptyList()) }
    var historyMetas by remember { mutableStateOf<List<SnapshotMeta>>(emptyList()) }
    var isRefreshingLive by remember { mutableStateOf(false) }
    var isSavingSnapshot by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showExportMenu by remember { mutableStateOf(false) }
    var pendingExportContent by remember { mutableStateOf<String?>(null) }
    var themeIconCenter by remember { mutableStateOf(Offset.Zero) }

    val displayedSnapshot: PowerSnapshot? = viewingHistorySnapshot ?: liveSnapshot
    val isLiveMode: Boolean = viewingHistorySnapshot == null

    val saveDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val payload = pendingExportContent
        pendingExportContent = null
        if (uri != null && payload != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(payload.toByteArray(StandardCharsets.UTF_8))
                    }
                }.onSuccess {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "报告已成功导出", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { err ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "导出失败：${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    suspend fun reloadHistoryLists() {
        withContext(Dispatchers.IO) {
            val metas = store.listSnapshotMetas()
            val daily = store.listDailyTrends()
            withContext(Dispatchers.Main) {
                historyMetas = metas
                trends = daily
            }
        }
    }

    /**
     * 刷新实时数据（不新增冗余历史快照，仅更新实时展示与每日趋势归档）
     */
    fun refreshLiveData(silent: Boolean = false) {
        if (isRefreshingLive || isSavingSnapshot) return
        scope.launch {
            isRefreshingLive = true
            errorMessage = null
            val result = collector.collectToday()
            result.onSuccess { bundle ->
                withContext(Dispatchers.IO) {
                    store.upsertDailyTrends(bundle.historicalDailyTrends)
                    store.upsertDailyTrend(
                        DailyTrendRecord(
                            dateStr = bundle.snapshot.dateStr,
                            totalDrainPct = bundle.snapshot.overview.totalDrainPct,
                            totalChargePct = bundle.snapshot.chargingDetail.todayTotalChargePct,
                            drainDurationMs = bundle.snapshot.overview.drainDurationMs,
                            drainRatePctPerHour = bundle.snapshot.overview.drainRatePctPerHour,
                            screenOnDurationMs = bundle.snapshot.overview.screenOnDurationMs,
                            screenOnDrainPct = bundle.snapshot.overview.screenOnDrainPct,
                            screenOffDurationMs = bundle.snapshot.overview.screenOffDurationMs,
                            screenOffDrainPct = bundle.snapshot.overview.screenOffDrainPct,
                            source = "pie_db"
                        )
                    )
                }
                liveSnapshot = bundle.snapshot
                reloadHistoryLists()
                if (!silent) {
                    val warn = bundle.warnings.firstOrNull()
                    snackbarHostState.showSnackbar(
                        if (warn != null) "实时数据已更新（提示：$warn）"
                        else "实时数据已刷新 · ${bundle.snapshot.appUsages.size} 应用 / ${bundle.snapshot.temperatureSummary.samples.size} 温度采样"
                    )
                }
            }.onFailure { err ->
                errorMessage = err.message ?: "Root 采集失败"
                if (!silent) {
                    snackbarHostState.showSnackbar("实时读取失败：${err.message}")
                }
            }
            isRefreshingLive = false
        }
    }

    /**
     * 采集最新实时数据并永久存档为一条历史快照
     */
    fun captureAndArchiveSnapshot() {
        if (isSavingSnapshot || isRefreshingLive) return
        scope.launch {
            isSavingSnapshot = true
            errorMessage = null
            val result = collector.collectToday()
            result.onSuccess { bundle ->
                withContext(Dispatchers.IO) {
                    store.upsertDailyTrends(bundle.historicalDailyTrends)
                    store.saveSnapshot(bundle.snapshot)
                }
                liveSnapshot = bundle.snapshot
                viewingHistorySnapshot = null // 存档后保持在最新实时态
                reloadHistoryLists()
                snackbarHostState.showSnackbar("已存为历史快照（累计 ${historyMetas.size} 份），主界面仍为实时数据")
            }.onFailure { err ->
                errorMessage = err.message ?: "Root 采集失败"
                snackbarHostState.showSnackbar("存档失败：${err.message}")
            }
            isSavingSnapshot = false
        }
    }

    // 首次进入：先载入本地历史列表，同时立刻拉取实时系统数据进入「实时模式」
    LaunchedEffect(Unit) {
        reloadHistoryLists()
        refreshLiveData(silent = true)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = "耗电洞察",
                                    fontWeight = FontWeight.Bold
                                )
                                displayedSnapshot?.let { snap ->
                                    Text(
                                        text = if (isLiveMode) {
                                            "● 实时模式 · 更新于 ${snap.capturedTimeLabel.takeLast(8)}"
                                        } else {
                                            "历史快照 · ${snap.capturedTimeLabel}"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isLiveMode) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        }
                                    )
                                }
                            }
                        },
                        actions = {
                            // 实时模式下提供一键刷新实时（不新增快照）
                            if (isLiveMode) {
                                IconButton(
                                    enabled = !isRefreshingLive && !isSavingSnapshot,
                                    onClick = { refreshLiveData(silent = false) }
                                ) {
                                    if (isRefreshingLive) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新实时数据")
                                    }
                                }
                            }

                            // 日间 / 夜间模式切换按钮（记录全局圆心坐标，点击触发图标中心四处晕染特效）
                            IconButton(
                                modifier = Modifier.onGloballyPositioned { coords ->
                                    themeIconCenter = coords.boundsInRoot().center
                                },
                                onClick = { onToggleDayNight(themeIconCenter) }
                            ) {
                                Icon(
                                    imageVector = if (effectiveDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                    contentDescription = if (effectiveDark) "切换日间模式" else "切换夜间模式"
                                )
                            }

                            Box {
                                IconButton(
                                    enabled = displayedSnapshot != null,
                                    onClick = { showExportMenu = true }
                                ) {
                                    Icon(Icons.Rounded.Download, contentDescription = "导出与更多")
                                }
                                DropdownMenu(
                                    expanded = showExportMenu,
                                    onDismissRequest = { showExportMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("导出完整 HTML 可视化报告") },
                                        onClick = {
                                            showExportMenu = false
                                            val snap = displayedSnapshot ?: return@DropdownMenuItem
                                            pendingExportContent = ReportExporter.toHtmlReport(snap, trends)
                                            saveDocLauncher.launch("耗电洞察_${snap.dateStr}_${snap.capturedTimeLabel.takeLast(8).replace(":", "")}.html")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("导出原始 JSON 数据") },
                                        onClick = {
                                            showExportMenu = false
                                            val snap = displayedSnapshot ?: return@DropdownMenuItem
                                            pendingExportContent = ReportExporter.toJsonString(snap)
                                            saveDocLauncher.launch("power_snapshot_${snap.dateStr}_${snap.capturedTimeLabel.takeLast(8).replace(":", "")}.json")
                                        }
                                    )
                                    if (themeMode != ThemeMode.SYSTEM) {
                                        DropdownMenuItem(
                                            text = { Text("主题恢复跟随系统") },
                                            onClick = {
                                                showExportMenu = false
                                                onFollowSystemTheme(themeIconCenter)
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )

                    // 历史回看模式下在全局顶部显示醒目横幅，明确告知当前非实时且可一键切回实时
                    AnimatedVisibility(
                        visible = !isLiveMode,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        val hSnap = viewingHistorySnapshot
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "正在回看历史快照（静态存档，非实时）",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    if (hSnap != null) {
                                        Text(
                                            text = "${hSnap.capturedTimeLabel}${if (hSnap.note.isNotBlank()) " · ${hSnap.note}" else ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                FilledTonalButton(
                                    onClick = {
                                        viewingHistorySnapshot = null
                                        refreshLiveData(silent = false)
                                    }
                                ) {
                                    Text("切回实时")
                                }
                            }
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!isWide) {
                    NavigationBar {
                        MainTab.entries.forEachIndexed { idx, tab ->
                            NavigationBarItem(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label, maxLines = 1) }
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (isLiveMode) {
                    ExtendedFloatingActionButton(
                        onClick = { captureAndArchiveSnapshot() },
                        icon = {
                            if (isSavingSnapshot) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Rounded.BookmarkAdd, contentDescription = null)
                            }
                        },
                        text = { Text(if (isSavingSnapshot) "存档中..." else "存为历史快照") }
                    )
                } else {
                    ExtendedFloatingActionButton(
                        onClick = {
                            viewingHistorySnapshot = null
                            refreshLiveData(silent = false)
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = { Icon(Icons.Rounded.RadioButtonChecked, contentDescription = null) },
                        text = { Text("返回实时数据") }
                    )
                }
            }
        ) { innerPadding ->
            Row(modifier = Modifier.fillMaxSize()) {
                if (isWide) {
                    NavigationRail(
                        modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
                    ) {
                        MainTab.entries.forEachIndexed { idx, tab ->
                            NavigationRailItem(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }

                val snap = displayedSnapshot
                if (snap == null) {
                    EmptyOnboardingView(
                        isLoading = isRefreshingLive || isSavingSnapshot,
                        errorMessage = errorMessage,
                        onRetry = { refreshLiveData(silent = false) },
                        contentPadding = innerPadding
                    )
                } else {
                    when (selectedTab) {
                        0 -> OverviewScreen(
                            snapshot = snap,
                            trends = trends,
                            isWide = isWide,
                            isLiveMode = isLiveMode,
                            contentPadding = innerPadding
                        )
                        1 -> AppsAndTrafficScreen(
                            snapshot = snap,
                            contentPadding = innerPadding
                        )
                        2 -> ChargeAndHealthScreen(
                            snapshot = snap,
                            contentPadding = innerPadding
                        )
                        3 -> TemperatureScreen(
                            snapshot = snap,
                            contentPadding = innerPadding
                        )
                        4 -> TrendAndHistoryScreen(
                            currentSnapshot = snap,
                            viewingHistoryId = viewingHistorySnapshot?.id,
                            trends = trends,
                            historyMetas = historyMetas,
                            onSelectSnapshot = { id ->
                                scope.launch {
                                    val loaded = withContext(Dispatchers.IO) { store.loadSnapshot(id) }
                                    if (loaded != null) {
                                        viewingHistorySnapshot = loaded
                                        selectedTab = 0
                                        snackbarHostState.showSnackbar("已切换到历史快照（可随时点顶部「切回实时」）")
                                    }
                                }
                            },
                            onReturnToLive = {
                                viewingHistorySnapshot = null
                                refreshLiveData(silent = false)
                            },
                            onDeleteSnapshot = { id ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { store.deleteSnapshot(id) }
                                    if (viewingHistorySnapshot?.id == id) {
                                        viewingHistorySnapshot = null
                                    }
                                    reloadHistoryLists()
                                    snackbarHostState.showSnackbar("已删除该历史快照")
                                }
                            },
                            onRenameSnapshot = { id, note ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { store.updateSnapshotNote(id, note) }
                                    if (viewingHistorySnapshot?.id == id) {
                                        viewingHistorySnapshot = viewingHistorySnapshot?.copy(note = note)
                                    }
                                    reloadHistoryLists()
                                }
                            },
                            contentPadding = innerPadding
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyOnboardingView(
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    contentPadding: PaddingValues
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(14.dp)
                            .size(34.dp)
                    )
                }
                Text(
                    text = "耗电洞察（Root 完整版）",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "授予 Root 权限后，将直接只读解析系统 PIE 功耗数据库（/data/system/pie/）与内核充电节点（/sys/class/fuelsummary/），完整呈现应用电流亮度、每小时充放、7/30天趋势、前后台流量、电池健康、充电协议电压及 30 秒级温度采样，并本地永久保存历史快照。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (errorMessage != null) {
                    Text(
                        text = "错误信息：$errorMessage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(4.dp))
                Button(enabled = !isLoading, onClick = onRetry) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在读取实时系统数据...")
                    } else {
                        Text("请求 Root 权限并读取实时数据")
                    }
                }
            }
        }
    }
}
