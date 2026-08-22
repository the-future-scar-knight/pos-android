package com.portionspot.pos.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.Result
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.util.Size

/**
 * Live camera barcode scanner. CameraX feeds frames to an [ImageAnalysis] analyzer
 * that decodes the Y (luminance) plane with zxing's [MultiFormatReader] — no Play
 * Services / ML Kit. The first successful decode fires [onResult] (once) and the
 * dialog closes. Handheld POS devices with a hardware scanner can instead type the
 * barcode straight into the catalogue search, so this is the camera fallback.
 */
@Composable
fun BarcodeScannerDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    DisposableEffect(Unit) {
        if (!granted) permLauncher.launch(Manifest.permission.CAMERA)
        onDispose { }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Scan barcode") },
        text = {
            if (granted) {
                Column(Modifier.fillMaxWidth()) {
                    CameraPreview(
                        onDecoded = { code -> onResult(code) },
                        modifier = Modifier.fillMaxWidth().height(280.dp)
                    )
                    // Says the one thing no amount of focus code can fix: a phone camera has
                    // a minimum focus distance of roughly a hand's width, and inside it the
                    // lens physically cannot resolve. Without this the cashier's instinct on
                    // a stubborn code is to move CLOSER, which is the one move that
                    // guarantees it will never read.
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Hold about a hand's width away and keep the code inside the box. " +
                            "Tap the picture to refocus.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Camera permission is needed to scan.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    )
}

@Composable
private fun CameraPreview(
    onDecoded: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // Latches after the first hit so onDecoded fires exactly once.
    val decoded = remember { AtomicBoolean(false) }
    // Which way up the NEXT frame is tried. See [decodeFrame]: one orientation per frame
    // rather than both, because a miss used to cost two full decodes of the same picture.
    val turnedNext = remember { AtomicBoolean(false) }
    // The last code seen but not yet trusted - see [decodeFrame]'s confirmation rule.
    val lastSeen = remember { AtomicReference<String?>(null) }
    // The bound camera and the preview surface, kept so focus can be driven after binding.
    // Null until CameraX finishes binding, which is why every use below is null-safe rather
    // than assumed — the dialog can be dismissed mid-bind.
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewRef by remember { mutableStateOf<PreviewView?>(null) }
    val reader = remember {
        MultiFormatReader().apply {
            // Zimbabwe retail runs on 1D product barcodes, not QR. Naming the exact
            // formats we expect makes zxing decode faster and far more reliably than
            // a blind "try every symbology" pass (which is why QR seemed to be all it
            // caught). QR/Data Matrix stay in the list so a QR still scans if present.
            setHints(
                mapOf(
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.POSSIBLE_FORMATS to listOf(
                        BarcodeFormat.EAN_13,
                        BarcodeFormat.EAN_8,
                        BarcodeFormat.UPC_A,
                        BarcodeFormat.UPC_E,
                        BarcodeFormat.CODE_128,
                        BarcodeFormat.CODE_39,
                        BarcodeFormat.CODE_93,
                        BarcodeFormat.ITF,
                        BarcodeFormat.CODABAR,
                        BarcodeFormat.QR_CODE,
                        BarcodeFormat.DATA_MATRIX,
                    ),
                )
            )
        }
    }

    // Clip the camera surface to a rounded card so it sits inside the dialog instead
    // of bleeding to square corners over the dialog's rounded edge (prompt §4).
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black)
    ) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            // Manual override. The automatic drive above aims at the band, which is right
            // almost always; this is for the time it is not — a label lit from behind, or a
            // barcode the cashier wants read from the edge of the frame.
            .pointerInput(camera, previewRef) {
                detectTapGestures { offset ->
                    val cam = camera ?: return@detectTapGestures
                    val view = previewRef ?: return@detectTapGestures
                    runCatching {
                        val point = view.meteringPointFactory.createPoint(offset.x, offset.y)
                        cam.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(
                                point,
                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                        )
                    }
                }
            },
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                // COMPATIBLE (TextureView) avoids the SurfaceView punch-through that
                // can leave the preview mis-seated in a dialog; FILL_CENTER fills the
                // rounded box without letterbox gaps while keeping the frame upright.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }.also { previewRef = it }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // CAP THE FRAME. Left to itself CameraX hands over whatever the sensor
                    // likes - 4000 px wide on a modern phone - and every one of those pixels
                    // is copied and thresholded before a barcode is even looked for. 1280
                    // resolves a shelf label from a hand's distance with room to spare, and
                    // the frames arrive several times faster, which is what actually makes a
                    // scanner feel quick: more attempts per second, not a better attempt.
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                )
                            )
                            .build()
                    )
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    decodeFrame(proxy, reader, decoded, turnedNext, lastSeen, onDecoded)
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }.onSuccess { camera = it }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
    // THE BAND THE APP IS ACTUALLY READING. Only the middle of the frame is decoded now,
    // and a scanner that quietly ignores three-quarters of what the camera shows is a
    // scanner people wave around wondering why it will not catch. Drawn to the same
    // fraction as [BAND], so aiming inside the guide is aiming at the decoder.
    Box(
        Modifier
            .align(Alignment.Center)
            .fillMaxWidth()
            .fillMaxHeight(BAND.toFloat())
            .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
    )
    }

    /**
     * Focus ON THE BAND, and keep asking.
     *
     * ★ WHY A BARCODE UP CLOSE WAS THE ONE THAT WOULD NOT READ. Left alone, CameraX runs
     * continuous autofocus metered across the whole frame, so the lens settles on whatever
     * dominates it — the shelf, the counter, the cashier's hand — rather than on the label.
     * At arm's length that costs nothing: the depth of field is deep enough that the barcode
     * is sharp anyway. Up close the depth of field collapses to a couple of centimetres, so
     * focusing on the background means the barcode is properly, unreadably blurred. That is
     * exactly the complaint — fast at a distance, hopeless near.
     *
     * So the metering point is the CENTRE OF THE DECODE BAND, the same rectangle drawn on
     * screen and the only part of the frame anything reads.
     *
     * ★ AND IT REPEATS. One `startFocusAndMetering` is a single sweep: it converges, the
     * action auto-cancels, and the lens is then free to drift on the next scene change —
     * which is guaranteed here, because the person is moving the phone toward the label
     * while it focuses. Re-driving it every second and a half means the lens keeps chasing
     * the barcode the whole time someone is lining it up, instead of settling once on
     * whatever happened to be in view at the instant the dialog opened.
     *
     * Stops as soon as a code is accepted — the dialog is closing and a focus sweep against
     * a dying preview is a wasted round-trip that can outlive the surface it metered.
     */
    LaunchedEffect(camera, previewRef) {
        val cam = camera ?: return@LaunchedEffect
        val view = previewRef ?: return@LaunchedEffect
        while (!decoded.get()) {
            val w = view.width.toFloat()
            val h = view.height.toFloat()
            if (w > 0f && h > 0f) {
                runCatching {
                    val point = view.meteringPointFactory.createPoint(w / 2f, h / 2f)
                    cam.cameraControl.startFocusAndMetering(
                        FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).setAutoCancelDuration(2, TimeUnit.SECONDS).build()
                    )
                }
            }
            kotlinx.coroutines.delay(1_500)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
}

private val mainHandler = Handler(Looper.getMainLooper())

/**
 * One camera frame's LUMINANCE, tightly packed — [width] × [height] bytes, no padding.
 *
 * ★ THE CAMERA'S BUFFER IS NOT TIGHTLY PACKED and treating it as though it were is half of
 * why this scanner never fired. Every Y plane arrives padded to a `rowStride` the hardware
 * chose, so a 1280-wide frame can be handed over 1536 bytes per row. The buffer was being
 * passed to zxing whole with the stride declared as the image width, which means every row
 * after the first was read at an offset — the picture zxing searched was a sheared version
 * of the one on screen, and a sheared barcode does not decode. On top of that the final row
 * is often short, so the read walked off the end and threw, and the catch below reported it
 * as "no barcode in this frame" — forever, silently, on every frame.
 */
private class Luma(val data: ByteArray, val width: Int, val height: Int)

/** Copy the Y plane out row by row, honouring the strides rather than assuming them. */
private fun ImageProxy.toLuma(): Luma? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    if (width <= 0 || height <= 0) return null
    val out = ByteArray(width * height)
    if (rowStride == width && pixelStride == 1) {
        buffer.get(out, 0, minOf(out.size, buffer.remaining()))
        return Luma(out, width, height)
    }
    val row = ByteArray(rowStride)
    var pos = 0
    for (r in 0 until height) {
        val at = r * rowStride
        if (at >= buffer.limit()) break
        // Cast to Buffer deliberately: ByteBuffer's own covariant `position(int)` only
        // exists from API 24, and binding to it would crash this app's minSdk-23 floor
        // with a NoSuchMethodError the moment someone scanned on an older phone.
        (buffer as java.nio.Buffer).position(at)
        val len = minOf(rowStride, buffer.remaining())
        buffer.get(row, 0, len)
        if (pixelStride == 1) {
            System.arraycopy(row, 0, out, pos, minOf(width, len))
        } else {
            for (c in 0 until width) {
                val src = c * pixelStride
                if (src >= len) break
                out[pos + c] = row[src]
            }
        }
        pos += width
    }
    return Luma(out, width, height)
}

/** The same frame turned a quarter turn. Dimensions swap; nothing is mirrored. */
private fun Luma.turned(): Luma {
    val out = ByteArray(data.size)
    var i = 0
    for (x in 0 until width) {
        for (y in height - 1 downTo 0) out[i++] = data[y * width + x]
    }
    return Luma(out, height, width)
}

/**
 * How much of the frame is read: the middle 42%, across the whole of the other axis.
 *
 * A barcode is aimed at, not stumbled upon. Decoding the full frame spends most of its
 * effort on the shelf, the cashier's hand and the floor, and every one of those pixels
 * is thresholded before the reader gives up on them. The band is drawn on screen so the
 * person holding the phone is aiming at the same rectangle the decoder is reading.
 */
private const val BAND = 0.42

/** Copy a sub-rectangle out. Cheaper than it looks: rows are contiguous. */
private fun Luma.crop(left: Int, top: Int, w: Int, h: Int): Luma {
    val out = ByteArray(w * h)
    for (y in 0 until h) {
        System.arraycopy(data, (top + y) * width + left, out, y * w, w)
    }
    return Luma(out, w, h)
}

/** Decode this frame, or null when there is no barcode in it. */
private fun Luma.decode(reader: MultiFormatReader): Result? =
    try {
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }

/**
 * The symbologies that carry their own check digit.
 *
 * EAN and UPC end in a digit computed from the others, so a misread fails arithmetic and
 * is thrown away by the reader itself. Code 39, Code 128, ITF and Codabar as commonly
 * printed carry no such thing: a smeared or half-lit scan can decode CLEANLY to the wrong
 * number, and on a till the wrong number is the wrong product at the wrong price. Those
 * are made to say the same thing twice before this believes them.
 */
private val SELF_CHECKING = setOf(
    BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
)

/**
 * Decode a frame — ONE orientation per frame, in a band, confirmed if it cannot check itself.
 *
 * ★ WHY NOT BOTH ORIENTATIONS EVERY FRAME. Analysis frames come out in the sensor's
 * orientation, a quarter turn from what the cashier sees, and a 1D reader only scans across
 * — so both ways up have to be tried. Trying both on every frame means every MISS costs two
 * full decodes, and misses are the common case while somebody is still lining the phone up.
 * Alternating instead costs one decode per frame and still covers both ways inside ~66ms,
 * which is faster in the only sense that matters: attempts per second.
 *
 * The band is cropped BEFORE the turn, so the rotation only ever copies the 42% that is
 * about to be read rather than the whole picture.
 */
private fun decodeFrame(
    proxy: ImageProxy,
    reader: MultiFormatReader,
    decoded: AtomicBoolean,
    turnedNext: AtomicBoolean,
    lastSeen: AtomicReference<String?>,
    onDecoded: (String) -> Unit
) {
    try {
        if (decoded.get()) return
        val luma = proxy.toLuma() ?: return
        val turned = turnedNext.getAndSet(!turnedNext.get())
        val band =
            if (turned) {
                // A vertical slice, turned a quarter — the same band, for a frame whose
                // rows run the other way.
                val w = (luma.width * BAND).toInt().coerceAtLeast(1)
                if (w >= luma.width) luma.turned()
                else luma.crop((luma.width - w) / 2, 0, w, luma.height).turned()
            } else {
                val h = (luma.height * BAND).toInt().coerceAtLeast(1)
                if (h >= luma.height) luma
                else luma.crop(0, (luma.height - h) / 2, luma.width, h)
            }
        val result = band.decode(reader) ?: return
        val text = result.text
        if (text.isNullOrBlank()) return
        // Self-checking symbologies are believed at once; the rest have to repeat themselves.
        if (result.barcodeFormat !in SELF_CHECKING && lastSeen.getAndSet(text) != text) return
        if (decoded.compareAndSet(false, true)) {
            // Hop to the main thread: onDecoded closes the dialog (composition).
            mainHandler.post { onDecoded(text) }
        }
    } catch (_: Exception) {
        // Nothing usable in this frame — keep scanning.
    } finally {
        proxy.close()
    }
}
