package com.ef64nagaoka.g2cam

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * コンパニオンの更新（0.1.16〜）。
 * GitHub の配布ページ（Releases。CI が版ごとに APK を置く）の最新版を見て、新しければ取ってきて入れ替える。
 * 署名の鍵を固定したので、上書きで入る（設定も残る）。
 */
object Updater {
    const val REPO = "ef641003/g2cam-companion"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    /** いつでも最新の APK を指す URL（初めて入れるときにも使える） */
    const val LATEST_APK_URL = "https://github.com/$REPO/releases/latest/download/g2cam-companion.apk"
    const val ACTION_INSTALL_STATUS = "com.ef64nagaoka.g2cam.INSTALL_STATUS"

    class Release(val version: String, val apkUrl: String, val notes: String, val sizeBytes: Long)

    /** 最新版を調べる（ネットワーク。メインスレッドから呼ばない） */
    fun fetchLatest(): Release {
        val c = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "g2cam-companion")
        }
        try {
            if (c.responseCode != 200) throw IllegalStateException("配布ページを読めません（HTTP ${c.responseCode}）")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = j.optString("tag_name").removePrefix("v")
            val assets = j.optJSONArray("assets")
            var url = ""
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            if (tag.isEmpty() || url.isEmpty()) throw IllegalStateException("配布ページに APK がありません")
            return Release(tag, url, j.optString("name"), size)
        } finally {
            c.disconnect()
        }
    }

    /** a が b より新しいか（"0.1.16" と "0.1.15" のような番号） */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /**
     * APK を取ってきて、Android のインストーラーに渡す（ネットワーク。メインスレッドから呼ばない）。
     * 入れ替えの確認画面は ACTION_INSTALL_STATUS の受け取り側（MainActivity）が出す。
     */
    fun downloadAndInstall(context: Context, rel: Release, progress: (String) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                var c = URL(rel.apkUrl).openConnection() as HttpURLConnection
                // GitHub の配布は別のホストへ転送される
                c.instanceFollowRedirects = true
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "g2cam-companion")
                if (c.responseCode in 300..399) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    c = URL(loc).openConnection() as HttpURLConnection
                    c.setRequestProperty("User-Agent", "g2cam-companion")
                }
                if (c.responseCode != 200) throw IllegalStateException("APK を取れません（HTTP ${c.responseCode}）")
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else rel.sizeBytes
                c.inputStream.use { input ->
                    session.openWrite("g2cam-companion.apk", 0, if (total > 0) total else -1).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct && pct % 10 == 0) {
                                    lastPct = pct
                                    progress("取り込み中 $pct%")
                                }
                            }
                        }
                        session.fsync(out)
                    }
                }
                c.disconnect()
                progress("入れ替えています…")
                val pi = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            try {
                installer.abandonSession(id)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    /** インストーラーからの知らせ。確認が要れば確認画面を出す。結果の文を返す（null は途中経過） */
    fun handleStatus(context: Context, intent: Intent): String? {
        return when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            }
            PackageInstaller.STATUS_SUCCESS -> "更新しました"
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                val hint = if (st == PackageInstaller.STATUS_FAILURE_CONFLICT || msg.contains("signature", true)) {
                    "（署名が違います。0.1.15 以前から 0.1.16 へは、一度アンインストールしてから入れてください）"
                } else {
                    ""
                }
                "更新できませんでした：$msg$hint"
            }
        }
    }
}

/** 更新で入れ替わったあと、待機モードがオンなら待機サービスを立ち上げ直す */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppLog.init(context)
        AppLog.i("コンパニオンを更新しました（${try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "?" }}）")
        if (Prefs.standby(context)) {
            try {
                StandbyService.start(context)
            } catch (_: Exception) {
            }
        }
    }
}
