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
    private const val KEY_STREAM_FPS = "streamFps"
    private const val KEY_IDLE_CAMERA_OFF = "idleCameraOff"
    private const val KEY_IDLE_SENSOR_FPS = "idleSensorFps"
    private const val KEY_EXT_ON_DEMAND = "extensionOnDemand"
    private const val KEY_STANDBY_OVERLAY_ALWAYS = "standbyOverlayAlways"
    private const val KEY_STANDBY_STARTS = "standbyStarts"
    private const val KEY_LAST_UPDATE_CHECK = "lastUpdateCheck"
    private const val KEY_HEAT_STOP_TEMP = "heatStopTemp"
    private const val KEY_HEAT_STOP_THERMAL = "heatStopThermal"
    private const val KEY_HEAT_HOT_THERMAL = "heatHotThermal"

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

    // ── 負荷を抑える設定（0.1.12）。録画の画質には関わらない ──

    /** メガネへ配信する映像の上限 fps（メガネには 5〜9fps しか送れないので 15 で足りる。30 は以前の動き） */
    fun streamFps(context: Context): Int = sp(context).getInt(KEY_STREAM_FPS, 15).coerceIn(5, 30)

    fun setStreamFps(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_STREAM_FPS, value.coerceIn(5, 30)).apply()
    }

    /** 誰も映像を取りに来なくなったら（20 秒）カメラを閉じる。次の要求で開き直す（1 秒ほど待たせる） */
    fun idleCameraOff(context: Context): Boolean = sp(context).getBoolean(KEY_IDLE_CAMERA_OFF, true)

    fun setIdleCameraOff(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_IDLE_CAMERA_OFF, value).apply()
    }

    /** 配信＋撮影だけのとき（録画の準備中・録画中ではないとき）はセンサーを 15fps にして ISP の仕事を減らす */
    fun idleSensorFps(context: Context): Boolean = sp(context).getBoolean(KEY_IDLE_SENSOR_FPS, true)

    fun setIdleSensorFps(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_IDLE_SENSOR_FPS, value).apply()
    }

    /** 静止画の高画質（端末のカメラ拡張）は撮影の前後だけ使い、待機中は通常の組み方にする（拡張は待機中も処理が走って重い） */
    fun extensionOnDemand(context: Context): Boolean = sp(context).getBoolean(KEY_EXT_ON_DEMAND, true)

    fun setExtensionOnDemand(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_EXT_ON_DEMAND, value).apply()
    }

    /** 待機モードで透明な窓を出し続ける（false なら起動要求のときだけ出す） */
    fun standbyOverlayAlways(context: Context): Boolean = sp(context).getBoolean(KEY_STANDBY_OVERLAY_ALWAYS, true)

    fun setStandbyOverlayAlways(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_STANDBY_OVERLAY_ALWAYS, value).apply()
    }

    /** 待機サービスが始まった時刻を残す（システムに何度も再起動されていないかを見るため。直近 20 回） */
    fun noteStandbyStart(context: Context) {
        val now = System.currentTimeMillis()
        val list = standbyStarts(context).filter { now - it < 24 * 3600_000L }.takeLast(19) + now
        sp(context).edit().putString(KEY_STANDBY_STARTS, list.joinToString(",")).apply()
    }

    private fun standbyStarts(context: Context): List<Long> =
        sp(context).getString(KEY_STANDBY_STARTS, "")?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    fun standbyStartsWithin(context: Context, ms: Long): Int {
        val now = System.currentTimeMillis()
        return standbyStarts(context).count { now - it < ms }
    }

    /** 最後に配布ページを確かめた時刻（エポックミリ秒） */
    fun lastUpdateCheck(context: Context): Long = sp(context).getLong(KEY_LAST_UPDATE_CHECK, 0L)

    fun setLastUpdateCheck(context: Context, at: Long) {
        sp(context).edit().putLong(KEY_LAST_UPDATE_CHECK, at).apply()
    }

    // ── 高温の判定（0.1.15。利用者が選べる） ──

    /**
     * 録画を止める電池の温度（度）。0 = G2 アプリの設定に従う（G2 録画は自分の設定、G2 カメラは温度では止めない）、
     * -1 = 温度では止めない、38〜60 = その温度で止める
     */
    fun heatStopTemp(context: Context): Int = sp(context).getInt(KEY_HEAT_STOP_TEMP, 0).let { if (it == 0 || it == -1) it else it.coerceIn(38, 60) }

    fun setHeatStopTemp(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_HEAT_STOP_TEMP, value).apply()
    }

    /** 録画を止める発熱の段階（Android の PowerManager の段階 3〜6）。0 = 段階では止めない。既定 4（危険） */
    fun heatStopThermal(context: Context): Int = sp(context).getInt(KEY_HEAT_STOP_THERMAL, 4).let { if (it == 0) 0 else it.coerceIn(2, 6) }

    fun setHeatStopThermal(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_HEAT_STOP_THERMAL, value).apply()
    }

    /** 「熱い」とみなす発熱の段階（配信の fps を半分・録画の準備と遡り録画の録りためを休む）。0 = 使わない。既定 3（重い） */
    fun heatHotThermal(context: Context): Int = sp(context).getInt(KEY_HEAT_HOT_THERMAL, 3).let { if (it == 0) 0 else it.coerceIn(1, 6) }

    fun setHeatHotThermal(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_HEAT_HOT_THERMAL, value).apply()
    }

    /** 実際に使う「録画を止める温度」。app は G2 アプリが頼んできた温度（0 で止めない）。0.0 なら温度では止めない */
    fun effectiveStopTemp(context: Context, app: Double): Double = when (val v = heatStopTemp(context)) {
        0 -> app
        -1 -> 0.0
        else -> v.toDouble()
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
