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

/** 검사 시간. 손으로 한 바퀴 돌려보기에 충분하면서 지루하지 않은 길이. */
private const val SWEEP_SECONDS = 8

/** 검사 중 최소 밝기 변화 총량. 이보다 작으면 각도를 거의 안 바꾼 것이다. */
private const val MIN_SWEEP_MOTION = 80.0

private val TYPES = DefectType.values()

private val OK = Color(0xFF3DBE63)
private val WARN = Color(0xFFE8A93B)
private val BAD = Color(0xFFE04B3F)
private val IDLE = Color(0xFF7C8A91)
private val FRAME = Color(0xFFFFC107)
private val PANEL = Color(0xFF14181B)
private val CARD = Color(0xFF1C2227)
private val DIM = Color(0xFF8B979D)

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

    // 유형별 판정 기준. 등록한 양품·불량 샘플에서 계산된다.
    var models by remember {
        mutableStateOf(TYPES.associateWith { DefectModel.load(context, it) })
    }

    // 검사 = 손으로 돌리는 동안의 유형별 최댓값 수집.
    var sweeping by remember { mutableStateOf(false) }
    var sweepLeft by remember { mutableIntStateOf(0) }
    var peaks by remember { mutableStateOf(List(TYPES.size) { 0.0 }) }
    var result by remember { mutableStateOf<List<Double>?>(null) }
    var motion by remember { mutableStateOf(0.0) }
    var lastMean by remember { mutableStateOf(-1.0) }

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
                    }

                    if (pendingSave) {
                        pendingSave = false
                        val file = Store.saveFrame(context, image, "cap")
                        Store.appendCsv(
                            context,
                            buildCsvRow(note, s, result, models, controller.applied, settings,
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

    // 검사 타이머. 프레임 콜백이 아니라 UI 쪽에서 시간을 센다.
    LaunchedEffect(sweeping) {
        if (!sweeping) return@LaunchedEffect
        peaks = List(TYPES.size) { 0.0 }
        motion = 0.0
        lastMean = -1.0
        result = null
        msg = ""
        for (i in SWEEP_SECONDS downTo 1) {
            sweepLeft = i
            delay(1000)
        }
        sweepLeft = 0
        sweeping = false
        result = peaks
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

    val tooDark = live.median < 3 && live.p99 < 6
    val glare = live.satRatio > 0.01

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
                        .clip(RoundedCornerShape(12.dp)).background(Color(0xE6000000))
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "프리즘을 천천히 돌리세요", fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, color = Color.White
                        )
                        Text(
                            sweepLeft.toString(), fontSize = 40.sp, fontWeight = FontWeight.Bold,
                            color = FRAME, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 영역 크기 — 화면 위에 얹어 아래 패널 자리를 먹지 않는다
            Row(
                Modifier.align(Alignment.BottomEnd).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RoundBtn("−") { roi = roi.copy(size = (roi.size - 0.05f).coerceAtLeast(0.10f)) }
                RoundBtn("+") { roi = roi.copy(size = (roi.size + 0.05f).coerceAtMost(0.90f)) }
            }

            if (showHelp) HelpOverlay { showHelp = false }
        }

        // ─────────── 조작 + 판정 ───────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(PANEL)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // 촬영 조건
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (settings.locked) "촬영 조건 고정됨" else "자동 노출 — 검사 전에 고정하세요",
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

            // 검사 시작
            Button(
                onClick = { sweeping = true },
                enabled = !sweeping,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E6BE6), contentColor = Color.White
                )
            ) {
                Text(
                    if (sweeping) "검사 중 " + sweepLeft + "초" else "검사 시작",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
            }

            // 종합 판정
            if (overall != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(colorOf(overall)).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        overall.label, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = Color.Black, modifier = Modifier.weight(1f)
                    )
                    if (motion < MIN_SWEEP_MOTION) {
                        Text("각도 변화 부족 — 다시", fontSize = 12.sp, color = Color.Black)
                    }
                }
            }

            val warn = when {
                tooDark -> "화면이 너무 어둡습니다. 조명을 켜세요"
                glare -> "너무 밝아 하얗게 뭉개집니다. 각도를 조정하세요"
                else -> null
            }
            if (warn != null) Text("⚠ $warn", fontSize = 12.sp, color = WARN)

            // 유형별 결과 + 등록
            TYPES.forEachIndexed { i, type ->
                TypeRow(
                    type = type,
                    score = shown[i],
                    verdict = verdicts[i],
                    model = models.getValue(type),
                    armed = result != null && !sweeping,
                    onGood = {
                        val m = models.getValue(type).plusGood(shown[i])
                        DefectModel.save(context, type, m)
                        models = models + (type to m)
                        msg = "${type.label} 양품 ${m.good.size}개 등록"
                    },
                    onBad = {
                        val m = models.getValue(type).plusBad(shown[i])
                        DefectModel.save(context, type, m)
                        models = models + (type to m)
                        msg = "${type.label} 불량 ${m.bad.size}개 등록"
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pendingSave = true },
                    modifier = Modifier.weight(1f).height(44.dp)
                ) { Text("측정 저장", fontSize = 14.sp) }
                OutlinedButton(
                    onClick = { showSettings = !showSettings },
                    modifier = Modifier.height(44.dp)
                ) { Text(if (showSettings) "닫기" else "설정") }
                OutlinedButton(
                    onClick = { showHelp = true },
                    modifier = Modifier.height(44.dp)
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
                    .heightIn(max = 300.dp)
                    .background(Color(0xFF0F1417))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
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
private fun TypeRow(
    type: DefectType,
    score: Double,
    verdict: Verdict,
    model: DefectModel,
    armed: Boolean,
    onGood: () -> Unit,
    onBad: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(CARD)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(colorOf(verdict)))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    type.label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, modifier = Modifier.weight(1f)
                )
                Text(
                    "%.1f".format(score), fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace, color = colorOf(verdict)
                )
            }
            Text(
                if (model.ready)
                    "${verdict.label} · 양품 ${model.good.size}/불량 ${model.bad.size} · ${model.state.label}"
                else
                    "양품 ${model.good.size}/${DefectModel.MIN_GOOD} 등록됨 — ${type.hint}",
                fontSize = 10.sp, color = DIM
            )
        }
        Spacer(Modifier.width(8.dp))
        SmallBtn("양품", OK, armed, onGood)
        Spacer(Modifier.width(5.dp))
        SmallBtn("불량", BAD, armed, onBad)
    }
}

@Composable
private fun SmallBtn(text: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(34.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
    ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
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
private fun HelpOverlay(onClose: () -> Unit) {
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
                "2. 프리즘을 손에 들고 노란 사각형 안에 들어오게 한다",
                "3. 「현재 상태로 고정」 — 이후 밝기가 변하지 않는다",
                "4. 「검사 시작」을 누르고 " + SWEEP_SECONDS + "초 동안 프리즘을 천천히 돌린다",
                "5. 유형별 결과가 뜬다. 그 개체가 무엇인지 알고 있다면",
                "   해당 유형 옆의 「양품」 또는 「불량」을 눌러 등록한다",
                "",
                "양품 " + DefectModel.MIN_GOOD + "개부터 판정이 시작되고,",
                "불량 " + DefectModel.MIN_BAD + "개를 더 넣으면 경계가 두 분포 사이로 옮겨간다.",
            ).forEach { Text(it, fontSize = 14.sp, color = Color.White, lineHeight = 20.sp) }
            Text(
                "왜 돌리면서 보는가 — 결함은 각도가 맞을 때만 빛난다. 스크래치는 특히 심해서 " +
                    "한 각도로 고정해 찍으면 그냥 놓친다. 검사원이 손으로 기울여 보는 것과 같은 이유다. " +
                    "앱은 그 " + SWEEP_SECONDS + "초 동안의 유형별 최고점으로 판정한다.",
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
