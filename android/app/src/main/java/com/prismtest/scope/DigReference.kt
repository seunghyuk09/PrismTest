package com.prismtest.scope

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.ByteBuffer

/** A real, labelled reference frame also exercises the same JNI core without a live camera. */
@Composable
fun DigReference() {
    val context = LocalContext.current
    val sample = remember {
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.dig_reference)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val gray = ByteBuffer.allocateDirect(pixels.size)
        pixels.forEach { p -> gray.put((((p shr 16 and 255) * 77 + (p shr 8 and 255) * 150 +
            (p and 255) * 29) shr 8).toByte()) }
        gray.rewind()
        // This is the manually selected inspection ROI of the reference, not a runtime location.
        val detected = NativeDig.detect(gray, 0, bitmap.width, 1, 220, 230, 160, 160,
            bitmap.width, bitmap.height, 0)
        bitmap.asImageBitmap() to detected
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("사용자가 확인한 디그 예시", color = Color.White, fontSize = 15.sp)
        Box(Modifier.fillMaxWidth().aspectRatio(620f / 420f)) {
            Image(sample.first, "영상 2의 2초 장면. 사용자가 중앙의 두 점을 디그로 확인했습니다.",
                modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                sample.second.spots.take(2).forEach { spot ->
                    drawCircle(Color(0xFFFFC107), 11.dp.toPx(),
                        Offset(spot.cx * size.width, spot.cy * size.height), style = Stroke(2.dp.toPx()))
                }
            }
        }
        Text("노란 원은 같은 검출 코어가 찾은 상위 두 점 후보입니다. 원의 크기는 실제 디그 크기가 아닙니다. " +
            "다른 점은 미분류이며 디그 2곳이 있는 이 제품의 불량 수량은 1개입니다.",
            color = Color(0xFFB0BEC5), fontSize = 12.sp)
    }
}
