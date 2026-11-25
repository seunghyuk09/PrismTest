package com.prismtest.scope

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.min

/** A separate activity stops the live CameraX lifecycle while testing saved evidence. */
class ValidationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF80D5BC))) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0E151B)) {
                    ValidationScreen { finish() }
                }
            }
        }
    }
}

private val validationMuted = Color(0xFFA6B7C3)
private val validationGood = Color(0xFF63DDB0)
private val validationWarn = Color(0xFFFFC857)
private fun ms(value: Double?) = value?.let { String.format(Locale.US, "%.2f ms", it) } ?: "—"
private fun percent(value: Double?) = value?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "—"

@Composable
private fun ValidationSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF19242D))
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        content()
    }
}

@Composable
private fun ValidationScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var media by remember { mutableStateOf(ValidationFrames.referenceMedia) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var roi by remember { mutableStateOf(ValidationFrames.referenceRoi) }
    var truth by remember { mutableStateOf(ValidationFrames.referenceTruth) }
    var completeTruth by remember { mutableStateOf(false) }
    var tolerance by remember { mutableDoubleStateOf(5.0) }
    var pieceId by remember { mutableStateOf("user-video2-piece-1") }
    var requestedMs by remember { mutableLongStateOf(0) }
    var loadedMs by remember { mutableLongStateOf(0) }
    var editMode by remember { mutableStateOf(ValidationEditMode.POINTS) }
    var showAll by remember { mutableStateOf(false) }
    var frameRun by remember { mutableStateOf<ValidationFrameRun?>(null) }
    var suite by remember { mutableStateOf<NativeSuiteResult?>(null) }
    var batch by remember { mutableStateOf<List<SampledValidationFrame>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf("") }
    var exportPending by remember { mutableStateOf(false) }
    val editable = !busy && !exportPending
    val comparison = remember(frameRun, truth, tolerance, completeTruth) {
        frameRun?.let { ValidationMetrics.compare(truth,
            it.candidates.map { c -> ValidationPoint(c.x, c.y) }, tolerance, completeTruth) }
    }

    fun job(label: String, task: suspend () -> Unit) {
        busy = true; progress = label; message = ""
        scope.launch {
            try { task() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = "실행하지 못했습니다: ${error.message ?: error.javaClass.simpleName}" }
            finally { busy = false; progress = "" }
        }
    }
    fun installFrame(next: ValidationMedia, image: Bitmap, time: Long) {
        media = next; bitmap = image; requestedMs = time; loadedMs = time
        frameRun = null; batch = emptyList(); completeTruth = false
        if (next.uri == null) {
            roi = ValidationFrames.referenceRoi; truth = ValidationFrames.referenceTruth
            pieceId = "user-video2-piece-1"; tolerance = 5.0; editMode = ValidationEditMode.POINTS
        } else {
            val side = min(900, (min(image.width, image.height) * .65).toInt()).coerceAtLeast(20)
            roi = ValidationRoi((image.width - side) / 2, (image.height - side) / 2,
                (image.width + side) / 2, (image.height + side) / 2)
            truth = emptyList(); editMode = ValidationEditMode.ROI
        }
    }
    fun saveReport(uri: android.net.Uri?) {
        exportPending = false
        if (uri == null) { pendingExport = ""; return }
        val data = pendingExport
        pendingExport = ""
        if (data.isBlank()) { message = "보고서를 다시 준비해서 저장해 주세요"; return }
        job("보고서 저장 중") {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("저장 위치를 열 수 없습니다")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(data) }
            }
            message = "보고서를 저장했습니다"
        }
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), ::saveReport)
    val saveHtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html"), ::saveReport)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) job("파일과 첫 프레임을 여는 중") {
            val loaded = withContext(Dispatchers.IO) {
                val next = ValidationFrames.open(context, uri)
                next to ValidationFrames.decode(context, next, 0)
            }
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* A temporary grant is enough for this session. */ }
            installFrame(loaded.first, loaded.second, 0)
            pieceId = "sample-001"
            message = "검사면만 드래그해서 선택하세요. 테두리·밝은 반사 띠는 가능한 한 피하세요"
        }
    }
    LaunchedEffect(Unit) {
        try { bitmap = withContext(Dispatchers.IO) { ValidationFrames.decode(context, media, 0) } }
        catch (e: Exception) { message = "참고 장면을 열지 못했습니다: ${e.message}" }
    }

    fun report() = ValidationReport.json(media.name, pieceId.trim(), if (media.isVideo) loadedMs else null,
        frameRun, truth, tolerance, completeTruth, suite, batch,
        "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}",
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("닫기") }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text("디그 검증기", fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Text("저장한 장면으로 검출을 확인합니다", color = validationMuted, fontSize = 12.sp)
            }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(progress, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = validationWarn, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (message.isNotBlank()) Text(message, color = validationWarn, fontSize = 13.sp)
            ValidationSection("1 · 테스트할 장면") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { open.launch(arrayOf("video/*", "image/*")) }, enabled = editable,
                        modifier = Modifier.weight(1f)) { Text("사진·영상 열기") }
                    OutlinedButton(onClick = {
                        job("내장 예시 여는 중") {
                            val next = ValidationFrames.referenceMedia
                            val image = withContext(Dispatchers.IO) { ValidationFrames.decode(context, next, 0) }
                            installFrame(next, image, 0)
                        }
                    }, enabled = editable, modifier = Modifier.weight(1f)) { Text("내장 디그 예시") }
                }
                Text(media.name, fontSize = 13.sp)
                bitmap?.let { Text("분석 이미지 ${it.width} × ${it.height}px · 좌표·허용 오차는 이 해상도 기준",
                    fontSize = 12.sp, color = validationMuted) }
                if (media.isVideo) {
                    Text("요청 ${requestedMs}ms / 열린 장면 ${loadedMs}ms / 전체 ${media.durationMs}ms", fontSize = 12.sp)
                    Slider(value = requestedMs.toFloat(), onValueChange = { requestedMs = it.toLong() },
                        valueRange = 0f..(media.durationMs - 1).coerceAtLeast(1).toFloat(), enabled = editable)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { requestedMs = (requestedMs - 100).coerceAtLeast(0) }, enabled = editable) { Text("−0.1초") }
                        OutlinedButton(onClick = { requestedMs = (requestedMs + 100).coerceAtMost(media.durationMs - 1) }, enabled = editable) { Text("+0.1초") }
                        Button(onClick = {
                            val next = media; val time = requestedMs
                            job("선택한 프레임 여는 중") {
                                val image = withContext(Dispatchers.IO) { ValidationFrames.decode(context, next, time) }
                                installFrame(next, image, time)
                                message = "장면이 바뀌어 영역과 정답 표시를 초기화했습니다"
                            }
                        }, enabled = editable) { Text("프레임 열기") }
                    }
                    Text("가장 가까운 프레임을 엽니다. 시각을 바꾼 뒤 ‘프레임 열기’를 누르세요", fontSize = 12.sp, color = validationMuted)
                }
                if (media.uri == null) Text("사용자가 디그라고 확인한 두 점이 미리 표시되어 있습니다. 나머지 점의 정답은 미확인입니다.",
                    color = validationMuted, fontSize = 12.sp)
                OutlinedTextField(pieceId, { pieceId = it.take(100) }, label = { Text("실물 샘플 ID") },
                    singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
                Text("같은 제품을 여러 각도로 찍어도 같은 ID를 사용하세요", color = validationMuted, fontSize = 12.sp)
            }
            ValidationSection("2 · 검사 영역과 정답 표시") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = editMode == ValidationEditMode.ROI, onClick = { editMode = ValidationEditMode.ROI },
                        enabled = editable, label = { Text("영역 드래그") })
                    FilterChip(selected = editMode == ValidationEditMode.POINTS, onClick = { editMode = ValidationEditMode.POINTS },
                        enabled = editable && bitmap?.let { roi.validFor(it.width, it.height) } == true,
                        label = { Text("정답 점 찍기") })
                }
                Text(if (editMode == ValidationEditMode.ROI) "전체 사진에서 검사면만 사각형으로 드래그하세요"
                    else "확대한 영역에서 실제 디그의 중심을 누르세요. 초록색은 정답, 노란색은 검출 후보입니다",
                    fontSize = 12.sp, color = validationMuted)
                bitmap?.let { image ->
                    ValidationCanvas(image, roi, truth, frameRun, comparison, editMode, editable, showAll,
                        Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(8.dp)),
                        onRoi = { selected ->
                            if (selected.validFor(image.width, image.height)) {
                                roi = selected; truth = emptyList(); frameRun = null; batch = emptyList()
                                completeTruth = false; editMode = ValidationEditMode.POINTS; message = ""
                            } else message = "한 변 20px 이상, 면적 100만 화소 이하로 선택하세요"
                        }, onPoint = { p ->
                            if (truth.size < 256 && roi.contains(p)) {
                                truth = truth + p; completeTruth = false
                            }
                        })
                }
                Text("영역 ${roi.width} × ${roi.height}px · 표시한 정답 ${truth.size}개", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { truth = truth.dropLast(1); completeTruth = false }, enabled = editable && truth.isNotEmpty()) { Text("마지막 점 취소") }
                    TextButton(onClick = { truth = emptyList(); completeTruth = false }, enabled = editable && truth.isNotEmpty()) { Text("점 모두 지우기") }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = editable) { completeTruth = !completeTruth },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = completeTruth, onCheckedChange = { completeTruth = it }, enabled = editable)
                    Text("이 영역의 모든 디그를 표시했습니다\n양품이면 정답 0개로 체크할 수 있습니다", fontSize = 12.sp)
                }
                Text("위치 허용 오차 ${tolerance.toInt()}px · 물리적인 디그 크기와 다릅니다", fontSize = 12.sp, color = validationMuted)
                Slider(tolerance.toFloat(), { tolerance = it.toInt().toDouble() }, valueRange = 2f..32f, steps = 29, enabled = editable)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(showAll, { showAll = it }, enabled = editable)
                    Text("검출 후보 모두 표시 (최대 32개)", fontSize = 12.sp)
                }
                Button(onClick = {
                    val image = bitmap ?: return@Button; val selected = roi
                    job("현재 영역의 디그 후보 분석 중") {
                        frameRun = withContext(Dispatchers.Default) { ValidationFrames.analyze(image, selected) }
                    }
                }, enabled = editable && bitmap?.let { roi.validFor(it.width, it.height) } == true,
                    modifier = Modifier.fillMaxWidth()) { Text("이 장면 비교하기") }
            }
            comparison?.let { c ->
                ValidationSection("3 · 표시한 정답과 비교") {
                    Text("찾은 정답 ${c.matches.size} / ${truth.size}개 · 놓친 정답 ${c.missed.size}개",
                        color = if (c.missed.isEmpty() && truth.isNotEmpty()) validationGood else validationWarn,
                        fontWeight = FontWeight.Bold)
                    Text(if (completeTruth) "추가 검출 ${c.extra.size}개 · 오검출 후보"
                        else "추가 검출 ${c.extra.size}개 · 정답 미표시로 분류 보류", fontSize = 14.sp)
                    Text("표시한 점 일치율 ${percent(c.labelledPointAgreement)}", fontSize = 13.sp)
                    if (completeTruth) Text("이 장면 정밀도 ${percent(c.precision)} · 재현율 ${percent(c.recall)}", fontSize = 13.sp)
                    else Text("일부 정답만 표시했으므로 정밀도·재현율을 계산하지 않습니다", color = validationMuted, fontSize = 12.sp)
                    frameRun?.let { r ->
                        Text("후보 ${r.candidates.size}개 / 최대 32개 · 밝은 반사 마스킹 ${percent(r.maskedFraction)}", fontSize = 12.sp)
                        Text("회색 변환 ${ms(r.grayMs)} · 검출 코어 ${ms(r.coreMs)}", fontSize = 12.sp)
                        if (r.candidates.size == 32) Text("후보가 출력 상한에 도달했습니다. 검출 결과 일부가 생략될 수 있습니다", color = validationWarn, fontSize = 12.sp)
                    }
                    Text("모든 출력 후보를 비교에 사용합니다. 이 값은 한 장면의 위치 비교이며 제품 전체의 합격 판정이나 정확도가 아닙니다.",
                        color = validationMuted, fontSize = 12.sp)
                }
            }
            ValidationSection("4 · 폰에서 코어 자체 검증") {
                Text("배경·잡음·반사선·두 점·회전·Y 버퍼 간격 등 10가지 입력을 실제 JNI/C++ 코어로 검사합니다",
                    color = validationMuted, fontSize = 12.sp)
                OutlinedButton(onClick = {
                    job("코어 자체 검증과 40회 시간 측정 중") {
                        suite = withContext(Dispatchers.Default) { NativeValidationSuite.run() }
                    }
                }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text("코어 자체 검증 실행") }
                suite?.let { s ->
                    Text("${if (s.passed) "통과" else "실패"} · ${s.checks.count { it.passed }} / ${s.checks.size}",
                        color = if (s.passed) validationGood else validationWarn, fontWeight = FontWeight.Bold)
                    s.checks.forEach { check -> Text("${if (check.passed) "✓" else "✗"} ${check.name}", fontSize = 12.sp) }
                    Text("코어 P50 ${ms(ValidationMetrics.percentile(s.benchmarkMs, .5))} / P95 ${ms(ValidationMetrics.percentile(s.benchmarkMs, .95))}", fontSize = 12.sp)
                    Text("160×140 합성 영역 · 준비 5회, 측정 40회. 촬영·영상 해독·화면 표시 시간은 포함하지 않습니다.",
                        color = validationMuted, fontSize = 12.sp)
                }
            }
            if (media.isVideo) ValidationSection("5 · 영상 반복 분석") {
                Text("현재 영역을 고정하고 최대 30장면을 샘플링합니다. 움직이는 제품에는 정답 점을 복사하지 않으며, 검출 개수와 처리 시간만 기록합니다.",
                    color = validationMuted, fontSize = 12.sp)
                OutlinedButton(onClick = {
                    val selected = roi; val source = media
                    job("영상 반복 분석 준비 중") {
                        val frames = mutableListOf<SampledValidationFrame>()
                        val times = ValidationMetrics.sampleTimes(source.durationMs)
                        for ((i, time) in times.withIndex()) {
                            progress = "영상 분석 ${i + 1} / ${times.size} · ${time}ms"
                            val sample = withContext(Dispatchers.Default) {
                                var image: Bitmap? = null
                                try {
                                    image = ValidationFrames.decode(context, source, time)
                                    SampledValidationFrame(time, ValidationFrames.analyze(image, selected))
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (e: Exception) { SampledValidationFrame(time, null, e.message ?: e.javaClass.simpleName) }
                                finally { image?.recycle() }
                            }
                            frames += sample
                        }
                        batch = frames
                    }
                }, enabled = editable && bitmap?.let { roi.validFor(it.width, it.height) } == true,
                    modifier = Modifier.fillMaxWidth()) { Text("영상 반복 분석 실행") }
                if (batch.isNotEmpty()) {
                    Text("완료 ${batch.count { it.run != null }} / ${batch.size}장면 · 오류 ${batch.count { it.error != null }}개", fontSize = 13.sp)
                    Text("코어 P50 ${ms(ValidationMetrics.percentile(batch.mapNotNull { it.run?.coreMs }, .5))} / P95 ${ms(ValidationMetrics.percentile(batch.mapNotNull { it.run?.coreMs }, .95))}", fontSize = 12.sp)
                    batch.forEach { f -> Text("${f.requestedMs}ms · ${f.run?.let { "후보 ${it.candidates.size}개 / ${ms(it.coreMs)}" } ?: f.error}", fontSize = 11.sp) }
                }
            }
            ValidationSection("보고서 저장") {
                Text("JSON은 수치·좌표 기록, HTML은 원본 영역과 표시 결과를 함께 담습니다. 저장 위치는 직접 선택합니다.",
                    color = validationMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        pendingExport = report().toString(2); exportPending = true
                        saveJson.launch("prism-validation.json")
                    }, enabled = editable && (frameRun != null || suite != null || batch.isNotEmpty()),
                        modifier = Modifier.weight(1f)) { Text("JSON 저장") }
                    Button(onClick = {
                        val record = report(); val image = bitmap; val run = frameRun; val labels = truth; val tol = tolerance
                        job("그림을 포함한 보고서 준비 중") {
                            pendingExport = withContext(Dispatchers.Default) { ValidationReport.html(record, image, run, labels, tol) }
                            exportPending = true
                            saveHtml.launch("prism-validation.html")
                        }
                    }, enabled = editable && (frameRun != null || suite != null || batch.isNotEmpty()),
                        modifier = Modifier.weight(1f)) { Text("HTML 저장") }
                }
                Text("검증기는 디그 점 후보 검출을 다룹니다. 이물·스크래치·뿌연 불량·기포의 성능은 별도 정답 데이터로 검증해야 합니다.",
                    color = validationMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
