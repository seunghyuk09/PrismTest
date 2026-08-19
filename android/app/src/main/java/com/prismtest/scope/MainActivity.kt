package com.prismtest.scope

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** 측정 모드. v0 은 판정하지 않는다 — 재는 것까지가 역할이다. */
enum class Mode(val label: String) {
    CONTRAST("대비 측정"),
    BACKGROUND("배경 검증"),
}

/** 배경 합격 기준. 8bit 평균 8 이하 = 포화값의 약 3%. */
const val BG_PASS_LEVEL = 8.0

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
        if (granted) Scope(controller)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("카메라 권한이 필요합니다", color = Color.White)
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

    var roiA by remember { mutableStateOf(Roi(0.40f, 0.50f, 0.18f)) }
    var roiB by remember { mutableStateOf(Roi(0.68f, 0.50f, 0.18f)) }
    var editingA by remember { mutableStateOf(true) }

    var statsA by remember { mutableStateOf(RoiStats.EMPTY) }
    var statsB by remember { mutableStateOf(RoiStats.EMPTY) }
    var frameW by remember { mutableIntStateOf(0) }
    var frameH by remember { mutableIntStateOf(0) }

    var note by remember { mutableStateOf("") }
    var lightMode by remember { mutableStateOf("darkfield") }
    var saveMsg by remember { mutableStateOf("") }
    var pendingSave by remember { mutableStateOf(false) }

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

    Column(Modifier.fillMaxSize()) {

        // ---------- 프리뷰 + ROI 오버레이 ----------
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
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val rect = fitRect(size, frameW, frameH) ?: return@Canvas
                fun draw(r: Roi, color: Color, active: Boolean) {
                    val s = r.size * minOf(rect[2], rect[3])
                    drawRect(
                        color = color,
                        topLeft = Offset(rect[0] + r.cx * rect[2] - s / 2, rect[1] + r.cy * rect[3] - s / 2),
                        size = Size(s, s),
                        style = Stroke(width = if (active) 5f else 2.5f)
                    )
                }
                draw(roiB, Color(0xFF4FC3F7), !editingA)
                draw(roiA, Color(0xFFFFC107), editingA)
            }
        }

        // ---------- 결과 ----------
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF15191C))
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Mode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m },
                        shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size)
                    ) { Text(m.label) }
                }
            }

            when (mode) {
                Mode.CONTRAST -> {
                    val c = Stats.contrast(statsA, statsB)
                    BigNumber(
                        label = "ΔI/I  (A 대비 B)",
                        value = String.format("%.1f%%", c * 100),
                        hint = when {
                            c >= 0.15 -> "양호 — 목표 15% 이상"
                            c >= 0.05 -> "최소 기준 통과 (5%)"
                            else -> "부족 — 조명을 바꿔야 한다"
                        },
                        color = when {
                            c >= 0.15 -> Color(0xFF66BB6A)
                            c >= 0.05 -> Color(0xFFFFC107)
                            else -> Color(0xFFEF5350)
                        }
                    )
                }
                Mode.BACKGROUND -> {
                    val pass = statsA.mean <= BG_PASS_LEVEL
                    BigNumber(
                        label = "배경 평균 밝기 (ROI A)",
                        value = String.format("%.1f / 255", statsA.mean),
                        hint = if (pass) "합격 — 8 이하" else "불합격 — 차광판·이격거리 재점검",
                        color = if (pass) Color(0xFF66BB6A) else Color(0xFFEF5350)
                    )
                }
            }

            RoiRow("A", statsA, Color(0xFFFFC107))
            RoiRow("B", statsB, Color(0xFF4FC3F7))

            if (statsA.satRatio > 0.005) {
                Warn("ROI A 포화 화소 ${(statsA.satRatio * 100).roundToInt()}% — 글레어. 각도를 조정하라")
            }

            HorizontalDivider(color = Color(0xFF2A3136))

            // ---------- ROI 조작 ----------
            Text("ROI — 화면을 탭하면 선택된 박스가 이동한다", fontSize = 12.sp, color = Color(0xFF8B979D))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(editingA, { editingA = true }, { Text("A 편집") })
                FilterChip(!editingA, { editingA = false }, { Text("B 편집") })
            }
            val cur = if (editingA) roiA else roiB
            LabeledSlider("크기", "%.0f%%".format(cur.size * 100), cur.size, 0.04f..0.5f) { v ->
                if (editingA) roiA = roiA.copy(size = v) else roiB = roiB.copy(size = v)
            }

            HorizontalDivider(color = Color(0xFF2A3136))

            // ---------- 카메라 수동 고정 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("수동 고정", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Switch(settings.locked, {
                    settings = settings.copy(locked = it); controller.updateManual(settings)
                })
            }

            val isoLo = caps.isoRange?.lower ?: 50
            val isoHi = caps.isoRange?.upper ?: 800
            LabeledSlider("ISO", "${settings.iso}", settings.iso.toFloat(),
                isoLo.toFloat()..isoHi.toFloat()) { v ->
                settings = settings.copy(iso = v.roundToInt()); controller.updateManual(settings)
            }
            val expLo = (caps.exposureRange?.lower ?: 100_000L).toFloat()
            val expHi = minOf(caps.exposureRange?.upper ?: 100_000_000L, 100_000_000L).toFloat()
            LabeledSlider("노출", "%.1f ms".format(settings.exposureNs / 1e6),
                settings.exposureNs.toFloat(), expLo..expHi) { v ->
                settings = settings.copy(exposureNs = v.toLong()); controller.updateManual(settings)
            }
            val focusHi = if (caps.minFocusDistance > 0f) caps.minFocusDistance else 10f
            LabeledSlider("초점",
                if (settings.focusDiopter > 0f) "%.2f D · %.0f mm".format(
                    settings.focusDiopter, 1000f / settings.focusDiopter
                ) else "∞",
                settings.focusDiopter, 0f..focusHi) { v ->
                settings = settings.copy(focusDiopter = v); controller.updateManual(settings)
            }

            AppliedPanel(caps, applied)

            HorizontalDivider(color = Color(0xFF2A3136))

            // ---------- 기록 ----------
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text("메모 (샘플 ID 등)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = lightMode, onValueChange = { lightMode = it },
                label = { Text("조명 조건") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            Button(
                onClick = { pendingSave = true; saveMsg = "저장 중…" },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("측정 저장  (PNG + CSV)", fontSize = 16.sp) }
            if (saveMsg.isNotEmpty()) {
                Text(saveMsg, fontSize = 12.sp, color = Color(0xFF8B979D), fontFamily = FontFamily.Monospace)
            }
            Text(
                "이미지 Pictures/PrismScope · 로그 Documents/PrismScope/prismscope_log.csv",
                fontSize = 11.sp, color = Color(0xFF6B767C)
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/* ---------------- 부품 ---------------- */

@Composable
private fun BigNumber(label: String, value: String, hint: String, color: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1B2126))
            .padding(14.dp)
    ) {
        Text(label, fontSize = 12.sp, color = Color(0xFF8B979D))
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = color,
            fontFamily = FontFamily.Monospace)
        Text(hint, fontSize = 12.sp, color = color)
    }
}

@Composable
private fun RoiRow(name: String, s: RoiStats, color: Color) {
    Text(
        "$name  평균 %.1f   최소 %d   최대 %d   σ %.1f   포화 %.2f%%   초점 %.1f"
            .format(s.mean, s.min, s.max, s.stdDev, s.satRatio * 100, s.focus),
        fontSize = 12.sp, color = color, fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun Warn(text: String) {
    Text(text, fontSize = 12.sp, color = Color(0xFFFFB74D))
}

@Composable
private fun LabeledSlider(
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
            onValueChange = onChange,
            valueRange = range
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
        val line = buildString {
            append("ISO ${a.iso ?: "-"}   ")
            append("노출 ${a.exposureNs?.let { "%.2f ms".format(it / 1e6) } ?: "-"}   ")
            append("초점 ${a.focusDiopter?.let { "%.2f D".format(it) } ?: "-"}")
        }
        Text(line, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFB0BEC5))
        val flags = listOf(
            "AE" to a.aeOff, "AF" to a.afOff, "AWB" to a.awbOff,
            "노이즈리덕션" to a.nrOff, "샤프닝" to a.edgeOff
        )
        Text(
            flags.joinToString("  ") { (n, off) -> if (off) "$n OFF ✓" else "$n ON ✗" },
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = if (flags.all { it.second }) Color(0xFF66BB6A) else Color(0xFFFFB74D)
        )
        Text(
            "카메라 ${caps.cameraId} · ${caps.hardwareLevelName}" +
                (if (caps.supportsManualSensor) " · MANUAL_SENSOR ✓" else " · MANUAL_SENSOR ✗") +
                (caps.minFocusMm?.let { " · 최소초점 %.0f mm".format(it) } ?: " · 고정초점"),
            fontSize = 11.sp, color = Color(0xFF8B979D)
        )
        if (!caps.supportsManualSensor) {
            Text("이 렌즈는 수동 노출을 지원하지 않는다. 판정에 쓸 수 없다.",
                fontSize = 11.sp, color = Color(0xFFEF5350))
        }
    }
}

/* ---------------- 좌표 ---------------- */

/**
 * FIT_CENTER 로 표시된 프레임의 화면상 위치. [left, top, width, height].
 * ROI 는 프레임 정규화 좌표라 화면 좌표로 옮기려면 이 사각형이 필요하다.
 */
private fun fitRect(box: Size, fw: Int, fh: Int): FloatArray? {
    if (box.width <= 0f || box.height <= 0f || fw <= 0 || fh <= 0) return null
    // 분석 버퍼는 센서 방향(가로)이고 화면은 세로다. 화면에 보이는 비율에 맞춰 회전 보정.
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
