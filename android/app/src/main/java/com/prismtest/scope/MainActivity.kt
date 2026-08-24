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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 1초에 필요한 최소 밝기 변화량. 검사 시간을 곱해 "충분히 돌렸는가"를 판정한다.
 * 시간을 늘려놓고 가만히 들고 있으면 커버리지가 늘지 않아야 하므로 시간에 비례시킨다.
 */
private const val MOTION_PER_SEC = 10.0

/**
 * 암시야가 성립했는지 보는 기준 — 검사 영역의 중앙값(정상면 밝기).
 *
 * 이 앱의 모든 점수는 "어두운 배경 위에 결함만 밝게 뜬다"를 전제로 한다.
 * 전제가 깨진 화면에서 나온 숫자는 프리즘이 아니라 방을 잰 값이다.
 * 그런 값이 기준선에 들어가면 이후 판정이 통째로 무의미해지므로 등록을 막는다.
 */
private const val DARK_OK = 20
private const val DARK_LIMIT = 45
private const val SAT_LIMIT = 0.02

private val TYPES = DefectType.values()

private val OK = Color(0xFF3DBE63)
private val WARN = Color(0xFFE8A93B)
private val BAD = Color(0xFFE04B3F)
private val IDLE = Color(0xFF7C8A91)
private val FRAME = Color(0xFFFFC107)
private val BLUE = Color(0xFF2E6BE6)
private val PANEL = Color(0xFF14181B)
private val CARD = Color(0xFF1C2227)
private val DIM = Color(0xFF8B979D)

private enum class Screen { INSPECT, ENROLL }

private fun colorOf(v: Verdict) = when (v) {
    Verdict.PASS -> OK
    Verdict.RECHECK -> WARN
    Verdict.FAIL -> BAD
    Verdict.NOT_READY -> IDLE
}

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

    var roi by remember { mutableStateOf(Roi(0.5f, 0.5f, 0.45f)) }
    var live by remember { mutableStateOf(RoiStats.EMPTY) }
    var frameW by remember { mutableIntStateOf(0) }
    var frameH by remember { mutableIntStateOf(0) }
    var tracked by remember { mutableStateOf(false) }

    var screen by remember { mutableStateOf(Screen.INSPECT) }
    var inspectSec by remember { mutableIntStateOf(Prefs.inspectSec(context)) }
    var enrollSec by remember { mutableIntStateOf(Prefs.enrollSec(context)) }
    var autoTrack by remember { mutableStateOf(Prefs.autoTrack(context)) }
    /** 0 = 양품, 1..4 = 그 유형의 불량. */
    var pick by remember { mutableIntStateOf(0) }

    // 유형별 판정 기준. 등록한 양품·불량 샘플에서 계산된다.
    var models by remember {
        mutableStateOf(TYPES.associateWith { DefectModel.load(context, it) })
    }

    // 검사 = 손으로 돌리는 동안의 유형별 최댓값 수집.
    var sweeping by remember { mutableStateOf(false) }
    var sweepLeft by remember { mutableIntStateOf(0) }
    var sweepDur by remember { mutableIntStateOf(8) }
    var sweepMode by remember { mutableStateOf(Screen.INSPECT) }
    var peaks by remember { mutableStateOf(List(TYPES.size) { 0.0 }) }
    var result by remember { mutableStateOf<List<Double>?>(null) }
    var motion by remember { mutableStateOf(0.0) }
    var lastMean by remember { mutableStateOf(-1.0) }
    // 검사 동안 가장 나빴던 화면 상태. 한 프레임이라도 방이 찍혔으면 그 결과는 못 쓴다.
    var sweepMedian by remember { mutableIntStateOf(0) }
    var sweepSat by remember { mutableStateOf(0.0) }

    var note by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var pendingSave by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    val roiRef = rememberUpdatedState(roi)
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
    }

    val previewView = remember {
        // FIT 으로 두면 세로 영상이 가로로 납작한 프리뷰 칸에 맞춰 축소되어
        // 프리즘이 손톱만 하게 보인다. 가운데를 잘라 채우는 편이 조준에 낫다.
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    LaunchedEffect(Unit) {
        controller.bind(
            lifecycleOwner, previewView, settings,
            onFrame = { image ->
                try {
                    // 화면에 서는 방향 기준의 크기. 오버레이 좌표는 이걸로 맞춘다.
                    val rot = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
                    val swap = rot == 90 || rot == 270
                    frameW = if (swap) image.height else image.width
                    frameH = if (swap) image.width else image.height

                    // 영역을 먼저 물체에 맞춘 뒤 그 영역을 잰다. 순서가 반대면 한 프레임 늦는다.
                    var r = roiRef.value
                    if (autoTrack) {
                        val moved = Stats.locate(image, r)
                        tracked = moved != null
                        if (moved != null) {
                            r = moved
                            roi = moved
                        }
                    } else {
                        tracked = false
                    }

                    val s = Stats.analyze(image, r)
                    live = s
                    applied = controller.applied

                    if (sweeping) {
                        // 유형마다 최댓값을 따로 잡는다. 스크래치가 번쩍이는 각도와
                        // 얼룩이 가장 잘 보이는 각도는 달라서 한 프레임으로는 못 잡는다.
                        val cur = peaks
                        var changed = false
                        val next = ArrayList<Double>(TYPES.size)
                        for (i in TYPES.indices) {
                            val v = TYPES[i].score(s)
                            if (v > cur[i]) {
                                next.add(v)
                                changed = true
                            } else {
                                next.add(cur[i])
                            }
                        }
                        if (changed) peaks = next
                        // 프레임 간 밝기 변화로 "실제로 돌리고 있는지"를 본다.
                        if (lastMean >= 0) motion += abs(s.mean - lastMean)
                        lastMean = s.mean
                        if (s.median > sweepMedian) sweepMedian = s.median
                        if (s.satRatio > sweepSat) sweepSat = s.satRatio
                    }

                    if (pendingSave) {
                        pendingSave = false
                        val file = Store.saveFrame(context, image, "cap")
                        Store.appendCsv(
                            context,
                            buildCsvRow(note, s, result, models, controller.applied, settings,
                                r, image.width, image.height, file ?: "", appVersion)
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

    // 검사 타이머. 프레임 콜백이 아니라 UI 쪽에서 시간을 센다.
    LaunchedEffect(sweeping) {
        if (!sweeping) return@LaunchedEffect
        peaks = List(TYPES.size) { 0.0 }
        motion = 0.0
        lastMean = -1.0
        sweepMedian = 0
        sweepSat = 0.0
        result = null
        msg = ""
        for (i in sweepDur downTo 1) {
            sweepLeft = i
            delay(1000)
        }
        sweepLeft = 0
        sweeping = false
        val final = peaks
        result = final

        if (sweepMode == Screen.ENROLL) {
            // 등록은 되돌릴 수 없다. 못 믿을 측정이면 조용히 넣지 말고 이유를 말한다.
            msg = when {
                sweepMedian > DARK_LIMIT -> "등록 취소 — 배경이 밝습니다 (정상면 $sweepMedian)"
                sweepSat > SAT_LIMIT -> "등록 취소 — 빛이 하얗게 뭉갰습니다"
                motion < sweepDur * MOTION_PER_SEC -> "등록 취소 — 각도 변화가 부족합니다. 더 크게 돌리세요"
                pick == 0 -> {
                    var m = models
                    TYPES.forEachIndexed { i, t ->
                        val nm = m.getValue(t).plusGood(final[i])
                        DefectModel.save(context, t, nm)
                        m = m + (t to nm)
                    }
                    models = m
                    "양품 등록됨 — 누적 ${m.getValue(TYPES[0]).good.size}개"
                }
                else -> {
                    val t = TYPES[pick - 1]
                    val nm = models.getValue(t).plusBad(final[pick - 1])
                    DefectModel.save(context, t, nm)
                    models = models + (t to nm)
                    "${t.label} 불량 등록됨 — 누적 ${nm.bad.size}개"
                }
            }
        }
    }

    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FFC107")
            textSize = 38f
            isFakeBoldText = true
            isAntiAlias = true
        }
    }

    // 표시할 점수: 검사가 끝났으면 그 결과, 아니면 지금 화면의 값
    val shown: List<Double> = result ?: if (sweeping) peaks else TYPES.map { it.score(live) }
    val verdicts = TYPES.mapIndexed { i, t -> models.getValue(t).judge(shown[i]) }
    val overall = when {
        result == null -> null
        verdicts.any { it == Verdict.FAIL } -> Verdict.FAIL
        verdicts.any { it == Verdict.RECHECK } -> Verdict.RECHECK
        verdicts.any { it == Verdict.PASS } -> Verdict.PASS
        else -> Verdict.NOT_READY
    }

    val badScene: String? = when {
        result == null -> null
        sweepMedian > DARK_LIMIT ->
            "배경이 밝습니다 (정상면 $sweepMedian) — 프리즘이 아니라 주변을 재고 있습니다"
        sweepSat > SAT_LIMIT -> "빛이 하얗게 뭉갰습니다 — 조명 각도를 낮추세요"
        else -> null
    }

    val tooDark = live.median < 3 && live.p99 < 6
    val liveBright = live.median > DARK_LIMIT
    val liveDim = live.median > DARK_OK && !liveBright
    val glare = live.satRatio > SAT_LIMIT
    val coverage = if (sweepDur > 0) (motion / (sweepDur * MOTION_PER_SEC)).coerceIn(0.0, 1.0) else 0.0

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
                val tag = if (autoTrack) {
                    if (tracked) "자동 추적" else "추적 대기 — 프리즘을 비추세요"
                } else "검사 영역"
                drawContext.canvas.nativeCanvas.drawText(tag, left + 6f, top - 14f, labelPaint)
            }

            if (sweeping) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(12.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Color(0xE6000000))
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (sweepMode == Screen.ENROLL) "천천히 여러 각도로 돌리세요"
                            else "프리즘을 천천히 돌리세요",
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White
                        )
                        Text(
                            sweepLeft.toString(), fontSize = 40.sp, fontWeight = FontWeight.Bold,
                            color = FRAME, fontFamily = FontFamily.Monospace
                        )
                        Bar(coverage.toFloat(), Modifier.width(150.dp))
                        Text(
                            "각도 커버리지 ${(coverage * 100).roundToInt()}%",
                            fontSize = 11.sp, color = if (coverage >= 1.0) OK else DIM,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            if (!autoTrack) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RoundBtn("−") { roi = roi.copy(size = (roi.size - 0.05f).coerceAtLeast(0.10f)) }
                    RoundBtn("+") { roi = roi.copy(size = (roi.size + 0.05f).coerceAtMost(0.90f)) }
                }
            }

            if (showHelp) HelpOverlay(inspectSec) { showHelp = false }
        }

        // ─────────── 패널 ───────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(PANEL)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // 촬영 조건 — 두 화면 공통
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (settings.locked) "촬영 조건 고정됨" else "자동 노출 — 검사 전에 고정하세요",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (settings.locked) OK else WARN
                    )
                    // 「배경」은 암실을 만드는 동안의 계기판이다. 이 값이 8 이하로
                    // 내려가야 나머지가 성립하므로, 경고가 없을 때도 늘 보여준다.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "ISO ${applied.iso ?: "-"} · " +
                                (applied.exposureNs?.let { "%.1f ms".format(it / 1e6) } ?: "-") +
                                " · %.1f×".format(settings.zoom) + "  ",
                            fontSize = 11.sp, color = DIM, fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "배경 ${live.median}",
                            fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                live.median <= 8 -> OK
                                live.median <= DARK_OK -> WARN
                                else -> BAD
                            }
                        )
                    }
                }
                if (settings.locked) {
                    OutlinedButton(
                        onClick = {
                            settings = settings.copy(locked = false)
                            controller.updateManual(settings)
                        },
                        modifier = Modifier.height(38.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) { Text("자동", fontSize = 13.sp) }
                } else {
                    Button(
                        onClick = {
                            settings = controller.currentAsManual(settings)
                            controller.updateManual(settings)
                        },
                        modifier = Modifier.height(38.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OK, contentColor = Color.Black
                        )
                    ) { Text("현재 상태로 고정", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }

            val warn = when {
                tooDark -> "화면이 너무 어둡습니다. 조명을 켜세요"
                liveBright -> "배경이 밝습니다 (정상면 ${live.median}) — 암실이 필요합니다"
                glare -> "너무 밝아 하얗게 뭉개집니다. 각도를 조정하세요"
                liveDim -> "배경이 조금 밝습니다 (정상면 ${live.median}) — 8 이하가 목표"
                else -> null
            }
            if (warn != null) {
                Text("⚠ $warn", fontSize = 12.sp, color = if (liveBright) BAD else WARN)
            }

            if (screen == Screen.INSPECT) {
                SecondsRow("검사 시간", Prefs.INSPECT_CHOICES, inspectSec, !sweeping) {
                    inspectSec = it
                    Prefs.setInspectSec(context, it)
                }
                Button(
                    onClick = {
                        sweepDur = inspectSec
                        sweepMode = Screen.INSPECT
                        sweeping = true
                    },
                    enabled = !sweeping,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BLUE, contentColor = Color.White)
                ) {
                    Text(
                        if (sweeping) "검사 중 " + sweepLeft + "초" else "검사 시작 (" + inspectSec + "초)",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                }

                if (badScene != null) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(BAD).padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("측정 불가", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Text(badScene, fontSize = 12.sp, color = Color.Black)
                    }
                } else if (overall != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(colorOf(overall)).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            overall.label, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            color = Color.Black, modifier = Modifier.weight(1f)
                        )
                        if (motion < sweepDur * MOTION_PER_SEC) {
                            Text("각도 변화 부족 — 다시", fontSize = 12.sp, color = Color.Black)
                        }
                    }
                }

                for (row in 0 until (TYPES.size + 1) / 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        for (col in 0 until 2) {
                            val i = row * 2 + col
                            if (i >= TYPES.size) {
                                Spacer(Modifier.weight(1f))
                                continue
                            }
                            TypeCard(
                                type = TYPES[i],
                                score = shown[i],
                                verdict = verdicts[i],
                                model = models.getValue(TYPES[i]),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { screen = Screen.ENROLL; result = null; msg = "" },
                        enabled = !sweeping,
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2A3138), contentColor = Color.White
                        )
                    ) { Text("샘플 등록", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = { pendingSave = true },
                        modifier = Modifier.height(44.dp)
                    ) { Text("저장", fontSize = 14.sp) }
                    OutlinedButton(
                        onClick = { showSettings = !showSettings },
                        modifier = Modifier.height(44.dp)
                    ) { Text(if (showSettings) "닫기" else "설정") }
                    OutlinedButton(
                        onClick = { showHelp = true },
                        modifier = Modifier.height(44.dp)
                    ) { Text("도움말") }
                }
            } else {
                // ─────────── 샘플 등록 ───────────
                Text(
                    "무엇으로 등록합니까",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White
                )
                for (row in 0 until 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (col in 0 until 3) {
                            val i = row * 3 + col
                            if (i > TYPES.size) {
                                Spacer(Modifier.weight(1f))
                                continue
                            }
                            val label = if (i == 0) "양품" else TYPES[i - 1].label
                            PickChip(
                                text = label,
                                selected = pick == i,
                                accent = if (i == 0) OK else BAD,
                                enabled = !sweeping,
                                modifier = Modifier.weight(1f)
                            ) { pick = i }
                        }
                    }
                }

                SecondsRow("등록 시간", Prefs.ENROLL_CHOICES, enrollSec, !sweeping) {
                    enrollSec = it
                    Prefs.setEnrollSec(context, it)
                }

                Button(
                    onClick = {
                        sweepDur = enrollSec
                        sweepMode = Screen.ENROLL
                        sweeping = true
                    },
                    enabled = !sweeping,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (pick == 0) OK else BAD,
                        contentColor = Color.Black
                    )
                ) {
                    val what = if (pick == 0) "양품" else TYPES[pick - 1].label + " 불량"
                    Text(
                        if (sweeping) "등록 중 " + sweepLeft + "초"
                        else what + "으로 등록 (" + enrollSec + "초)",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    TYPES.joinToString("  ") {
                        val m = models.getValue(it)
                        "${it.label.take(2)} ${m.good.size}/${m.bad.size}"
                    } + "   (양품/불량)",
                    fontSize = 11.sp, color = DIM, fontFamily = FontFamily.Monospace
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { screen = Screen.INSPECT; result = null; msg = "" },
                        enabled = !sweeping,
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BLUE, contentColor = Color.White
                        )
                    ) { Text("검사로 돌아가기", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = { showSettings = !showSettings },
                        modifier = Modifier.height(44.dp)
                    ) { Text(if (showSettings) "닫기" else "설정") }
                }
            }

            if (msg.isNotEmpty()) {
                Text(
                    msg, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = if (msg.startsWith("등록 취소")) BAD else DIM
                )
            }
        }

        // ─────────── 설정 ───────────
        if (showSettings) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .background(Color(0xFF0F1417))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("검사 영역 자동 추적", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "배경이 검으면 화면에서 검지 않은 것은 프리즘뿐이다. " +
                                "끄면 사각형을 손으로 맞춘다.",
                            fontSize = 11.sp, color = DIM
                        )
                    }
                    Switch(
                        checked = autoTrack,
                        onCheckedChange = { autoTrack = it; Prefs.setAutoTrack(context, it) }
                    )
                }

                HorizontalDivider(color = Color(0xFF262E33))

                Text("등록된 샘플", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                TYPES.forEach { t ->
                    val m = models.getValue(t)
                    val b = m.band
                    Text(
                        "${t.label} — 양품 ${m.good.size} · 불량 ${m.bad.size} · ${m.state.label}" +
                            (b?.let { "\n   합격 %.1f 미만 · 불량 %.1f 이상".format(it.pass, it.fail) } ?: ""),
                        fontSize = 11.sp, color = DIM, fontFamily = FontFamily.Monospace
                    )
                }
                OutlinedButton(
                    onClick = {
                        TYPES.forEach { DefectModel.save(context, it, DefectModel()) }
                        models = TYPES.associateWith { DefectModel() }
                        result = null
                        msg = "모든 기준 초기화됨"
                    },
                    modifier = Modifier.height(44.dp)
                ) { Text("전체 초기화") }

                HorizontalDivider(color = Color(0xFF262E33))

                Text("현재 화면 측정값", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    ("대비 %.1f · 어둠대비 %.1f · 넓이 %.2f%% · 선형성 %.2f\n" +
                        "점세기 %.1f · 정상면 %d · 선명도 %.1f · 포화 %.2f%%")
                        .format(
                            live.contrast, live.darkContrast, live.brightArea * 100,
                            live.linearity, live.spot, live.median, live.focus, live.satRatio * 100
                        ),
                    fontSize = 11.sp, color = DIM, fontFamily = FontFamily.Monospace
                )

                HorizontalDivider(color = Color(0xFF262E33))

                Text("배율 — 프리즘이 화면을 채울수록 정확해진다", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Sld(
                    "배율", "%.1f×".format(settings.zoom), settings.zoom,
                    (caps.zoomRange?.lower ?: 1f)..(caps.zoomRange?.upper ?: 8f)
                ) {
                    settings = settings.copy(zoom = it)
                    controller.updateManual(settings)
                }

                HorizontalDivider(color = Color(0xFF262E33))

                Text("카메라 수동 조정 — 고정 상태에서만 반영", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val isoLo = caps.isoRange?.lower ?: 50
                val isoHi = caps.isoRange?.upper ?: 800
                Sld("ISO", "${settings.iso}", settings.iso.toFloat(), isoLo.toFloat()..isoHi.toFloat()) {
                    settings = settings.copy(iso = it.roundToInt())
                    controller.updateManual(settings)
                }
                val expLo = (caps.exposureRange?.lower ?: 100_000L).toFloat()
                val expHi = minOf(caps.exposureRange?.upper ?: 100_000_000L, 100_000_000L).toFloat()
                Sld(
                    "노출", "%.1f ms".format(settings.exposureNs / 1e6),
                    settings.exposureNs.toFloat(), expLo..expHi
                ) {
                    settings = settings.copy(exposureNs = it.toLong())
                    controller.updateManual(settings)
                }
                val focusHi = if (caps.minFocusDistance > 0f) caps.minFocusDistance else 10f
                Sld(
                    "초점 거리",
                    if (settings.focusDiopter > 0f) "%.0f mm".format(1000f / settings.focusDiopter) else "무한대",
                    settings.focusDiopter, 0f..focusHi
                ) {
                    settings = settings.copy(focusDiopter = it)
                    controller.updateManual(settings)
                }

                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("메모 (샘플 번호 등)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "카메라 ${caps.cameraId} · ${caps.hardwareLevelName}" +
                        (caps.minFocusMm?.let { " · 최소 초점거리 %.0f mm".format(it) } ?: "") +
                        "\n분석 해상도 ${caps.analysisWidth}×${caps.analysisHeight}" +
                        " · 검사 영역 ${(roi.size * caps.analysisHeight).roundToInt()} px" +
                        "\n이미지 Pictures/PrismScope · 로그 Documents/PrismScope\n앱 버전 $appVersion",
                    fontSize = 11.sp, color = Color(0xFF67737A), fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/* ───────────── 부품 ───────────── */

@Composable
private fun TypeCard(
    type: DefectType,
    score: Double,
    verdict: Verdict,
    model: DefectModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clip(RoundedCornerShape(9.dp)).background(CARD)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colorOf(verdict)))
            Spacer(Modifier.width(7.dp))
            Text(
                type.label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = Color.White, modifier = Modifier.weight(1f)
            )
            Text(
                "%.1f".format(score), fontSize = 15.sp,
                fontFamily = FontFamily.Monospace, color = colorOf(verdict)
            )
        }
        Text(
            if (model.ready) "${verdict.label} · 양 ${model.good.size} / 불 ${model.bad.size}"
            else "양품 ${model.good.size}/${DefectModel.MIN_GOOD} — 등록 필요",
            fontSize = 10.sp, color = DIM, fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun PickChip(
    text: String,
    selected: Boolean,
    accent: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) accent else CARD)
            .border(1.dp, if (selected) accent else Color(0xFF39424A), RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.Black else Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SecondsRow(
    label: String,
    choices: List<Int>,
    current: Int,
    enabled: Boolean,
    onPick: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 11.sp, color = DIM)
        choices.forEach { s ->
            PickChip(
                text = s.toString() + "초",
                selected = s == current,
                accent = FRAME,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) { onPick(s) }
        }
    }
}

@Composable
private fun Bar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF3A444C))
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                .background(if (fraction >= 1f) OK else FRAME)
        )
    }
}

@Composable
private fun RoundBtn(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color(0xB3000000)),
        contentAlignment = Alignment.Center
    ) {
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxSize()
        ) { Text(text, fontSize = 18.sp, color = Color.White) }
    }
}

@Composable
private fun HelpOverlay(inspectSec: Int, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xF0000000)).padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("사용 순서", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            listOf(
                "1. 폰을 암실 거치대에 고정하고 조명을 켠다",
                "2. 프리즘을 손에 들면 노란 사각형이 알아서 따라붙는다",
                "3. 「현재 상태로 고정」 — 이후 밝기가 변하지 않는다",
                "4. 「샘플 등록」에서 양품을 " + DefectModel.MIN_GOOD + "개 등록한다",
                "5. 유형별 불량도 " + DefectModel.MIN_BAD + "개씩 등록하면 경계가 정확해진다",
                "6. 「검사로 돌아가기」 → 「검사 시작 (" + inspectSec + "초)」",
                "",
                "등록과 검사 모두 그 시간 동안 프리즘을 천천히 돌린다.",
                "돌리는 동안의 유형별 최고점으로 판정한다.",
            ).forEach { Text(it, fontSize = 14.sp, color = Color.White, lineHeight = 20.sp) }
            Text(
                "왜 돌리는가 — 결함은 각도가 맞을 때만 빛난다. 스크래치는 특히 심해서 " +
                    "한 각도로 고정해 찍으면 그냥 놓친다. 검사원이 손으로 기울여 보는 것과 같은 이유다.",
                fontSize = 12.sp, color = DIM, lineHeight = 18.sp
            )
            Text(
                "「양품」으로 등록하면 네 유형 모두에 양품 표본이 들어간다. " +
                    "특정 유형을 고르면 그 유형의 불량 표본으로만 들어간다.",
                fontSize = 12.sp, color = DIM, lineHeight = 18.sp
            )
            Text(
                "「양품·불량이 겹침」이 뜨면 — 그 유형은 지금 조명으로 구분되지 않는다는 뜻이다. " +
                    "샘플을 더 넣어도 안 갈리면 알고리즘이 아니라 조명을 바꿔야 한다.",
                fontSize = 12.sp, color = WARN, lineHeight = 18.sp
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

/**
 * FILL_CENTER 로 표시된 프레임의 화면상 위치. [left, top, width, height].
 *
 * 상자를 **덮도록** 확대하므로 결과가 상자 밖으로 넘칠 수 있고, 그 값이 맞다 —
 * 오버레이는 Canvas 가 알아서 잘라 그린다.
 *
 * 화면비를 상자 모양으로 추측하면 안 된다. 프리뷰 상자는 가로로 넓은데 영상은
 * 세로로 서 있는 경우가 실제로 나오고, 그때 사각형이 측정 영역보다 크게 그려진다.
 * 회전을 반영한 **표시 해상도**를 그대로 받는다.
 */
private fun fitRect(box: Size, dispW: Int, dispH: Int): FloatArray? {
    if (box.width <= 0f || box.height <= 0f || dispW <= 0 || dispH <= 0) return null
    val scale = maxOf(box.width / dispW, box.height / dispH)
    val w = dispW * scale
    val h = dispH * scale
    return floatArrayOf((box.width - w) / 2f, (box.height - h) / 2f, w, h)
}

private fun buildCsvRow(
    note: String, s: RoiStats, result: List<Double>?, models: Map<DefectType, DefectModel>,
    cam: AppliedCamera, m: ManualSettings, roi: Roi, w: Int, h: Int,
    file: String, appVersion: String,
): String {
    fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
    val scores = result ?: TYPES.map { it.score(s) }
    val verdicts = TYPES.mapIndexed { i, t -> models.getValue(t).judge(scores[i]) }
    val overall = when {
        verdicts.any { it == Verdict.FAIL } -> Verdict.FAIL
        verdicts.any { it == Verdict.RECHECK } -> Verdict.RECHECK
        verdicts.any { it == Verdict.PASS } -> Verdict.PASS
        else -> Verdict.NOT_READY
    }
    val cells = ArrayList<Any>()
    cells.add(q(Store.timestamp()))
    cells.add(q(note))
    cells.add(q(if (result != null) "sweep" else "live"))
    cells.add(q(overall.label))
    TYPES.forEachIndexed { i, t ->
        val mm = models.getValue(t)
        cells.add("%.3f".format(scores[i]))
        cells.add(q(verdicts[i].label))
        cells.add(mm.good.size)
        cells.add(mm.bad.size)
        cells.add(mm.band?.let { "%.3f".format(it.pass) } ?: "")
        cells.add(mm.band?.let { "%.3f".format(it.fail) } ?: "")
    }
    cells.add(s.median)
    cells.add(s.p99)
    cells.add(s.max)
    cells.add("%.3f".format(s.contrast))
    cells.add("%.3f".format(s.darkContrast))
    cells.add("%.5f".format(s.brightArea))
    cells.add("%.3f".format(s.linearity))
    cells.add("%.3f".format(s.spot))
    cells.add("%.5f".format(s.satRatio))
    cells.add("%.3f".format(s.focus))
    cells.add(cam.iso ?: "")
    cells.add(cam.exposureNs ?: "")
    cells.add(cam.focusDiopter ?: "")
    cells.add(if (m.locked) 1 else 0)
    cells.add("%.2f".format(m.zoom))
    cells.add("%.4f".format(roi.cx))
    cells.add("%.4f".format(roi.cy))
    cells.add("%.4f".format(roi.size))
    cells.add(w)
    cells.add(h)
    cells.add(q(file))
    cells.add(q(appVersion))
    return cells.joinToString(",")
}
