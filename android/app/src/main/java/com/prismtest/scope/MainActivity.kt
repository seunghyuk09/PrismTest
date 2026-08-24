package com.prismtest.scope

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/** 스윕 시간. 한 바퀴 돌리기에 충분하면서 지루하지 않은 길이. */
private const val SWEEP_SECONDS = 6
/** 스윕 중 최소 밝기 변화 총량. 이보다 작으면 각도를 거의 안 바꾼 것이다. */
private const val MIN_SWEEP_MOTION = 60.0

private val OK = Color(0xFF3DBE63)
private val WARN = Color(0xFFE8A93B)
private val BAD = Color(0xFFE04B3F)
private val IDLE = Color(0xFF7C8A91)
private val FRAME = Color(0xFFFFC107)
private val PANEL = Color(0xFF14181B)
private val CARD = Color(0xFF1C2227)
private val DIM = Color(0xFF8B979D)

class MainActivity : ComponentActivity() {

    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var controller: CameraController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = CameraController(this, exec)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Root(controller) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.unbind()
        exec.shutdown()
    }
}

@Composable
private fun Root(controller: CameraController) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF0E1113)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (granted) Inspect(controller)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("카메라 권한을 허용해 주세요", color = Color.White)
            }
        }
    }
}

@Composable
private fun Inspect(controller: CameraController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var settings by remember { mutableStateOf(ManualSettings()) }
    var caps by remember { mutableStateOf(CameraCaps()) }
    var applied by remember { mutableStateOf(AppliedCamera()) }

    var roi by remember { mutableStateOf(Roi(0.5f, 0.5f, 0.30f)) }
    var stats by remember { mutableStateOf(RoiStats.EMPTY) }
    var frameW by remember { mutableIntStateOf(0) }
    var frameH by remember { mutableIntStateOf(0) }

    var baseline by remember { mutableStateOf(Baseline.load(context, Baseline.KIND_STAIN)) }
    var scratchBase by remember { mutableStateOf(Baseline.load(context, Baseline.KIND_SCRATCH)) }

    // 스크래치 스윕 — 손으로 각도를 바꾸는 동안 최댓값을 잡는다.
    var sweeping by remember { mutableStateOf(false) }
    var sweepLeft by remember { mutableIntStateOf(0) }
    var sweepMax by remember { mutableStateOf(0.0) }
    var sweepMotion by remember { mutableStateOf(0.0) }
    var sweepResult by remember { mutableStateOf<Double?>(null) }
    var lastMean by remember { mutableStateOf(-1.0) }
    var note by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var pendingSave by remember { mutableStateOf(false) }
    var pendingRegister by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    val roiRef = rememberUpdatedState(roi)
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
    }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }

    LaunchedEffect(Unit) {
        controller.bind(
            lifecycleOwner, previewView, settings,
            onFrame = { image ->
                try {
                    frameW = image.width
                    frameH = image.height
                    val s = Stats.analyze(image, roiRef.value)
                    stats = s
                    applied = controller.applied

                    if (sweeping) {
                        if (s.scratchScore > sweepMax) sweepMax = s.scratchScore
                        // 프레임 간 밝기 변화로 "실제로 돌리고 있는지"를 본다.
                        // 가만히 두면 변화가 0에 가까워 스윕이 성립하지 않는다.
                        if (lastMean >= 0) sweepMotion += abs(s.mean - lastMean)
                        lastMean = s.mean
                    }
                    if (pendingRegister) {
                        pendingRegister = false
                        val b = baseline.plus(s.stainIndex)
                        baseline = b
                        Baseline.save(context, Baseline.KIND_STAIN, b)
                        msg = "얼룩 기준 · 양품 ${b.n}개 등록됨" +
                            if (b.ready) "" else " (${Baseline.MIN_SAMPLES}개 이상 필요)"
                    }
                    if (pendingSave) {
                        pendingSave = false
                        val file = Store.saveFrame(context, image, "cap")
                        Store.appendCsv(
                            context,
                            buildCsvRow(note, s, baseline, scratchBase, controller.applied, settings,
                                roiRef.value, image.width, image.height, file ?: "", appVersion)
                        )
                        msg = if (file != null) "저장됨" else "저장 실패"
                    }
                } finally {
                    image.close()
                }
            },
            onReady = { caps = it }
        )
    }

    // 스윕 타이머. 프레임 콜백이 아니라 UI 쪽에서 시간을 센다.
    LaunchedEffect(sweeping) {
        if (!sweeping) return@LaunchedEffect
        sweepMax = 0.0; sweepMotion = 0.0; lastMean = -1.0; sweepResult = null
        for (i in SWEEP_SECONDS downTo 1) {
            sweepLeft = i
            delay(1000)
        }
        sweepLeft = 0
        sweeping = false
        sweepResult = sweepMax
    }

    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FFC107")
            textSize = 38f; isFakeBoldText = true; isAntiAlias = true
        }
    }

    val verdict = baseline.judge(stats.stainIndex)
    val tooDark = stats.median < 3 && stats.p99 < 6
    val blurry = stats.focus < 1.0 && !tooDark
    val glare = stats.satRatio > 0.01

    Column(Modifier.fillMaxSize()) {

        // ─────────── 카메라 ───────────
        var boxSize by remember { mutableStateOf(Size.Zero) }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .onSizeChanged { boxSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(frameW, frameH) {
                    detectTapGestures { off ->
                        val rect = fitRect(boxSize, frameW, frameH) ?: return@detectTapGestures
                        roi = roi.copy(
                            cx = ((off.x - rect[0]) / rect[2]).coerceIn(0f, 1f),
                            cy = ((off.y - rect[1]) / rect[3]).coerceIn(0f, 1f)
                        )
                    }
                }
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val rect = fitRect(size, frameW, frameH) ?: return@Canvas
                val side = roi.size * minOf(rect[2], rect[3])
                val left = rect[0] + roi.cx * rect[2] - side / 2
                val top = rect[1] + roi.cy * rect[3] - side / 2
                drawRect(FRAME, Offset(left, top), Size(side, side), style = Stroke(6f))
                drawContext.canvas.nativeCanvas.drawText("검사 영역", left + 6f, top - 14f, labelPaint)
            }
            if (sweeping) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(12.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xE6000000))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("프리즘을 천천히 돌리세요", fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, color = Color.White)
                        Text("$sweepLeft 초", fontSize = 30.sp, fontWeight = FontWeight.Bold,
                            color = FRAME, fontFamily = FontFamily.Monospace)
                        Text("최고 %.1f".format(sweepMax), fontSize = 13.sp,
                            color = DIM, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            if (showHelp) HelpOverlay { showHelp = false }
        }

        // ─────────── 판정 ───────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(PANEL)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            VerdictCard(verdict, stats.stainIndex, baseline)

            val warning = when {
                tooDark -> "화면이 너무 어둡습니다. 조명을 켜세요"
                glare -> "너무 밝아 하얗게 뭉개집니다. 각도를 조정하세요"
                blurry -> "초점이 흐립니다. 거리를 조정하세요"
                else -> null
            }
            if (warning != null) Text("⚠ $warning", fontSize = 12.sp, color = WARN)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (settings.locked) "촬영 조건 고정됨" else "자동 노출 — 측정 전에 고정하세요",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (settings.locked) OK else WARN
                    )
                    Text(
                        "ISO ${applied.iso ?: "-"} · " +
                            (applied.exposureNs?.let { "%.1f ms".format(it / 1e6) } ?: "-"),
                        fontSize = 11.sp, color = DIM, fontFamily = FontFamily.Monospace
                    )
                }
                if (settings.locked) {
                    OutlinedButton(
                        onClick = { settings = settings.copy(locked = false); controller.updateManual(settings) },
                        modifier = Modifier.height(42.dp)
                    ) { Text("자동", fontSize = 13.sp) }
                } else {
                    Button(
                        onClick = { settings = controller.currentAsManual(settings); controller.updateManual(settings) },
                        modifier = Modifier.height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OK, contentColor = Color.Black)
                    ) { Text("현재 상태로 고정", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }

            sweepResult?.let { r ->
                val v = scratchBase.judge(r)
                val c = when (v) {
                    Verdict.PASS -> OK
                    Verdict.RECHECK -> WARN
                    Verdict.FAIL -> BAD
                    Verdict.NOT_READY -> IDLE
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(CARD).padding(12.dp)
                ) {
                    Text("스크래치 검사 결과 — ${v.label}", fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, color = c)
                    Text(
                        if (scratchBase.ready) "최고 점수 %.1f (합격 %.1f 미만)".format(r, scratchBase.passLimit)
                        else "최고 점수 %.1f · 설정에서 양품 스윕을 %d회 이상 등록하세요"
                            .format(r, Baseline.MIN_SAMPLES),
                        fontSize = 12.sp, color = DIM, fontFamily = FontFamily.Monospace
                    )
                    if (sweepMotion < MIN_SWEEP_MOTION) {
                        Text("⚠ 각도 변화가 적습니다. 더 크게 돌려 다시 재세요",
                            fontSize = 12.sp, color = WARN)
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val b = scratchBase.plus(r)
                            scratchBase = b
                            Baseline.save(context, Baseline.KIND_SCRATCH, b)
                            msg = "스크래치 기준 · 양품 ${b.n}회 등록됨"
                            sweepResult = null
                        }, modifier = Modifier.weight(1f)) { Text("양품으로 등록", fontSize = 13.sp) }
                        OutlinedButton(onClick = { sweepResult = null },
                            modifier = Modifier.weight(1f)) { Text("닫기", fontSize = 13.sp) }
                    }
                }
            }

            Button(
                onClick = { sweeping = true },
                enabled = !sweeping,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E3A44), contentColor = Color.White
                )
            ) {
                Text(
                    if (sweeping) "검사 중… $sweepLeft 초"
                    else "스크래치 검사 (${SWEEP_SECONDS}초 · 손으로 돌리기)",
                    fontSize = 15.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { roi = roi.copy(size = (roi.size - 0.04f).coerceAtLeast(0.06f)) },
                    modifier = Modifier.weight(1f).height(44.dp)
                ) { Text("영역 작게") }
                OutlinedButton(
                    onClick = { roi = roi.copy(size = (roi.size + 0.04f).coerceAtMost(0.85f)) },
                    modifier = Modifier.weight(1f).height(44.dp)
                ) { Text("영역 크게") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pendingSave = true },
                    modifier = Modifier.weight(1f).height(50.dp)
                ) { Text("측정 저장", fontSize = 16.sp) }
                OutlinedButton(
                    onClick = { showSettings = !showSettings },
                    modifier = Modifier.height(50.dp)
                ) { Text(if (showSettings) "닫기" else "설정") }
                OutlinedButton(
                    onClick = { showHelp = true },
                    modifier = Modifier.height(50.dp)
                ) { Text("도움말") }
            }

            if (msg.isNotEmpty()) {
                Text(msg, fontSize = 11.sp, color = DIM, fontFamily = FontFamily.Monospace)
            }
        }

        // ─────────── 설정 ───────────
        if (showSettings) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .background(Color(0xFF0F1417))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Text("양품 기준 등록", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "양품이 확실한 프리즘을 검사 영역에 놓고 「양품으로 등록」을 누르세요. " +
                        "여러 개를 등록할수록 기준이 정확해집니다. ${Baseline.MIN_SAMPLES}개부터 판정이 시작됩니다.",
                    fontSize = 12.sp, color = DIM
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { pendingRegister = true },
                        modifier = Modifier.weight(1f).height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OK, contentColor = Color.Black)
                    ) { Text("양품으로 등록", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = {
                            baseline = baseline.cleared()
                            Baseline.save(context, Baseline.KIND_STAIN, baseline)
                            scratchBase = scratchBase.cleared()
                            Baseline.save(context, Baseline.KIND_SCRATCH, scratchBase)
                            msg = "기준 초기화됨 (얼룩·스크래치)"
                        },
                        modifier = Modifier.height(46.dp)
                    ) { Text("초기화") }
                }
                Text(
                    if (baseline.n == 0) "등록된 양품 없음"
                    else "등록 %d개 · 평균 %.1f · 편차 %.1f · 합격 상한 %.1f · 불량 하한 %.1f"
                        .format(baseline.n, baseline.mean, baseline.sd, baseline.passLimit, baseline.failLimit),
                    fontSize = 11.sp, color = DIM, fontFamily = FontFamily.Monospace
                )
                Text(
                    if (scratchBase.n == 0) "스크래치 기준 없음 — 양품으로 스윕 검사 후 등록"
                    else "스크래치 · 등록 %d회 · 평균 %.1f · 합격 상한 %.1f"
                        .format(scratchBase.n, scratchBase.mean, scratchBase.passLimit),
                    fontSize = 11.sp, color = DIM, fontFamily = FontFamily.Monospace
                )

                HorizontalDivider(color = Color(0xFF262E33))

                Text("현재 측정값", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    ("얼룩 지수 %.1f · 정상면 %d · 최대밝기 %d · 넓이 %.2f%%\n" +
                        "스크래치 %.1f · 신장도 %.1f · 선명도 %.1f · 포화 %.2f%%")
                        .format(stats.stainIndex, stats.median, stats.max, stats.brightArea * 100,
                            stats.scratchScore, stats.elongation, stats.focus, stats.satRatio * 100),
                    fontSize = 11.sp, color = DIM, fontFamily = FontFamily.Monospace
                )

                HorizontalDivider(color = Color(0xFF262E33))

                Text("카메라 수동 조정 — 고정 상태에서만 반영", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val isoLo = caps.isoRange?.lower ?: 50
                val isoHi = caps.isoRange?.upper ?: 800
                Sld("ISO", "${settings.iso}", settings.iso.toFloat(), isoLo.toFloat()..isoHi.toFloat()) {
                    settings = settings.copy(iso = it.roundToInt()); controller.updateManual(settings)
                }
                val expLo = (caps.exposureRange?.lower ?: 100_000L).toFloat()
                val expHi = minOf(caps.exposureRange?.upper ?: 100_000_000L, 100_000_000L).toFloat()
                Sld("노출", "%.1f ms".format(settings.exposureNs / 1e6),
                    settings.exposureNs.toFloat(), expLo..expHi) {
                    settings = settings.copy(exposureNs = it.toLong()); controller.updateManual(settings)
                }
                val focusHi = if (caps.minFocusDistance > 0f) caps.minFocusDistance else 10f
                Sld("초점 거리",
                    if (settings.focusDiopter > 0f) "%.0f mm".format(1000f / settings.focusDiopter) else "무한대",
                    settings.focusDiopter, 0f..focusHi) {
                    settings = settings.copy(focusDiopter = it); controller.updateManual(settings)
                }

                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("메모 (샘플 번호 등)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "카메라 ${caps.cameraId} · ${caps.hardwareLevelName}" +
                        (caps.minFocusMm?.let { " · 최소 초점거리 %.0f mm".format(it) } ?: "") +
                        "\n이미지 Pictures/PrismScope · 로그 Documents/PrismScope\n앱 버전 $appVersion",
                    fontSize = 11.sp, color = Color(0xFF67737A), fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/* ───────────── 부품 ───────────── */

@Composable
private fun VerdictCard(v: Verdict, index: Double, b: Baseline) {
    val color = when (v) {
        Verdict.PASS -> OK
        Verdict.RECHECK -> WARN
        Verdict.FAIL -> BAD
        Verdict.NOT_READY -> IDLE
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CARD)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when (v) {
                    Verdict.PASS -> "OK"
                    Verdict.RECHECK -> "?"
                    Verdict.FAIL -> "NG"
                    Verdict.NOT_READY -> "–"
                },
                color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(v.label, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
            Text(
                if (b.ready) "얼룩 지수 %.1f  (합격 %.1f 미만)".format(index, b.passLimit)
                else "얼룩 지수 %.1f · 설정에서 양품을 %d개 이상 등록하세요"
                    .format(index, Baseline.MIN_SAMPLES),
                fontSize = 12.sp, color = DIM, fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun HelpOverlay(onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xF0000000)).padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("사용 순서", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            listOf(
                "1. 프리즘을 지그에 놓고 조명을 켠다",
                "2. 노란 사각형이 프리즘을 덮도록 화면을 눌러 옮기고 크기를 맞춘다",
                "3. 화면이 잘 보이면 「현재 상태로 고정」을 누른다 — 이후 밝기가 변하지 않는다",
                "4. 설정 → 양품 프리즘을 놓고 「양품으로 등록」을 5개 이상 반복한다",
                "5. 이제 프리즘을 올릴 때마다 양품 / 재검 / 불량이 화면에 뜬다",
                "6. 기록이 필요하면 「측정 저장」을 누른다",
                "",
                "스크래치는 각도가 맞아야만 번쩍인다. 「스크래치 검사」를 누르고",
                "6초 동안 프리즘을 손으로 천천히 돌리면, 그동안의 최고 점수로 판정한다.",
            ).forEach { Text(it, fontSize = 14.sp, color = Color.White, lineHeight = 20.sp) }
            Text(
                "얼룩 지수 = 검사 영역에서 가장 밝은 부분이 정상면보다 얼마나 밝은가.\n" +
                    "얼룩은 빛을 산란시켜 밝게 뜨므로 지수가 클수록 얼룩이 심하다.",
                fontSize = 12.sp, color = DIM, lineHeight = 18.sp
            )
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("닫기")
            }
        }
    }
}

@Composable
private fun Sld(
    label: String, value: String, current: Float,
    range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), fontSize = 13.sp)
            Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFB0BEC5))
        }
        Slider(
            value = current.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange, valueRange = range
        )
    }
}

/* ───────────── 좌표 ───────────── */

/** FIT_CENTER 로 표시된 프레임의 화면상 위치. [left, top, width, height]. */
private fun fitRect(box: Size, fw: Int, fh: Int): FloatArray? {
    if (box.width <= 0f || box.height <= 0f || fw <= 0 || fh <= 0) return null
    val frameAspect = if (box.width < box.height) fh.toFloat() / fw else fw.toFloat() / fh
    val boxAspect = box.width / box.height
    return if (boxAspect > frameAspect) {
        val w = box.height * frameAspect
        floatArrayOf((box.width - w) / 2f, 0f, w, box.height)
    } else {
        val h = box.width / frameAspect
        floatArrayOf(0f, (box.height - h) / 2f, box.width, h)
    }
}

private fun buildCsvRow(
    note: String, s: RoiStats, b: Baseline, sb: Baseline, cam: AppliedCamera, m: ManualSettings,
    roi: Roi, w: Int, h: Int, file: String, appVersion: String,
): String {
    fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
    return listOf(
        q(Store.timestamp()), q(note), q(b.judge(s.stainIndex).label),
        "%.3f".format(s.stainIndex), s.median, s.p99, s.max, "%.3f".format(s.mean),
        "%.5f".format(s.brightArea), "%.5f".format(s.satRatio), "%.3f".format(s.focus),
        "%.3f".format(s.scratchScore), "%.3f".format(s.elongation),
        b.n, "%.3f".format(b.mean), "%.3f".format(b.sd),
        "%.3f".format(b.passLimit), "%.3f".format(b.failLimit),
        sb.n, "%.3f".format(sb.passLimit),
        cam.iso ?: "", cam.exposureNs ?: "", cam.focusDiopter ?: "", if (m.locked) 1 else 0,
        "%.4f".format(roi.cx), "%.4f".format(roi.cy), "%.4f".format(roi.size),
        w, h, q(file), q(appVersion)
    ).joinToString(",")
}
