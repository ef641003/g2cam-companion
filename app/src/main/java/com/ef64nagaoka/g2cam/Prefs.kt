package com.ef64nagaoka.g2cam

import android.content.Context

/** 小さな設定の保存 */
object Prefs {
    private const val NAME = "g2cam"
    private const val KEY_AUTO_STOP = "autoStop"

    /** G2 カメラ側の終了に合わせて自動で止める（終了の合図、または応答の途絶え） */
    fun autoStop(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_STOP, true)

    fun setAutoStop(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_STOP, value).apply()
    }
}
