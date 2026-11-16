package com.ef64nagaoka.g2cam

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * レンズの倍率（0.1.20）：端末のレンズ（超広角・標準・望遠）が、ズームの何倍で「そのままの画角」になるかを見積もり、
 * いまのズームが光学（レンズそのまま）か、デジタル（切り出して拡大。拡大するほど甘くなる）かを出す。
 * Android に頼らない計算だけ（単体試験で確かめる）。
 *
 * 見積もり：レンズごとの焦点距離とセンサーの大きさから 35mm 判換算の焦点距離を出し、標準レンズ（ズーム 1 倍）との比を倍率にする。
 * 超広角があるとき（ズームの下端が 1 未満）は、一番広いレンズ ＝ ズームの下端 とみなして標準レンズを決める。
 * 端末がレンズを 2 本以上出していないときは「分からない」（1 本しか見えない端末でも、裏で望遠に切り替えていることがあるため）。
 */
object LensInfo {
    /** 端末のレンズ 1 本（物理カメラ）。focalMm = 焦点距離、sensorW / sensorH = センサーの大きさ（mm） */
    data class Physical(val id: String, val focalMm: Float, val sensorW: Float, val sensorH: Float) {
        val valid: Boolean get() = focalMm > 0f && sensorW > 0f && sensorH > 0f

        /** 35mm 判に直した焦点距離（画角の目安） */
        val eq35: Double get() = focalMm * FULL_FRAME_DIAGONAL / hypot(sensorW.toDouble(), sensorH.toDouble())
    }

    /** ズームの ratio 倍で、このレンズそのままの画角になる */
    data class Lens(val id: String, val ratio: Double, val name: String)

    /**
     * いまのズームの様子。lens = 使っている（とみなした）レンズ、digital = そこからの拡大率（1 = 拡大なし）、
     * optical = 光学とみなせるか。分からなければ null
     */
    data class ZoomState(val lens: Lens?, val digital: Double?, val optical: Boolean?)

    const val FULL_FRAME_DIAGONAL = 43.2666

    /** これ未満の拡大は光学とみなす（倍率の見積もりの誤差を見込む） */
    const val OPTICAL_MAX_DIGITAL = 1.15

    /** 標準レンズの見積もりが、これ以上（比で）ずれていたら信用しない */
    private const val MAIN_TOLERANCE = 1.35

    /** 望遠の倍率は、端末がきりのよい値で切り替えることが多いので、近ければ寄せる */
    private val NICE = doubleArrayOf(1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0, 6.0, 7.0, 8.0, 10.0)
    private const val NICE_TOLERANCE = 0.06

    /** レンズの一覧（倍率の小さい順）。分からなければ空 */
    fun lenses(physical: List<Physical>, zoomMin: Double, zoomMax: Double): List<Lens> {
        val list = physical.filter { it.valid }.distinctBy { it.id }.sortedBy { it.eq35 }
        if (list.size < 2) return emptyList()
        val widest = list.first()
        val main: Physical
        if (zoomMin < 0.95) {
            // 超広角つき：一番広いレンズがズームの下端。そこから見積もった標準レンズに一番近いものを標準にする
            val est = widest.eq35 / zoomMin
            val cand = list.drop(1).minByOrNull { abs(ln(it.eq35 / est)) } ?: return emptyList()
            if (abs(ln(cand.eq35 / est)) > ln(MAIN_TOLERANCE)) return emptyList()
            main = cand
        } else {
            main = widest
        }
        val out = ArrayList<Lens>()
        // 標準と一番広いレンズを先に入れる（ほぼ同じ倍率の補助のカメラに、その席を取られないように）
        val ordered = ArrayList<Physical>()
        ordered.add(main)
        if (widest !== main) ordered.add(widest)
        for (p in list) if (p !== main && p !== widest) ordered.add(p)
        for (p in ordered) {
            val raw = when {
                p === main -> 1.0
                p === widest -> zoomMin
                else -> p.eq35 / main.eq35
            }
            val ratio = round2(snap(raw))
            if (ratio < zoomMin * 0.95 || ratio > zoomMax * 1.05) continue
            // ほぼ同じ倍率のレンズ（補助のカメラなど）は 1 本にまとめる
            if (out.any { abs(ln(it.ratio / ratio)) < ln(1.05) }) continue
            out.add(Lens(p.id, ratio, nameOf(ratio)))
        }
        return if (out.size < 2) emptyList() else out.sortedBy { it.ratio }
    }

    private fun snap(r: Double): Double {
        if (r < 1.2) return r
        for (n in NICE) if (abs(r - n) <= n * NICE_TOLERANCE) return n
        return r
    }

    private fun round2(v: Double): Double = (v * 100).roundToInt() / 100.0

    fun nameOf(ratio: Double): String = when {
        ratio < 0.95 -> "超広角"
        ratio < 1.2 -> "標準"
        else -> "望遠"
    }

    /**
     * いまのズームの様子。activeId = いま使っているレンズ（端末が教えてくれたとき。暗いと望遠に切り替えない端末がある）。
     * 分からなければ、ズーム以下で一番大きい倍率のレンズを使っているとみなす
     */
    fun zoomState(lenses: List<Lens>, zoom: Double, activeId: String?): ZoomState {
        if (!(zoom > 0.0)) return ZoomState(null, null, null)
        // レンズが分からなくても、1 倍は標準レンズそのまま
        if (lenses.isEmpty()) return ZoomState(null, null, if (abs(zoom - 1.0) < 0.02) true else null)
        val predicted = lenses.filter { it.ratio <= zoom * 1.03 }.maxByOrNull { it.ratio } ?: lenses.minByOrNull { it.ratio }!!
        val lens = activeId?.let { id -> lenses.firstOrNull { it.id == id } } ?: predicted
        // 使っているレンズの倍率がズームより大きい（見積もりが大きすぎた）ときは、拡大なしとみなす
        val digital = max(1.0, zoom / lens.ratio)
        return ZoomState(lens, (digital * 10).roundToInt() / 10.0, digital < OPTICAL_MAX_DIGITAL)
    }

    /** 倍率の表示（0.6 / 1 / 3.5） */
    fun fmt(ratio: Double): String =
        if (abs(ratio - ratio.roundToInt()) < 0.005) ratio.roundToInt().toString()
        else String.format(Locale.US, "%.2f", ratio).trimEnd('0').trimEnd('.')

    /** 一覧の説明（超広角 x0.6・標準 x1・望遠 x3） */
    fun describe(lenses: List<Lens>): String = lenses.joinToString("・") { "${it.name} x${fmt(it.ratio)}" }

    /** いまの様子の説明（望遠・光学 / 標準・デジタル 2 倍） */
    fun describe(state: ZoomState): String {
        val lens = state.lens ?: return if (state.optical == true) "標準・光学" else "不明"
        val d = state.digital ?: 1.0
        return if (state.optical == true) "${lens.name}・光学" else "${lens.name}・デジタル ${fmt(d)} 倍"
    }
}
