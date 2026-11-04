package com.pocketcam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { PocketCamApp() } }
    }
}

private val PERMS = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}.toTypedArray()

@SuppressLint("MissingPermission")
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
fun PocketCamApp() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current

    var granted by remember { mutableStateOf(PERMS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> granted = r.values.all { it } }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(PERMS) }
    if (!granted) {
        Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) { Text("Camera and microphone permission needed", color = Color.White) }
        return
    }

    val previewView = remember { PreviewView(ctx) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var seconds by remember { mutableStateOf(0) }

    // Hardware ranges (null = device does not expose manual control)
    var isoRange by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    var expRange by remember { mutableStateOf<LongRange?>(null) }
    var minFocusDiopters by remember { mutableStateOf(0f) }

    var manualExposure by remember { mutableStateOf(false) }
    var iso by remember { mutableStateOf(100f) }
    var shutterDenom by remember { mutableStateOf(50f) }
    var manualFocus by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build()
            val vc = VideoCapture.withOutput(recorder)
            provider.unbindAll()
            val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, vc)
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                isoRange = it.lower.toFloat()..it.upper.toFloat(); iso = it.lower.toFloat()
            }
            info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { expRange = it.lower..it.upper }
            minFocusDiopters = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            camera = cam; videoCapture = vc
        }, ContextCompat.getMainExecutor(ctx))
    }

    // Push manual settings to the camera whenever they change
    LaunchedEffect(camera, manualExposure, iso, shutterDenom, manualFocus, focus) {
        val cam = camera ?: return@LaunchedEffect
        val b = CaptureRequestOptions.Builder()
        if (manualExposure && isoRange != null && expRange != null) {
            val ns = (1_000_000_000.0 / shutterDenom).toLong().coerceIn(expRange!!.first, expRange!!.last)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.toInt())
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
        }
        if (manualFocus && minFocusDiopters > 0f) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus * minFocusDiopters)
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
    }

    LaunchedEffect(recording) {
        seconds = 0
        while (recording != null) { delay(1000); seconds++ }
    }

    fun toggleRecord() {
        val active = recording
        if (active != null) { active.stop(); recording = null; return }
        val vc = videoCapture ?: return
        val name = "PocketCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PocketCam")
        }
        val opts = MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        recording = vc.output.prepareRecording(ctx, opts).withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { ev -> if (ev is VideoRecordEvent.Finalize) recording = null }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())

        Text(
            "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60),
            color = if (recording != null) Color.Red else Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
        )

        // Control panel (right side)
        Column(
            Modifier.align(Alignment.CenterStart).padding(8.dp).width(240.dp)
                .background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(12.dp)
        ) {
            if (isoRange != null && expRange != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Manual exposure", color = Color.White, modifier = Modifier.weight(1f)); Switch(manualExposure, { manualExposure = it })
                }
                if (manualExposure) {
                    Text("ISO ${iso.toInt()}", color = Color.White)
                    Slider(iso, { iso = it }, valueRange = isoRange!!)
                    Text("Shutter 1/${shutterDenom.toInt()}", color = Color.White)
                    Slider(shutterDenom, { shutterDenom = it }, valueRange = 24f..2000f)
                }
            } else Text("Manual exposure not supported", color = Color.Gray)

            if (minFocusDiopters > 0f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Manual focus", color = Color.White, modifier = Modifier.weight(1f)); Switch(manualFocus, { manualFocus = it })
                }
                if (manualFocus) Slider(focus, { focus = it }, valueRange = 0f..1f)
            } else Text("Manual focus not supported", color = Color.Gray)
        }

        // Record button
        Box(
            Modifier.align(Alignment.CenterEnd).padding(24.dp).size(72.dp)
                .background(Color.White, CircleShape).padding(6.dp)
        ) {
            Button(
                onClick = { toggleRecord() },
                shape = if (recording != null) RoundedCornerShape(8.dp) else CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier.fillMaxSize()
            ) {}
        }
    }
}
