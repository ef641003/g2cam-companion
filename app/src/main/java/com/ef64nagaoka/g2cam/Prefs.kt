package com.ef64nagaoka.g2cam

import android.content.Context

/** 小さな設定の保存 */
object Prefs {
    private const val NAME = "g2cam"
    private const val KEY_AUTO_STOP = "autoStop"
    private const val KEY_SAVE_TREE = "saveTree"
    private const val KEY_HQ = "highQuality"
    private const val KEY_STANDBY = "standby"
    private const val KEY_STAB = "stabilization"
    private const val KEY_PREROLL = "preRoll"
    private const val KEY_PREROLL_SEC = "preRollSeconds"

    private fun sp(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** G2 カメラ側の終了に合わせて自動で止める（終了の合図、または応答の途絶え） */
    fun autoStop(context: Context): Boolean = sp(context).getBoolean(KEY_AUTO_STOP, true)

    fun setAutoStop(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_STOP, value).apply()
    }

    /** 保存先に選んだフォルダ（ドキュメントツリーの URI 文字列）。null なら既定（DCIM/G2Camera） */
    fun saveTree(context: Context): String? = sp(context).getString(KEY_SAVE_TREE, null)

    fun setSaveTree(context: Context, uri: String?) {
        sp(context).edit().apply { if (uri == null) remove(KEY_SAVE_TREE) else putString(KEY_SAVE_TREE, uri) }.apply()
    }

    /** 静止画を端末のカメラ拡張（HDR・夜景・オート）で撮る。非対応の端末では自動で通常撮影になる */
    fun highQuality(context: Context): Boolean = sp(context).getBoolean(KEY_HQ, true)

    fun setHighQuality(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_HQ, value).apply()
    }

    /** G2 カメラの起動を待つ「待機」を常駐させる */
    fun standby(context: Context): Boolean = sp(context).getBoolean(KEY_STANDBY, false)

    fun setStandby(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_STANDBY, value).apply()
    }

    /** 手ブレ補正（光学式があれば静止画・配信で、動画は電子式も）を使う */
    fun stabilization(context: Context): Boolean = sp(context).getBoolean(KEY_STAB, true)

    fun setStabilization(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_STAB, value).apply()
    }

    /**
     * 遡り録画（G2 録画が開いている間は短い切れ端を録り続け、録画を始めたときに直前の数秒もつなぐ）。
     * 待機中もカメラ（と音声ありならマイク）で録り続けるので、電池と発熱が増える。既定はオフ
     */
    fun preRoll(context: Context): Boolean = sp(context).getBoolean(KEY_PREROLL, false)

    fun setPreRoll(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_PREROLL, value).apply()
    }

    /** 遡る秒数（5 / 10 / 15 / 30） */
    fun preRollSeconds(context: Context): Int = sp(context).getInt(KEY_PREROLL_SEC, 10).coerceIn(3, 60)

    fun setPreRollSeconds(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_PREROLL_SEC, value.coerceIn(3, 60)).apply()
    }

    /** 録画の組み方の記憶：向き・画質・静止画の有無ごとに、頼んだ画質が通った手ブレ補正のモード（0〜2） */
    private fun planKey(facing: String, quality: String, still: Boolean) = "plan:$facing:$quality:${if (still) "s" else "n"}"

    fun planEis(context: Context, facing: String, quality: String, still: Boolean): Int? {
        val v = sp(context).getInt(planKey(facing, quality, still), -1)
        return if (v in 0..2) v else null
    }

    fun setPlanEis(context: Context, facing: String, quality: String, still: Boolean, eis: Int) {
        sp(context).edit().putInt(planKey(facing, quality, still), eis).apply()
    }
}
