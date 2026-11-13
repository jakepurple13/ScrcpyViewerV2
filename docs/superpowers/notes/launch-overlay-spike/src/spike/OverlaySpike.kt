package spike

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.material.MaterialDecoratedWindow
import dev.nucleusframework.window.material.MaterialTitleBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SLOW = (System.getProperty("spike.slow") ?: "1").toFloat()
private val FORCE_X11 = System.getProperty("spike.forceX11") == "true"
private val AUTO = System.getProperty("spike.auto") != "false"
private val t0 = System.nanoTime()
private fun log(msg: String) = System.err.println("[spike +%5d ms] %s".format((System.nanoTime() - t0) / 1_000_000, msg))

private enum class Phase { Launch, Quit }

fun main() {
    log("start slow=$SLOW forceX11=$FORCE_X11 os=${System.getProperty("os.name")} session=${System.getenv("XDG_SESSION_TYPE")}")
    nucleusApplication(enableSingleInstance = false) {
        val pos = WindowPosition(200.dp, 160.dp)
        val size = DpSize(760.dp, 480.dp)
        val mainState = rememberWindowState(position = pos, size = size)
        var mainVisible by remember { mutableStateOf(false) }
        var overlay by remember { mutableStateOf<Phase?>(Phase.Launch) }
        var picture by remember { mutableStateOf<ImageBitmap?>(null) }
        var mainDrewAfterShow by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var layerRef by remember { mutableStateOf<androidx.compose.ui.graphics.layer.GraphicsLayer?>(null) }
        var requestQuit by remember { mutableStateOf(false) }

        MaterialTheme(colorScheme = darkColorScheme()) {
            MaterialDecoratedWindow(
                onCloseRequest = { requestQuit = true },
                state = mainState,
                visible = mainVisible,
                title = "Overlay spike – real window",
            ) {
                MaterialTitleBar()
                val layer = rememberGraphicsLayer()
                androidx.compose.runtime.SideEffect { layerRef = layer }
                Box(
                    Modifier.fillMaxSize()
                        .drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        }
                        .background(Color(0xFF0E1514)),
                ) {
                    RealContent(onQuit = { requestQuit = true })
                }
                LaunchedEffect(mainVisible) {
                    if (mainVisible) {
                        withFrameNanos { }
                        withFrameNanos { }
                        log("main window drew after show")
                        mainDrewAfterShow = true
                    }
                }
                // Picture taken while hidden (launch): does a hidden Tao window render at all?
                LaunchedEffect(Unit) {
                    delay(50)
                    val p = runCatching { layer.toImageBitmap() }.getOrNull()
                    log("picture while hidden: ${p?.let { "${it.width}x${it.height}" } ?: "none"}")
                }
            }

            LaunchedEffect(requestQuit) {
                if (!requestQuit) return@LaunchedEffect
                picture = runCatching { layerRef?.toImageBitmap() }.getOrNull()
                log("quit: picture ${picture?.width}x${picture?.height}")
                overlay = Phase.Quit
            }
            LaunchedEffect(mainDrewAfterShow) {
                if (mainDrewAfterShow && overlay == Phase.Launch) {
                    overlay = null
                    log("launch overlay closed")
                    if (AUTO) {
                        delay((2500 * SLOW).toLong())
                        requestQuit = true
                    }
                }
            }

            val phase = overlay
            if (phase != null) {
                val oState = rememberWindowState(position = pos, size = size)
                DecoratedWindow(
                    onCloseRequest = {},
                    state = oState,
                    title = "overlay",
                    resizable = false,
                    focusable = false,
                    alwaysOnTop = true,
                    undecorated = true,
                    transparent = true,
                    clickThrough = true,
                    forceX11 = FORCE_X11,
                ) {
                    var firstFrame by remember { mutableStateOf(false) }
                    var t by remember { mutableStateOf(0f) }
                    val duration = (if (phase == Phase.Launch) 720f else 380f) * SLOW
                    LaunchedEffect(phase) {
                        log("overlay $phase window composed")
                        val start = withFrameNanos { it }
                        firstFrame = true
                        if (phase == Phase.Quit) {
                            mainVisible = false
                            log("quit: main hidden on overlay first frame")
                        }
                        while (true) {
                            val now = withFrameNanos { it }
                            t = ((now - start) / 1_000_000f / duration).coerceIn(0f, 1f)
                            if (t >= 1f) break
                        }
                        log("overlay $phase finished")
                        if (phase == Phase.Launch) mainVisible = true
                        else {
                            delay(30)
                            log("exit")
                            exitApplication()
                        }
                    }
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.type == PointerEventType.Press) log("OVERLAY GOT A CLICK (click-through failed)")
                                }
                            }
                        },
                    ) {
                        if (phase == Phase.Launch) LaunchScene(t) else QuitScene(t, picture)
                    }
                }
            }
        }
    }
}

@Composable
private fun RealContent(onQuit: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Real window", color = Color(0xFFDDE4E3), style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Box(Modifier.size(140.dp, 90.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A2121)))
            }
        }
        Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF004F4F)))
        Button(onClick = onQuit) { Text("Quit (overlay squash)") }
    }
}

private val EMPH = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val CRT = CubicBezierEasing(0.5f, 0f, 0.75f, 0f)

/** Teal rounded rect growing from 20% to 100%, then a surface-colored fill (the window stand-in). */
@Composable
private fun LaunchScene(t: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val e = EMPH.transform(t)
        val f = 0.2f + 0.8f * e
        val w = size.width * f
        val h = size.height * f
        val color = lerpColor(Color(0xFF4CDADA), Color(0xFF0E1514), e)
        drawRoundRect(color, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h), CornerRadius(12.dp.toPx()))
    }
}

/** Picture squashed to a line, then to a dot. */
@Composable
private fun QuitScene(t: Float, picture: ImageBitmap?) {
    Canvas(Modifier.fillMaxSize()) {
        val e = CRT.transform(t)
        val sy = if (e < 0.58f) 1f - (1f - 0.012f) * (e / 0.58f) else 0.012f
        val sx = if (e < 0.58f) 1f else if (e < 0.84f) 1f - (1f - 0.02f) * ((e - 0.58f) / 0.26f) else 0.02f * (1f - (e - 0.84f) / 0.16f)
        val alpha = if (e < 0.84f) 1f else 1f - (e - 0.84f) / 0.16f
        withTransform({ scale(sx, sy, center) }) {
            if (picture != null) {
                // The picture is the content below the title bar; the title bar strip is drawn as a band.
                val bar = size.height - picture.height
                drawRect(Color(0xFF1A2121), size = Size(size.width, bar.coerceAtLeast(0f)), alpha = alpha)
                drawImage(picture, dstOffset = IntOffset(0, bar.toInt().coerceAtLeast(0)), dstSize = IntSize(size.width.toInt(), picture.height), alpha = alpha)
            } else {
                drawRect(Color(0xFF0E1514), alpha = alpha)
            }
            drawRect(Color.White, alpha = (e * 0.8f).coerceAtMost(0.8f) * alpha)
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)
