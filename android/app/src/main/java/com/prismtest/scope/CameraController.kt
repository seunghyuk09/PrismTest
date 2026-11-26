package com.prismtest.scope

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Size
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.UUID
import kotlin.math.abs

/** 카메라가 실제로 적용한 값. 설정한 값이 아니라 CaptureResult 에서 읽은 값이다. */
data class AppliedCamera(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val focusDiopter: Float? = null,
    val aeMode: Int? = null,
    val afMode: Int? = null,
    val afState: Int? = null,
    val lensMoving: Boolean = false,
    val awbMode: Int? = null,
    val awbLocked: Boolean = false,
    val noiseReduction: Int? = null,
    val edgeMode: Int? = null,
) {
    val aeOff: Boolean get() = aeMode == CaptureResult.CONTROL_AE_MODE_OFF
    val afOff: Boolean get() = afMode == CaptureResult.CONTROL_AF_MODE_OFF
    val awbOff: Boolean get() = awbMode == CaptureResult.CONTROL_AWB_MODE_OFF
    val nrOff: Boolean get() = noiseReduction == CaptureResult.NOISE_REDUCTION_MODE_OFF
    val edgeOff: Boolean get() = edgeMode == CaptureResult.EDGE_MODE_OFF

    /** A passive AF result or an unsuccessful AF lock is not a held inspection focus. */
    fun focusHeld(settings: ManualSettings, caps: CameraCaps): Boolean {
        if (!settings.locked || lensMoving) return false
        return when {
            settings.focusControl == FocusControl.MANUAL -> {
                val requested = settings.focusDiopter ?: return false
                val actual = focusDiopter ?: return false
                afOff && requested.isFinite() && actual.isFinite() && requested >= 0f &&
                    abs(actual - requested) <= maxOf(.05f, requested * .02f)
            }
            caps.supportsAutoFocus -> afMode == (if (settings.focusControl == FocusControl.CURRENT_LOCK)
                CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CaptureResult.CONTROL_AF_MODE_AUTO) &&
                    afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
            else -> afOff // A genuinely fixed-focus camera has no AF operation to wait for.
        }
    }
}

/** 기기가 지원하는 수동 제어 범위. */
data class CameraCaps(
    val cameraId: String = "",
    val isoRange: Range<Int>? = null,
    val exposureRange: Range<Long>? = null,
    val minFocusDistance: Float = 0f,
    val zoomRange: Range<Float>? = null,
    /** 분석 버퍼의 실제 해상도. 결함 크기가 몇 화소로 잡히는지를 결정한다. */
    val analysisWidth: Int = 0,
    val analysisHeight: Int = 0,
    val hardwareLevel: Int = -1,
    val supportsManualSensor: Boolean = false,
    val supportsManualPostProcessing: Boolean = false,
    val supportsAutoFocus: Boolean = false,
    val supportsContinuousFocus: Boolean = false,
    val boundQuality: CaptureQuality = CaptureQuality.FHD,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
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
    /**
     * 기본값은 자동이다. 잠긴 상태로 시작하면 조명이 갖춰지지 않은 곳에서
     * 새까만 화면만 보이고, 그게 앱이 고장난 것처럼 보인다.
     * 화면이 잘 보이는 상태에서 [CameraController.lockAt] 으로 잠근다.
     */
    val locked: Boolean = false,
    val iso: Int = 100,
    val exposureNs: Long = 16_000_000L,
    /** A missing distance is unknown; zero is a real infinity reading. Never substitute 4 D. */
    val focusDiopter: Float? = null,
    val focusControl: FocusControl = FocusControl.CURRENT_LOCK,
    val focusLockId: String = "",
    /**
     * 광학 배율. 프리즘이 화면에서 차지하는 비율을 결정한다.
     *
     * 폰 광각은 화각이 넓어 초점이 맞는 최단 거리에서도 프리즘이 화면의 10%
     * 남짓밖에 안 된다. 그만큼 결함에 배정되는 화소가 줄어든다. 렌즈를 더
     * 들이대는 대신 배율을 올리는 편이 초점 여유를 지키면서 화소를 벌 수 있다.
     */
    val zoom: Float = 2.0f,
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
    @Volatile private var bindingGeneration = 0
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var focusRequest = 0
    private var focusMeteringActive = false
    private var currentFocusLockActive = false
    private var captureOptionsGeneration = 0
    private var requestedZoom: Float? = null
    private var pendingFocus: ((ManualSettings?, String?) -> Unit)? = null

    @Volatile var caps: CameraCaps = CameraCaps(); private set
    @Volatile var applied: AppliedCamera = AppliedCamera(); private set

    // 자동 상태에서 ISP 가 쓰던 색 보정값. 수동으로 잠글 때 이 값을 그대로 넘겨야
    // 색이 유지된다. 이걸 안 넘기고 AWB 만 끄면 센서 원본이 나와 초록빛이 돈다.
    @Volatile private var lastGains: RggbChannelVector? = null
    @Volatile private var lastTransform: ColorSpaceTransform? = null

    val whiteBalanceProfile: String
        get() = lastGains?.let {
            "%.3f,%.3f,%.3f,%.3f".format(java.util.Locale.US,
                it.red, it.greenEven, it.greenOdd, it.blue)
        } ?: "awb-locked"

    @android.annotation.SuppressLint("RestrictedApi")
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        settings: ManualSettings,
        onFrame: (ImageProxy) -> Unit,
        onReady: (CameraCaps) -> Unit,
        quality: CaptureQuality = CaptureQuality.FHD,
        onError: (String) -> Unit = {},
        previewQuality: CaptureQuality = quality,
    ) {
        cancelPendingFocus("카메라가 다시 연결됩니다. 초점을 다시 고정하세요")
        val generation = ++bindingGeneration
        camera = null
        applied = AppliedCamera()
        lastGains = null
        lastTransform = null
        focusMeteringActive = false
        currentFocusLockActive = false
        captureOptionsGeneration++
        requestedZoom = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (generation != bindingGeneration) return@addListener
            val p = try { future.get() } catch (e: Exception) {
                onError("카메라를 열지 못했습니다: ${e.message}"); return@addListener
            }
            provider = p
            p.unbindAll()

            // Request the selected quality for both streams. The shared viewport, not
            // a bitmap enlargement, aligns live analysis and annotations.
            val resSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(previewQuality.target.width, previewQuality.target.height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                .setResolutionFilter { sizes, _ ->
                    val allowed = previewQuality.bounded(sizes.map { FrameSize(it.width, it.height) }).toSet()
                    sizes.filter { FrameSize(it.width, it.height) in allowed }
                }
                .build()

            // CameraX 1.4 otherwise retains its default 1080p preview ceiling.
            // These builder settings are pinned to that dependency and verified in CI.
            val previewBuilder = Preview.Builder().setResolutionSelector(resSel)
                .setMaxResolution(Size(CaptureQuality.UHD.target.width, CaptureQuality.UHD.target.height))
                .setHighResolutionDisabled(false)
                .setDynamicRange(androidx.camera.core.DynamicRange.SDR)
            val ext = Camera2Interop.Extender(previewBuilder)
            ext.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (generation != bindingGeneration) return
                    applied = AppliedCamera(
                        iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                        exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                        focusDiopter = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                        aeMode = result.get(CaptureResult.CONTROL_AE_MODE),
                        afMode = result.get(CaptureResult.CONTROL_AF_MODE),
                        afState = result.get(CaptureResult.CONTROL_AF_STATE),
                        lensMoving = result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_MOVING,
                        awbMode = result.get(CaptureResult.CONTROL_AWB_MODE),
                        awbLocked = result.get(CaptureResult.CONTROL_AWB_LOCK) == true,
                        noiseReduction = result.get(CaptureResult.NOISE_REDUCTION_MODE),
                        edgeMode = result.get(CaptureResult.EDGE_MODE),
                    )
                    result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { lastGains = it }
                    result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { lastTransform = it }
                }
            })

            val preview = previewBuilder.build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            // Detail requests more real source pixels. No bitmap enlargement/sharpening is used
            // to manufacture evidence. Actual negotiated/cropped dimensions are shown in the UI.
            val analysisRes = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(quality.target.width, quality.target.height),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .setAllowedResolutionMode(if (quality != CaptureQuality.HD)
                    ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                    else ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                .setResolutionFilter { sizes, _ ->
                    val allowed = quality.bounded(sizes.map { FrameSize(it.width, it.height) }).toSet()
                    sizes.filter { FrameSize(it.width, it.height) in allowed }
                }
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisRes)
                .setMaxResolution(Size(CaptureQuality.UHD.target.width, CaptureQuality.UHD.target.height))
                .setHighResolutionDisabled(false)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(analysisExecutor, ImageAnalysis.Analyzer { image ->
                if (generation == bindingGeneration) onFrame(image) else image.close()
            })

            previewView.doOnLayout {
                if (generation != bindingGeneration) return@doOnLayout
                // Both streams must use the same sensor crop. Matching nominal aspect ratios
                // alone does not align annotations on devices that choose different resolutions.
                val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis)
                previewView.viewPort?.let { group.setViewPort(it) }
                try {
                    val cam = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
                    camera = cam
                    val previewSize = preview.resolutionInfo?.resolution
                    caps = readCaps(cam, analysis).copy(boundQuality = quality,
                        previewWidth = previewSize?.width ?: 0, previewHeight = previewSize?.height ?: 0)
                    updateManual(settings)
                    onReady(caps)
                } catch (e: Exception) {
                    // Prefer retaining full-resolution evidence while lowering the preview
                    // if the phone cannot run two high-resolution streams together.
                    val lowerPreview = previewQuality.lower()
                    val lowerAnalysis = quality.lower()
                    if (lowerPreview != null) {
                        bind(lifecycleOwner, previewView, settings, onFrame, onReady, quality, onError, lowerPreview)
                    } else if (lowerAnalysis != null) {
                        bind(lifecycleOwner, previewView, settings, onFrame, onReady, lowerAnalysis, onError, lowerAnalysis)
                    } else onError("카메라 연결 실패: ${e.message}")
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** 바인딩 후 수동값만 갱신한다. 재바인딩 없이 즉시 반영된다. */
    fun updateManual(s: ManualSettings) {
        val cam = camera ?: return
        val cancelCurrent = currentFocusLockActive && !s.locked
        if (!s.locked || s.focusControl == FocusControl.MANUAL) {
            cancelPendingFocus(null)
            if (focusMeteringActive) cam.cameraControl.cancelFocusAndMetering()
            focusMeteringActive = false
            currentFocusLockActive = false
        }
        writeSettings(s, if (cancelCurrent) CaptureRequest.CONTROL_AF_TRIGGER_CANCEL else null)
    }

    /** A transient AF trigger is removed as soon as a capture acknowledges it. The generation
     * guard prevents a delayed completion from overwriting a newer lock or manual adjustment. */
    private fun writeSettings(s: ManualSettings, afTrigger: Int? = null) {
        val cam = camera ?: return
        val command = ++captureOptionsGeneration
        val b = CaptureRequestOptions.Builder()
        if (s.locked) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureNs)
            // An autofocus lock belongs to CameraX's AF state machine. Do not override its
            // AF_MODE_AUTO or replay a reported (possibly uncalibrated) lens distance.
            if (s.focusControl == FocusControl.MANUAL) {
                val distance = requireNotNull(s.focusDiopter)
                require(distance.isFinite() && distance in 0f..caps.minFocusDistance)
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
            }
            applyWhiteBalance(b)
        } else {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
        if (!caps.supportsAutoFocus) {
            // Some fixed lenses advertise AUTO modes despite a 0 D minimum focus distance.
            // They have no autofocus movement or FOCUSED_LOCKED state to wait for.
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        } else if ((s.locked && s.focusControl == FocusControl.CURRENT_LOCK) || afTrigger != null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger ?: CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        }
        // Keep processing consistent before/after locking. Turning sharpening off only
        // at lock time made the view look softer even if the lens had not moved.
        b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        b.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        b.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        b.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        val configured = Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        if (afTrigger != null) configured.addListener({
            if (command != captureOptionsGeneration || camera !== cam) return@addListener
            try {
                configured.get()
                writeSettings(s)
            } catch (error: Exception) { Log.w("PrismFocus", "AF trigger not acknowledged", error) }
        }, ContextCompat.getMainExecutor(context))

        // 배율은 CaptureRequest 가 아니라 CameraControl 로 건다. 프리뷰와 분석
        // 버퍼에 함께 걸리므로 화면에서 본 크기가 그대로 측정 크기가 된다.
        val zr = caps.zoomRange
        val zoom = if (zr != null) s.zoom.coerceIn(zr.lower, zr.upper) else s.zoom
        // Reapplying an unchanged zoom can disturb metering on logical multi-camera devices.
        if (requestedZoom != zoom) {
            requestedZoom = zoom
            cam.cameraControl.setZoomRatio(zoom)
        }
    }

    /** Freeze a successfully focused continuous-AF lens without starting another focus sweep.
     * Camera2 defines PASSIVE_FOCUSED + AF_TRIGGER_START as FOCUSED_LOCKED in this mode. */
    fun holdCurrent(point: MeteringPoint, base: ManualSettings,
                    onResult: (ManualSettings?, String?) -> Unit) {
        if (caps.supportsAutoFocus && !caps.supportsContinuousFocus) {
            lockAt(point, base, onResult)
            return
        }
        val cam = camera ?: run { onResult(null, "카메라 연결을 기다리세요"); return }
        updateManual(base.copy(locked = false))
        val token = ++focusRequest
        val generation = bindingGeneration
        pendingFocus = onResult
        val lockId = UUID.randomUUID().toString()
        var beforeTrigger: AppliedCamera? = null
        fun active() = token == focusRequest && generation == bindingGeneration && camera === cam
        Log.i("PrismFocus", "Current hold requested: mode=${applied.afMode} state=${applied.afState} distance=${applied.focusDiopter}")
        fun finish(snapshot: ManualSettings?, error: String?) {
            if (!active()) return
            val callback = pendingFocus ?: return
            pendingFocus = null
            focusRequest++
            Log.i("PrismFocus", "Current hold ${if (snapshot != null) "confirmed" else "failed: $error"}: " +
                "mode=${applied.afMode} state=${applied.afState} moving=${applied.lensMoving} distance=${applied.focusDiopter}")
            if (snapshot == null) updateManual(base.copy(locked = false))
            callback(snapshot, error)
        }
        fun confirm() {
            if (!active()) return
            val reading = applied
            if (!caps.supportsAutoFocus || (beforeTrigger != null && reading !== beforeTrigger)) {
                val snapshot = FocusLockPolicy.snapshot(base, reading, caps, lockId, FocusControl.CURRENT_LOCK)
                if (snapshot != null) { finish(snapshot, null); return }
            }
            if (beforeTrigger == null && caps.supportsAutoFocus && !reading.lensMoving &&
                reading.afMode == CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE &&
                reading.afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED) {
                beforeTrigger = reading
                currentFocusLockActive = true
                writeSettings(base.copy(locked = false), CaptureRequest.CONTROL_AF_TRIGGER_START)
            }
            mainHandler.postDelayed({ confirm() }, 50)
        }
        mainHandler.postDelayed({ finish(null,
            "현재 초점을 확인하지 못했습니다. 프리즘이 선명하게 보이도록 거리를 조절하거나 초점 다시 맞춤을 누르세요") }, 8_000)
        confirm()
    }

    /**
     * 화이트밸런스를 고정한다.
     *
     * AWB 를 끄기만 하면 색 보정이 통째로 빠져 센서 원본(초록 우세)이 그대로 나온다.
     * 자동 상태에서 ISP 가 쓰던 gains/transform 을 그대로 넘겨야 색이 유지된다.
     * 값을 아직 못 읽었으면 AWB 자동 + 잠금으로 대체한다.
     */
    private fun applyWhiteBalance(b: CaptureRequestOptions.Builder) {
        val g = lastGains
        val t = lastTransform
        if (g != null && t != null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, g)
            b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, t)
        } else {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
    }

    /** Focus at the inspection point and leave the hardware AF lock engaged indefinitely.
     * Only exposure/WB are copied after a successful, stationary AF result. No explicit
     * lens-distance request is sent. Failure leaves automatic controls available for retry. */
    fun lockAt(point: MeteringPoint, base: ManualSettings,
               onResult: (ManualSettings?, String?) -> Unit) {
        val cam = camera ?: run { onResult(null, "카메라 연결을 기다리세요"); return }
        // Switching from continuous to single AUTO resets its old lock. Clear interop AF
        // overrides before CameraX submits its own AF trigger; do not pin a CANCEL over it.
        currentFocusLockActive = false
        updateManual(base.copy(locked = false))
        val token = ++focusRequest
        val generation = bindingGeneration
        pendingFocus = onResult
        var focusResultAtCompletion: AppliedCamera? = null
        val lockId = UUID.randomUUID().toString()
        Log.i("PrismFocus", "Lock requested: autofocus=${caps.supportsAutoFocus} minimum=${caps.minFocusDistance} " +
            "point=${point.x},${point.y} mode=${applied.afMode} state=${applied.afState} distance=${applied.focusDiopter}")
        fun active() = token == focusRequest && generation == bindingGeneration && camera === cam
        fun finish(snapshot: ManualSettings?, error: String?) {
            if (!active()) return
            val callback = pendingFocus ?: return
            pendingFocus = null
            focusRequest++
            if (snapshot == null) {
                Log.w("PrismFocus", "Lock failed: $error; autofocus=${caps.supportsAutoFocus} " +
                    "minimum=${caps.minFocusDistance} mode=${applied.afMode} state=${applied.afState} " +
                    "moving=${applied.lensMoving} distance=${applied.focusDiopter}")
                if (focusMeteringActive) cam.cameraControl.cancelFocusAndMetering()
                focusMeteringActive = false
            } else {
                Log.i("PrismFocus", "Hardware lock: mode=${applied.afMode} state=${applied.afState} " +
                    "moving=${applied.lensMoving} distance=${applied.focusDiopter} (no lens-distance replay)")
            }
            callback(snapshot, error)
        }
        fun confirm() {
            if (!active()) return
            // The CameraX future and interop callback run on different executors. Require a
            // fresh result after the future, not a cached reading from the preceding AF lock.
            val reading = applied
            val snapshot = if (reading === focusResultAtCompletion) null else
                FocusLockPolicy.snapshot(base, reading, caps, lockId)
            if (snapshot != null) finish(snapshot, null)
            else mainHandler.postDelayed({ confirm() }, 50)
        }
        mainHandler.postDelayed({
            finish(null, "초점을 고정하지 못했습니다. 프리즘 모서리를 영역 중앙에 놓고 거리를 조절해 다시 맞추세요")
        }, 8_000)
        if (!caps.supportsAutoFocus) {
            confirm()
            return
        }
        if (point.x !in 0f..1f || point.y !in 0f..1f) {
            finish(null, "프리뷰가 아직 준비되지 않았습니다. 잠시 뒤 다시 고정하세요")
            return
        }
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .disableAutoCancel().build()
        if (!cam.cameraInfo.isFocusMeteringSupported(action)) {
            finish(null, "이 카메라는 영역 초점 잠금을 지원하지 않습니다. 다른 카메라를 사용하세요")
            return
        }
        focusMeteringActive = true
        val focus = cam.cameraControl.startFocusAndMetering(action)
        focus.addListener({
            if (!active()) return@addListener
            try {
                if (focus.get().isFocusSuccessful) {
                    focusResultAtCompletion = applied
                    confirm()
                }
                else finish(null, "초점이 잡히지 않았습니다. 프리즘 모서리를 영역 중앙에 놓고 거리를 조절해 다시 맞추세요")
            } catch (error: Exception) {
                Log.w("PrismFocus", "AF request interrupted", error)
                finish(null, "초점 맞추기가 중단됐습니다. 다시 고정하세요")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun cancelPendingFocus(message: String?) {
        focusRequest++
        val callback = pendingFocus
        pendingFocus = null
        callback?.invoke(null, message)
    }

    private fun readCaps(cam: Camera, analysis: ImageAnalysis): CameraCaps {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val zoom = cam.cameraInfo.zoomState.value
        val res = analysis.resolutionInfo?.resolution
        val level = info.getCameraCharacteristic(
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
        ) ?: -1
        val avail = info.getCameraCharacteristic(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        ) ?: IntArray(0)
        val minimumDistance = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        return CameraCaps(
            cameraId = info.cameraId,
            isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDistance = minimumDistance ?: 0f,
            zoomRange = zoom?.let { Range(it.minZoomRatio, it.maxZoomRatio) },
            analysisWidth = res?.width ?: 0,
            analysisHeight = res?.height ?: 0,
            hardwareLevel = level,
            supportsManualSensor = avail.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ),
            supportsManualPostProcessing = avail.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
            ),
            supportsAutoFocus = FocusLockPolicy.supportsAutoFocus(
                info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) == true, minimumDistance),
            supportsContinuousFocus = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true,
        )
    }

    fun unbind() {
        cancelPendingFocus(null)
        captureOptionsGeneration++
        currentFocusLockActive = false
        bindingGeneration++
        provider?.unbindAll()
        camera = null
    }
}
