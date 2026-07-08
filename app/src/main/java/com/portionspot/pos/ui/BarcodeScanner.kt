package com.portionspot.pos.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

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
                CameraPreview(
                    onDecoded = { code -> onResult(code) },
                    modifier = Modifier.fillMaxWidth().height(280.dp)
                )
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
    val decoded = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
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
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                // COMPATIBLE (TextureView) avoids the SurfaceView punch-through that
                // can leave the preview mis-seated in a dialog; FILL_CENTER fills the
                // rounded box without letterbox gaps while keeping the frame upright.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    decodeFrame(proxy, reader, decoded, onDecoded)
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
    }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
}

private val mainHandler = Handler(Looper.getMainLooper())

private fun decodeFrame(
    proxy: ImageProxy,
    reader: MultiFormatReader,
    decoded: java.util.concurrent.atomic.AtomicBoolean,
    onDecoded: (String) -> Unit
) {
    try {
        if (decoded.get()) return
        val plane = proxy.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val rowStride = plane.rowStride
        val source = PlanarYUVLuminanceSource(
            data, rowStride, proxy.height, 0, 0, proxy.width, proxy.height, false
        )
        val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
        val text = result.text
        if (!text.isNullOrBlank() && decoded.compareAndSet(false, true)) {
            // Hop to the main thread: onDecoded closes the dialog (composition).
            mainHandler.post { onDecoded(text) }
        }
    } catch (_: Exception) {
        // No barcode in this frame (NotFoundException) — keep scanning.
    } finally {
        reader.reset()
        proxy.close()
    }
}
