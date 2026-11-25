package com.prismtest.scope

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun ZoomableEvidenceImage(bitmap: Bitmap, modifier: Modifier = Modifier) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var translation by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Box(modifier.clipToBounds()) {
        Image(bitmap.asImageBitmap(), "저장된 검사 사진", Modifier.fillMaxSize()
            .pointerInput(bitmap) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 8f)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val moved = (translation - (centroid - center)) * (next / scale) + (centroid - center) + pan
                    translation = Offset(moved.x.coerceIn(-size.width * (next - 1) / 2, size.width * (next - 1) / 2),
                        moved.y.coerceIn(-size.height * (next - 1) / 2, size.height * (next - 1) / 2))
                    scale = next
                }
            }.pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = { scale = 1f; translation = Offset.Zero })
            }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = translation.x, translationY = translation.y),
            contentScale = ContentScale.Fit)
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(String.format(Locale.US, "화면 %.1f×", scale), color = Color(0xFF80D5BC), fontSize = 12.sp)
            TextButton(onClick = { scale = 1f; translation = Offset.Zero }) { Text("확대 초기화") }
        }
        Text("두 손가락 확대·이동 / 두 번 눌러 초기화", Modifier.align(Alignment.BottomCenter).padding(6.dp),
            color = Color(0xFFADBFCB), fontSize = 11.sp)
    }
}
