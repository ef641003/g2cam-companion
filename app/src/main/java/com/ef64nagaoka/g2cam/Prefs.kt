package com.ef64nagaoka.g2cam

import android.content.Context

/** 小さな設定の保存 */
object Prefs {
    private const val NAME = "g2cam"
    private const val KEY_AUTO_STOP = "autoStop"
    private const val KEY_SAVE_TREE = "saveTree"
    private const val KEY_HQ = "highQuality"

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
}
