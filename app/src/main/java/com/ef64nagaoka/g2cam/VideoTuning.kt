package com.ef64nagaoka.g2cam

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 録画の細かさ（ビットレート）と fps の決め方（0.1.20）。Android に頼らない計算だけ（単体試験で確かめる）。
 *
 * ビットレート：std は指定しない（CameraX が端末の標準から決める）。high / max は端末の標準に倍率を掛ける。
 * fps：30（これまでどおり。指定しない）か 60。60 は「選んだ画質のまま」「手ブレ補正を弱めずに」使えるときだけ使う
 * （利用者の選択：手ブレ補正を優先。両立しなければ 30fps に戻して知らせる）。
 */
object VideoTuning {
    const val STD = "std"
    const val HIGH = "high"
    const val MAX = "max"

    /** 目標のビットレートの下限・上限（おかしな値を符号化器に渡さないため） */
    const val MIN_BPS = 1_000_000
    const val MAX_BPS = 200_000_000

    /** 頼まれた段階の名前を揃える（知らない名前は std） */
    fun levelOf(name: String?): String = when (name?.lowercase(Locale.US)) {
        HIGH -> HIGH
        MAX -> MAX
        else -> STD
    }

    /** 端末の標準に掛ける倍率 */
    fun multiplier(level: String): Double = when (level) {
        HIGH -> 1.5
        MAX -> 2.0
        else -> 1.0
    }

    fun label(level: String): String = when (level) {
        HIGH -> "高（標準の 1.5 倍）"
        MAX -> "最高（標準の 2 倍）"
        else -> "標準"
    }

    /** 頼まれた fps を揃える（50 以上は 60、それ以外は 30） */
    fun fpsOf(v: Int): Int = if (v >= 50) 60 else 30

    /** 端末の標準が読めないときの目安（30fps のとき。短辺の画素数から） */
    fun fallbackBitrate(shortSide: Int): Int = when {
        shortSide >= 2160 -> 42_000_000
        shortSide >= 1080 -> 17_000_000
        shortSide >= 720 -> 10_000_000
        else -> 4_000_000
    }

    /**
     * 目標のビットレート（bps）。std なら null（指定しない）。
     * baseBps は端末の標準（baseFps のときの値）。fps が違えば比例させる（CameraX が標準で行うのと同じ）
     */
    fun targetBitrate(level: String, baseBps: Int, baseFps: Int, fps: Int): Int? {
        val m = multiplier(level)
        if (m <= 1.0 || baseBps <= 0) return null
        val scale = if (baseFps > 0 && fps > 0) fps.toDouble() / baseFps else 1.0
        return (baseBps * scale * m).coerceIn(MIN_BPS.toDouble(), MAX_BPS.toDouble()).roundToInt()
    }

    /** AE の fps 範囲に 60fps があるか（無ければ 60fps は試さない） */
    fun supports60(ranges: List<LowLight.FpsRange>): Boolean = ranges.any { it.upper >= 60 }

    /**
     * 60fps で試す電子式手ブレ補正のモードの順（強い順）。手ブレ補正を優先するので、補正オンなら「補正なし」は試さない。
     * strongest = 端末で一番強いモード（2 = プレビュー安定化、1 = 通常。無ければ null）、hasNormal = 通常（1）があるか。
     * 補正オフの設定、または端末に電子式の補正が無いときは、補正なし（0）だけ（失うものが無い）
     */
    fun plans60(stabOn: Boolean, strongest: Int?, hasNormal: Boolean): List<Int> {
        if (!stabOn) return listOf(0)
        val out = ArrayList<Int>()
        if (strongest != null && strongest > 0) out.add(strongest)
        if (hasNormal && !out.contains(1)) out.add(1)
        if (out.isEmpty()) out.add(0)
        return out
    }

    /** 実測の fps が、頼んだ fps に届いているとみなせるか（60 なら 51 以上） */
    fun fpsOk(measured: Double, want: Int): Boolean = measured >= want * 0.85

    /** 大きさと長さから、平均のビットレート（Mbps）。分からなければ 0 */
    fun mbpsOf(bytes: Long, durationMs: Long): Double = if (bytes > 0 && durationMs > 0) bytes * 8.0 / durationMs / 1000.0 else 0.0

    /** 表示用：bps → "48Mbps"（10 未満は小数 1 桁） */
    fun mbpsLabel(bps: Long): String {
        val m = bps / 1_000_000.0
        return if (m >= 10) "${m.roundToInt()}Mbps" else String.format(Locale.US, "%.1fMbps", m)
    }
}
