package com.prismtest.scope

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService

/** 카메라가 실제로 적용한 값. 설정한 값이 아니라 CaptureResult 에서 읽은 값이다. */
data class AppliedCamera(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val focusDiopter: Float? = null,
    val aeMode: Int? = null,
    val afMode: Int? = null,
    val awbMode: Int? = null,
    val noiseReduction: Int? = null,
    val edgeMode: Int? = null,
) {
    val aeOff: Boolean get() = aeMode == CaptureResult.CONTROL_AE_MODE_OFF
    val afOff: Boolean get() = afMode == CaptureResult.CONTROL_AF_MODE_OFF
    val awbOff: Boolean get() = awbMode == CaptureResult.CONTROL_AWB_MODE_OFF
    val nrOff: Boolean get() = noiseReduction == CaptureResult.NOISE_REDUCTION_MODE_OFF
    val edgeOff: Boolean get() = edgeMode == CaptureResult.EDGE_MODE_OFF
}

/** 기기가 지원하는 수동 제어 범위. */
data class CameraCaps(
    val cameraId: String = "",
    val isoRange: Range<Int>? = null,
    val exposureRange: Range<Long>? = null,
    val minFocusDistance: Float = 0f,
    val hardwareLevel: Int = -1,
    val supportsManualSensor: Boolean = false,
    val supportsManualPostProcessing: Boolean = false,
) {
    val hardwareLevelName: String
        get() = when (hardwareLevel) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }

    /** 최소 초점거리(diopter)를 mm 로 환산. null 이면 고정초점 렌즈다. */
    val minFocusMm: Float?
        get() = if (minFocusDistance > 0f) 1000f / minFocusDistance else null
}

/** 수동 고정 설정값. */
data class ManualSettings(
    val locked: Boolean = true,
    val iso: Int = 50,
    val exposureNs: Long = 8_000_000L,
    val focusDiopter: Float = 4.0f,
)

/**
 * CameraX + Camera2Interop 로 노출·ISO·초점·화이트밸런스를 완전 수동 고정한다.
 *
 * 검사 앱의 대전제는 "촬영 조건 고정"이다. 자동 노출이 살아 있으면 프리즘을 바꿀
 * 때마다 밝기가 달라져 지수를 비교할 수 없다. 제조사 후처리(노이즈리덕션·샤프닝)도
 * 미세 결함 텍스처를 왜곡하므로 끈다.
 *
 * 설정한 키가 실제로 먹었는지 CaptureResult 로 반드시 검증한다. 기기에 따라
 * 조용히 무시되는 키가 있고, 그걸 모르고 쓰면 원인 불명의 값 편차를 겪는다.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
    private val analysisExecutor: ExecutorService,
) {
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    @Volatile var caps: CameraCaps = CameraCaps(); private set
    @Volatile var applied: AppliedCamera = AppliedCamera(); private set

    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        settings: ManualSettings,
        onFrame: (ImageProxy) -> Unit,
        onReady: (CameraCaps) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = try { future.get() } catch (e: Exception) { return@addListener }
            provider = p
            p.unbindAll()

            // 프리뷰와 분석의 화면비를 4:3 으로 맞춘다.
            // 두 스트림의 비가 다르면 ROI 오버레이 좌표가 어긋난다.
            val resSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val previewBuilder = Preview.Builder().setResolutionSelector(resSel)
            val ext = Camera2Interop.Extender(previewBuilder)
            applyManual(ext, settings)
            ext.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    applied = AppliedCamera(
                        iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                        exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                        focusDiopter = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                        aeMode = result.get(CaptureResult.CONTROL_AE_MODE),
                        afMode = result.get(CaptureResult.CONTROL_AF_MODE),
                        awbMode = result.get(CaptureResult.CONTROL_AWB_MODE),
                        noiseReduction = result.get(CaptureResult.NOISE_REDUCTION_MODE),
                        edgeMode = result.get(CaptureResult.EDGE_MODE),
                    )
                }
            })

            val preview = previewBuilder.build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resSel)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(analysisExecutor, ImageAnalysis.Analyzer { image -> onFrame(image) })

            val cam = p.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
            camera = cam
            caps = readCaps(cam)
            onReady(caps)
        }, ContextCompat.getMainExecutor(context))
    }

    /** 바인딩 후 수동값만 갱신한다. 재바인딩 없이 즉시 반영된다. */
    fun updateManual(s: ManualSettings) {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()
        if (s.locked) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureNs)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopter)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            b.setCaptureRequestOption(
                CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED
            )
            b.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        } else {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
        Camera2CameraControl.from(cam.cameraControl).captureRequestOptions = b.build()
    }

    private fun applyManual(ext: Camera2Interop.Extender<Preview>, s: ManualSettings) {
        if (!s.locked) return
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        ext.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
        ext.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureNs)
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        ext.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopter)
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
    }

    private fun readCaps(cam: Camera): CameraCaps {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val level = info.getCameraCharacteristic(
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
        ) ?: -1
        val avail = info.getCameraCharacteristic(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        ) ?: IntArray(0)
        return CameraCaps(
            cameraId = info.cameraId,
            isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDistance = info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ) ?: 0f,
            hardwareLevel = level,
            supportsManualSensor = avail.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ),
            supportsManualPostProcessing = avail.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
            ),
        )
    }

    fun unbind() {
        provider?.unbindAll()
        camera = null
    }
}
