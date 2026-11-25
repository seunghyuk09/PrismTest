package com.prismtest.scope

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

data class SampledValidationFrame(val requestedMs: Long, val run: ValidationFrameRun?, val error: String? = null)

object ValidationReport {
    private fun nullable(v: Double?): Any = v?.takeIf { it.isFinite() } ?: JSONObject.NULL
    private fun point(p: ValidationPoint) = JSONObject().put("x", p.x).put("y", p.y)
    private fun runJson(run: ValidationFrameRun): JSONObject = JSONObject()
        .put("width", run.width).put("height", run.height)
        .put("roi_xywh", JSONArray(listOf(run.roi.left, run.roi.top, run.roi.width, run.roi.height)))
        .put("score", run.score).put("masked_fraction", run.maskedFraction)
        .put("local_contrast_threshold", run.threshold).put("gray_conversion_ms", run.grayMs)
        .put("core_ms", run.coreMs).put("candidate_output_cap", 32)
        .put("candidates", JSONArray(run.candidates.map { c -> JSONObject().put("rank", c.rank)
            .put("x", c.x).put("y", c.y).put("radius_px", c.radius).put("contrast", c.contrast).put("area_px", c.pixels) }))

    fun json(source: String, pieceId: String, requestedMs: Long?, run: ValidationFrameRun?,
             truth: List<ValidationPoint>, tolerance: Double, completeTruth: Boolean,
             suite: NativeSuiteResult?, batch: List<SampledValidationFrame>,
             device: String, appVersion: String): JSONObject {
        val root = JSONObject().put("schema", "prism-validation-v1").put("algorithm", "dig_v1")
            .put("created_at", java.time.Instant.now().toString()).put("device", device)
            .put("app_version", appVersion).put("source", source).put("physical_piece_id", pieceId)
            .put("requested_video_ms", requestedMs ?: JSONObject.NULL)
            .put("frame_time_note", "Video times request the nearest decoded frame; no exact PTS or camera-FPS claim.")
            .put("truth_scope", if (completeTruth) "complete_selected_roi" else "marked_points_only")
            .put("tolerance_px", tolerance).put("truth_points", JSONArray(truth.map(::point)))
            .put("scope_note", "Frame point agreement and native function tests; not population accuracy, physical dig size, or live-camera validation.")
        if (run != null) {
            val compare = ValidationMetrics.compare(truth, run.candidates.map { ValidationPoint(it.x, it.y) }, tolerance, completeTruth)
            root.put("frame", runJson(run)).put("comparison", JSONObject()
                .put("matches", JSONArray(compare.matches.map { m -> JSONObject().put("truth_index", m.truthIndex)
                    .put("candidate_index", m.candidateIndex).put("distance_px", m.errorPx) }))
                .put("missed_truth_indices", JSONArray(compare.missed)).put("extra_candidate_indices", JSONArray(compare.extra))
                .put("extra_interpretation", if (completeTruth) "false_positive_candidates" else "unlabelled")
                .put("labelled_point_agreement", nullable(compare.labelledPointAgreement))
                .put("precision_this_frame", nullable(compare.precision)).put("recall_this_frame", nullable(compare.recall)))
        }
        if (suite != null) root.put("native_self_test", JSONObject().put("passed", suite.passed)
            .put("checks", JSONArray(suite.checks.map { c -> JSONObject().put("name", c.name)
                .put("passed", c.passed).put("description", c.description).put("ms", c.milliseconds) }))
            .put("benchmark", JSONObject().put("roi", "160x140 synthetic").put("warmup", 5)
                .put("samples", suite.benchmarkMs.size).put("p50_ms", nullable(ValidationMetrics.percentile(suite.benchmarkMs, .5)))
                .put("p95_ms", nullable(ValidationMetrics.percentile(suite.benchmarkMs, .95)))
                .put("excludes", "Camera, image/video decoding, RGB conversion and display")))
        if (batch.isNotEmpty()) root.put("sampled_video", JSONObject().put("annotation_applied", false)
            .put("requested_frames", batch.size).put("successful_frames", batch.count { it.run != null })
            .put("core_p50_ms", nullable(ValidationMetrics.percentile(batch.mapNotNull { it.run?.coreMs }, .5)))
            .put("core_p95_ms", nullable(ValidationMetrics.percentile(batch.mapNotNull { it.run?.coreMs }, .95)))
            .put("frames", JSONArray(batch.map { f -> JSONObject().put("requested_ms", f.requestedMs)
                .put("result", f.run?.let(::runJson) ?: JSONObject.NULL).put("error", f.error ?: JSONObject.NULL) })))
        return root
    }

    fun html(report: JSONObject, bitmap: Bitmap?, run: ValidationFrameRun?, truth: List<ValidationPoint>, tolerance: Double): String {
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
        fun num(d: Double) = String.format(Locale.US, "%.3f", d)
        val figure = if (bitmap != null && run != null) {
            val roi = run.roi
            val cropped = Bitmap.createBitmap(bitmap, roi.left, roi.top, roi.width, roi.height)
            val png = ByteArrayOutputStream().also { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            if (cropped !== bitmap) cropped.recycle()
            val comparison = ValidationMetrics.compare(truth, run.candidates.map { ValidationPoint(it.x, it.y) },
                tolerance, report.optString("truth_scope") == "complete_selected_roi")
            val circles = truth.mapIndexed { i, p ->
                val color = if (i in comparison.missed) "#ff5e6b" else "#25d88d"
                "<circle cx='${num(p.x - roi.left)}' cy='${num(p.y - roi.top)}' r='${num(tolerance)}' stroke='$color'/>"
            }.joinToString("") + run.candidates.joinToString("") { c ->
                "<circle cx='${num(c.x - roi.left)}' cy='${num(c.y - roi.top)}' r='${num(c.radius + 2)}' stroke='#ffc857'/>"
            }
            "<figure><img alt='선택한 검사 영역 원본' src='data:image/png;base64,${Base64.encodeToString(png, Base64.NO_WRAP)}'/>" +
                "<svg viewBox='0 0 ${roi.width} ${roi.height}' aria-label='정답과 검출 위치' fill='none' stroke-width='1.5'>$circles</svg></figure>" +
                "<p>녹색: 일치한 정답 · 빨강: 놓친 정답 · 노랑: 검출 후보. 원은 위치 비교용이며 실제 디그 크기가 아닙니다.</p>"
        } else ""
        return """<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>PrismScope 검증 보고서</title><style>body{font:15px system-ui;max-width:1000px;margin:30px auto;padding:0 18px;background:#111920;color:#e9f0f5}figure{position:relative;max-width:720px;margin:20px 0}img{width:100%;display:block}svg{position:absolute;inset:0;width:100%;height:100%}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#1d2933;padding:20px;border-radius:12px}p{line-height:1.8}</style>
            <h1>PrismScope 검증 보고서</h1><p>이 결과는 선택한 프레임의 점 위치와 검출 코어 기능을 비교합니다. 실물 모집단의 정확도·디그 mm 크기를 보증하지 않습니다.</p>
            $figure<h2>기록</h2><pre>${escape(report.toString(2))}</pre></html>""".trimIndent()
    }
}
