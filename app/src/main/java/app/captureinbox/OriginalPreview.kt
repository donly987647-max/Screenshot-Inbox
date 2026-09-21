package app.captureinbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File

@Composable
fun OriginalPreview(file: File?) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        AsyncImage(file, "원본 스크린샷. 눌러서 확대", Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp).clickable { expanded = true }, contentScale = ContentScale.Fit)
        TextButton(onClick = { expanded = true }) { Icon(Icons.Outlined.ZoomIn, null); Spacer(Modifier.width(6.dp)); Text("원본 크게 보기") }
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101916)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("원본 스크린샷", color = Color.White, modifier = Modifier.weight(1f))
                    IconButton(onClick = { expanded = false }) { Icon(Icons.Outlined.Close, "원본 닫기", tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        val maxX = size.width * (scale - 1f) / 2f
                        val maxY = size.height * (scale - 1f) / 2f
                        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                    }
                }) {
                    AsyncImage(file, "확대 가능한 원본", Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scale = (scale / 1.5f).coerceAtLeast(1f); offset = Offset.Zero }) { Icon(Icons.Outlined.ZoomOut, "축소", tint = Color.White) }
                    TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("${(scale * 100).toInt()}% · 초기화", color = Color.White) }
                    IconButton(onClick = { scale = (scale * 1.5f).coerceAtMost(8f) }) { Icon(Icons.Outlined.ZoomIn, "확대", tint = Color.White) }
                }
            }
        }
    }
}
