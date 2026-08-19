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
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** 측정 모드. v0 은 판정하지 않는다 — 재는 것까지가 역할이다. */
enum class Mode(val label: String) {
    CONTRAST("결함 대비"),
    BACKGROUND("배경 어둠"),
}

/** 배경 합격 기준. 8bit 평균 8 이하 = 포화값의 약 3%. */
const val BG_PASS_LEVEL = 8.0

private val OK = Color(0xFF5FD37A)
private val WARN = Color(0xFFF2C14E)
private val BAD = Color(0xFFEF6A5E)
private val A_COLOR = Color(0xFFFFC107)
private val B_COLOR = Color(0xFF4FC3F7)
private val PANEL = Color(0xFF15191C)
private val CARD = Color(0xFF1B2126)
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
        // 시스템 바 아래로 내용이 깔리지 않게 한다. targetSdk 35 는 기본이 edge-to-edge 라
        // 이 패딩이 없으면 상단이 상태바에, 하단이 내비게이션 바에 가린다.
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (granted) Scope(controller)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("카메라 권한을 허용해 주세요", color = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Scope(controller: CameraController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var mode by remember { mutableStateOf(Mode.CONTRAST) }
    var settings by remember { mutableStateOf(ManualSettings()) }
    var caps by remember { mutableStateOf(CameraCaps()) }
    var applied by remember { mutableStateOf(AppliedCamera()) }

    var roiA by remember { mutableStateOf(Roi(0.38f, 0.45f, 0.16f)) }
    var roiB by remember { mutableStateOf(Roi(0.68f, 0.45f, 0.16f)) }
    var editingA by remember { mutableStateOf(true) }

    var statsA by remember { mutableStateOf(RoiStats.EMPTY) }
    var statsB by remember { mutableStateOf(RoiStats.EMPTY) }
    var frameW by remember { mutableIntStateOf(0) }
    var frameH by remember { mutableIntStateOf(0) }

    var note by remember { mutableStateOf("") }
    var lightMode by remember { mutableStateOf("darkfield") }
    var saveMsg by remember { mutableStateOf("") }
    var pendingSave by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(true) }

    val roiARef = rememberUpdatedState(roiA)
    val roiBRef = rememberUpdatedState(roiB)

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
                    val a = Stats.analyze(image, roiARef.value)
                    val b = Stats.analyze(image, roiBRef.value)
                    statsA = a
                    statsB = b
                    applied = controller.applied
                    if (pendingSave) {
                        pendingSave = false
                        val file = Store.saveFrame(context, image, "cap")
                        val row = buildCsvRow(
                            note, lightMode, a, b, controller.applied, settings,
                            roiARef.value, roiBRef.value, image.width, image.height, file ?: ""
                        )
                        Store.appendCsv(context, row)
                        saveMsg = if (file != null) "저장됨 · $file" else "저장 실패"
                    }
                } finally {
                    image.close()
                }
            },
            onReady = { caps = it }
        )
    }

    val paintA = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FFC107")
            textSize = 40f; isFakeBoldText = true; isAntiAlias = true
        }
    }
    val paintB = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#4FC3F7")
            textSize = 40f; isFakeBoldText = true; isAntiAlias = true
        }
    }

    Column(Modifier.fillMaxSize()) {

        // ================= 카메라 프리뷰 =================
        // weight(1f) 로 남는 공간을 전부 받는다. 아래 패널은 높이가 제한돼 있어야
        // 한다 — 무제한 스크롤 컬럼을 두면 이 영역이 0 으로 짓눌린다.
        var boxSize by remember { mutableStateOf(Size.Zero) }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .onSizeChanged { boxSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(editingA, frameW, frameH) {
                    detectTapGestures { off ->
                        val rect = fitRect(boxSize, frameW, frameH) ?: return@detectTapGestures
                        val nx = ((off.x - rect[0]) / rect[2]).coerceIn(0f, 1f)
                        val ny = ((off.y - rect[1]) / rect[3]).coerceIn(0f, 1f)
                        if (editingA) roiA = roiA.copy(cx = nx, cy = ny)
                        else roiB = roiB.copy(cx = nx, cy = ny)
                    }
                }
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val rect = fitRect(size, frameW, frameH) ?: return@Canvas
                fun draw(r: Roi, c: Color, active: Boolean, label: String, p: android.graphics.Paint) {
                    val s = r.size * minOf(rect[2], rect[3])
                    val left = rect[0] + r.cx * rect[2] - s / 2
                    val top = rect[1] + r.cy * rect[3] - s / 2
                    drawRect(c, Offset(left, top), Size(s, s), style = Stroke(if (active) 6f else 3f))
                    drawContext.canvas.nativeCanvas.drawText(label, left + 6f, top - 12f, p)
                }
                draw(roiB, B_COLOR, !editingA, "B  배경", paintB)
                draw(roiA, A_COLOR, editingA, "A  결함", paintA)
            }

            if (showHelp) {
                Box(Modifier.align(Alignment.TopCenter).padding(10.dp)) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xE0000000))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "노란 A 박스를 결함 위에, 파란 B 박스를 깨끗한 배경 위에 놓으세요.\n" +
                                "아래 버튼으로 박스를 고르고 화면을 누르면 이동합니다.",
                            fontSize = 12.sp, color = Color.White, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showHelp = false }) { Text("닫기", fontSize = 12.sp) }
                    }
                }
            }
        }

        // ================= 결과 패널 (높이 고정) =================
        Column(
            Modifier
                .fillMaxWidth()
                .background(PANEL)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Mode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m },
                        shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size)
                    ) { Text(m.label, fontSize = 14.sp) }
                }
            }

            when (mode) {
                Mode.CONTRAST -> {
                    val c = Stats.contrast(statsA, statsB) * 100
                    Result(
                        big = "%.1f%%".format(c),
                        meaning = "A 박스가 B 박스보다 이만큼 밝습니다",
                        advice = when {
                            c >= 15 -> "좋습니다. 이 조명 조건을 기록해 두세요"
                            c >= 5 -> "보이긴 합니다. 조명 각도를 더 낮춰 15%를 노려보세요"
                            c >= 0 -> "너무 약합니다. 배경을 더 어둡게, 조명은 더 비스듬히"
                            else -> "A 가 B 보다 어둡습니다. 박스 위치를 확인하세요"
                        },
                        color = when {
                            c >= 15 -> OK
                            c >= 5 -> WARN
                            else -> BAD
                        }
                    )
                }
                Mode.BACKGROUND -> {
                    val v = statsA.mean
                    Result(
                        big = "%.1f".format(v),
                        meaning = "A 박스의 밝기입니다 (0 = 완전한 검정, 255 = 흰색)",
                        advice = if (v <= BG_PASS_LEVEL) "합격입니다. 배경이 충분히 어둡습니다"
                        else "기준은 8 이하입니다. 차광판을 세우고 배경을 더 멀리 두세요",
                        color = if (v <= BG_PASS_LEVEL) OK else BAD
                    )
                }
            }

            if (statsA.satRatio > 0.005 || statsB.satRatio > 0.005) {
                Text("⚠ 너무 밝아 하얗게 뭉개진 부분이 있습니다. 각도를 조정하세요",
                    fontSize = 12.sp, color = WARN)
            }

            // ---- 박스 조작 ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("A 옮기기", editingA, A_COLOR, Modifier.weight(1f)) { editingA = true }
                Btn("B 옮기기", !editingA, B_COLOR, Modifier.weight(1f)) { editingA = false }
                Btn("작게", false, DIM) {
                    if (editingA) roiA = roiA.copy(size = (roiA.size - 0.03f).coerceAtLeast(0.04f))
                    else roiB = roiB.copy(size = (roiB.size - 0.03f).coerceAtLeast(0.04f))
                }
                Btn("크게", false, DIM) {
                    if (editingA) roiA = roiA.copy(size = (roiA.size + 0.03f).coerceAtMost(0.5f))
                    else roiB = roiB.copy(size = (roiB.size + 0.03f).coerceAtMost(0.5f))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pendingSave = true; saveMsg = "저장 중…" },
                    modifier = Modifier.weight(1f).height(50.dp)
                ) { Text("측정 저장", fontSize = 16.sp) }
                OutlinedButton(
                    onClick = { showSettings = !showSettings },
                    modifier = Modifier.height(50.dp)
                ) { Text(if (showSettings) "설정 닫기" else "설정") }
            }

            if (saveMsg.isNotEmpty()) {
                Text(saveMsg, fontSize = 11.sp, color = DIM, fontFamily = FontFamily.Monospace)
            }
        }

        // ================= 설정 (접힘, 높이 제한) =================
        if (showSettings) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .background(Color(0xFF101518))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("상세 측정값", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                StatLine("A", statsA, A_COLOR)
                StatLine("B", statsB, B_COLOR)

                HorizontalDivider(color = Color(0xFF2A3136))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("카메라 수동 고정", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Switch(settings.locked, {
                        settings = settings.copy(locked = it); controller.updateManual(settings)
                    })
                }

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
                OutlinedTextField(
                    value = lightMode, onValueChange = { lightMode = it },
                    label = { Text("조명 조건 이름") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                AppliedPanel(caps, applied)

                Text("이미지 Pictures/PrismScope · 로그 Documents/PrismScope",
                    fontSize = 11.sp, color = Color(0xFF6B767C))
            }
        }
    }
}

/* ---------------- 부품 ---------------- */

@Composable
private fun Result(big: String, meaning: String, advice: String, color: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CARD)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(big, fontSize = 38.sp, fontWeight = FontWeight.Bold, color = color,
            fontFamily = FontFamily.Monospace)
        Text(meaning, fontSize = 12.sp, color = DIM)
        Text(advice, fontSize = 13.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Btn(
    text: String, active: Boolean, color: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    if (active) {
        Button(onClick = onClick, modifier = modifier.height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.Black)
        ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(44.dp)) {
            Text(text, fontSize = 13.sp, color = color)
        }
    }
}

@Composable
private fun StatLine(name: String, s: RoiStats, color: Color) {
    Text(
        "$name  평균 %.1f   최소 %d   최대 %d   편차 %.1f   포화 %.2f%%   선명도 %.1f"
            .format(s.mean, s.min, s.max, s.stdDev, s.satRatio * 100, s.focus),
        fontSize = 11.sp, color = color, fontFamily = FontFamily.Monospace
    )
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

@Composable
private fun AppliedPanel(caps: CameraCaps, a: AppliedCamera) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF12171A))
            .padding(10.dp)
    ) {
        Text("카메라가 실제로 적용한 값", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
            "ISO ${a.iso ?: "-"}   노출 ${a.exposureNs?.let { "%.2f ms".format(it / 1e6) } ?: "-"}   " +
                "초점 ${a.focusDiopter?.let { if (it > 0f) "%.0f mm".format(1000f / it) else "무한대" } ?: "-"}",
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFB0BEC5)
        )
        val flags = listOf(
            "자동노출" to a.aeOff, "자동초점" to a.afOff, "화이트밸런스" to a.awbOff,
            "노이즈리덕션" to a.nrOff, "샤프닝" to a.edgeOff
        )
        Text(
            flags.joinToString("  ") { (n, off) -> if (off) "$n 끔 ✓" else "$n 켜짐 ✗" },
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = if (flags.all { it.second }) OK else WARN
        )
        Text(
            "카메라 ${caps.cameraId} · ${caps.hardwareLevelName}" +
                (if (caps.supportsManualSensor) " · 수동제어 지원 ✓" else " · 수동제어 불가 ✗") +
                (caps.minFocusMm?.let { " · 최소 초점거리 %.0f mm".format(it) } ?: " · 고정초점"),
            fontSize = 11.sp, color = DIM
        )
        if (!caps.supportsManualSensor) {
            Text("이 렌즈는 수동 노출을 지원하지 않습니다. 판정에 쓸 수 없습니다.",
                fontSize = 11.sp, color = BAD)
        }
    }
}

/* ---------------- 좌표 ---------------- */

/**
 * FIT_CENTER 로 표시된 프레임의 화면상 위치. [left, top, width, height].
 * ROI 는 화면 정규화 좌표라 화면에 그리려면 이 사각형이 필요하다.
 */
private fun fitRect(box: Size, fw: Int, fh: Int): FloatArray? {
    if (box.width <= 0f || box.height <= 0f || fw <= 0 || fh <= 0) return null
    // 분석 버퍼는 센서 방향(가로)이고 화면은 세로다. 보이는 비율에 맞춰 회전 보정.
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
    note: String, lightMode: String, a: RoiStats, b: RoiStats,
    cam: AppliedCamera, s: ManualSettings, ra: Roi, rb: Roi,
    w: Int, h: Int, file: String,
): String {
    fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
    val c = Stats.contrast(a, b)
    return listOf(
        q(Store.timestamp()), q(note), q(lightMode),
        "%.3f".format(a.mean), a.min, a.max, "%.3f".format(a.stdDev),
        "%.5f".format(a.satRatio), "%.3f".format(a.focus),
        "%.3f".format(b.mean), b.min, b.max, "%.3f".format(b.stdDev),
        "%.5f".format(b.satRatio), "%.3f".format(b.focus),
        "%.5f".format(c), if (a.mean <= BG_PASS_LEVEL) 1 else 0,
        cam.iso ?: "", cam.exposureNs ?: "", cam.focusDiopter ?: "",
        cam.aeMode ?: "", cam.afMode ?: "", cam.awbMode ?: "",
        cam.noiseReduction ?: "", cam.edgeMode ?: "",
        s.iso, s.exposureNs, "%.3f".format(s.focusDiopter), if (s.locked) 1 else 0,
        "%.4f".format(ra.cx), "%.4f".format(ra.cy), "%.4f".format(ra.size),
        "%.4f".format(rb.cx), "%.4f".format(rb.cy), "%.4f".format(rb.size),
        w, h, q(file)
    ).joinToString(",")
}
