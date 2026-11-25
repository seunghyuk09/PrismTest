package com.prismtest.scope

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

class InspectionResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra("report_uri").orEmpty()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF80D5BC), onPrimary = Color(0xFF03251D))) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0E151B), contentColor = MaterialTheme.colorScheme.onSurface) {
                    InspectionResultScreen(report) { finish() }
                }
            }
        }
    }
    companion object {
        fun open(context: Context, reportUri: String) {
            context.startActivity(Intent(context, InspectionResultActivity::class.java).putExtra("report_uri", reportUri))
        }
    }
}

private fun resultColor(value: String) = when (value) {
    "FAIL" -> Color(0xFFFF6373)
    "PASS" -> Color(0xFF63DDB0)
    "RECHECK" -> Color(0xFFFFC857)
    else -> Color(0xFFADBFCB)
}

@Composable
private fun InspectionResultScreen(reportUri: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var record by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    var showOriginal by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(reportUri) {
        try {
            record = withContext(Dispatchers.IO) {
                val uri = Uri.parse(reportUri)
                require(uri.scheme == "content" && uri.authority == "media") { "검사 기록의 저장 위치를 확인해 주세요" }
                val input = context.contentResolver.openInputStream(uri) ?: error("검사 기록을 열지 못했습니다")
                val json = input.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                require(json.optString("schema") == "prism-inspection-v1") { "지원하지 않는 검사 기록입니다" }
                json
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = "결과를 열지 못했습니다: ${e.message}" }
    }
    val types = record?.optJSONArray("types")
    val result = if (types != null && selected < types.length()) types.getJSONObject(selected) else null
    val files = result?.optJSONObject("photos")
    val imageUri = files?.optString(if (showOriginal) "original_uri" else "annotated_uri").orEmpty()
    LaunchedEffect(imageUri) {
        photo = null
        if (imageUri.isBlank()) return@LaunchedEffect
        loading = true; error = ""
        try {
            photo = withContext(Dispatchers.IO) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(imageUri))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = "사진을 열지 못했습니다: ${e.message}" }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("검사 결과 · 자동 저장", Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onClose) { Text("닫기") }
        }
        record?.let { r ->
            Text("전체: ${r.optString("verdict_label")} · ${r.optString("face_label")}",
                fontSize = 19.sp, fontWeight = FontWeight.Bold, color = resultColor(r.optString("verdict")))
            if (!r.isNull("issue")) Text(r.optString("issue"), color = Color(0xFFFFC857), fontSize = 13.sp)
            Text(r.optString("completed_at"), fontSize = 12.sp, color = Color(0xFFA6B7C3))
        }
        if (types != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until types.length()) {
                val t = types.getJSONObject(i)
                FilterChip(selected == i, { selected = i }, label = { Text("${t.optString("label")} · ${t.optString("verdict_label")}") })
            }
        }
        result?.let { t ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!showOriginal, { showOriginal = false }, label = { Text("의심 위치 표시") })
                FilterChip(showOriginal, { showOriginal = true }, label = { Text("원본 휘도 사진") })
            }
            Text("${if (t.optBoolean("valid_peak")) "해당 유형의 최고 점수 장면" else "진단용 장면 · 유효 영상 없음"} · 점수 ${String.format(Locale.US, "%.2f", t.optDouble("score"))}",
                fontSize = 12.sp, color = Color(0xFFA6B7C3))
            t.optJSONObject("frame")?.let { frame ->
                Text("원본 ${frame.optInt("display_width")}×${frame.optInt("display_height")}px · 휘도 PNG",
                    fontSize = 12.sp, color = Color(0xFFA6B7C3))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            photo?.let { ZoomableEvidenceImage(it, Modifier.fillMaxSize()) }
            if (loading || record == null && error.isBlank()) CircularProgressIndicator()
            else if (imageUri.isBlank() && record != null) Text("촬영 프레임을 확보하지 못해 사진이 없습니다.\n검사 조건과 기록을 확인해 주세요.")
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFC857), fontSize = 13.sp)
        Column(Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (result?.optString("location_mode") == "dig_candidates")
                "원은 디그 후보 위치입니다. 1번은 점수가 가장 높은 후보이며 실제 디그 크기·확정을 뜻하지 않습니다."
                else "사각형은 점수를 계산한 영역입니다. 이 유형은 개별 결함 위치를 산출하지 않습니다.", fontSize = 12.sp)
            Text("사진: ${InspectionStorage.IMAGE_DIR}\n기록: ${InspectionStorage.REPORT_DIR}",
                fontSize = 11.sp, color = Color(0xFFA6B7C3))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(imageUri), "image/png")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { error = "갤러리·파일 앱의 PrismScope/Results에서 확인하세요" }
            }, enabled = imageUri.isNotBlank(), modifier = Modifier.weight(1f)) { Text("사진 앱에서 열기") }
            Button(onClick = onClose, modifier = Modifier.weight(1f)) { Text("검사 화면으로") }
        }
    }
}
