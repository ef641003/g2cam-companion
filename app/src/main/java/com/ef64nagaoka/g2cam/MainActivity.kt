package com.ef64nagaoka.g2cam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/** サービスの開始／停止と、電池の最適化除外などの案内だけを置いた画面。 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_TREE = 11
    }

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var saveDir: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestRuntimePermissions()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            Prefs.setSaveTree(this, uri.toString())
        } catch (e: Exception) {
            Prefs.setSaveTree(this, null)
            status.text = "このフォルダは使えません：${e.message}\n別のフォルダ（サブフォルダ）を選んでください"
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "G2 カメラ コンパニオン"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "スマホのカメラを G2 カメラアプリ（Even アプリ内）に渡し、画面が消えていても映像と撮影を続けます。写真は「写真」アプリの G2Camera フォルダに保存されます。"
            setPadding(0, dp(8), 0, dp(12))
        })

        status = TextView(this).apply {
            textSize = 16f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFEFEFEF.toInt())
        }
        root.addView(status)

        toggle = Button(this).apply {
            setOnClickListener { onToggle() }
        }
        root.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        saveDir = TextView(this).apply {
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(saveDir)
        root.addView(Button(this).apply {
            text = "保存先フォルダを選ぶ"
            setOnClickListener {
                // 内部ストレージやSDカードの好きなフォルダ。ルートや Download 直下は Android が選ばせないので、サブフォルダを作って選ぶ
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                ), REQ_TREE)
            }
        })
        root.addView(Button(this).apply {
            text = "保存先を既定（写真アプリ）に戻す"
            setOnClickListener {
                Prefs.saveTree(this@MainActivity)?.let {
                    try {
                        contentResolver.releasePersistableUriPermission(
                            Uri.parse(it),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    } catch (_: Exception) {
                    }
                }
                Prefs.setSaveTree(this@MainActivity, null)
                render()
            }
        })
        root.addView(CheckBox(this).apply {
            text = "G2 カメラを開いたら自動でカメラを開始する（待機モード。通知を出して常駐します）"
            isChecked = Prefs.standby(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setStandby(this@MainActivity, checked)
                if (checked) StandbyService.start(this@MainActivity) else StandbyService.stop(this@MainActivity)
                handler.postDelayed({ render() }, 300)
            }
        })
        root.addView(Button(this).apply {
            text = "「他のアプリの上に表示」を許可する（待機モードに必要）"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        root.addView(CheckBox(this).apply {
            text = "静止画を高画質で撮る（端末のHDR・オート機能を使う。非対応の端末では通常撮影）。反映は次にカメラを開始したとき"
            isChecked = Prefs.highQuality(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setHighQuality(this@MainActivity, checked) }
        })
        root.addView(Button(this).apply {
            text = "電池の最適化から除外する"
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        })
        root.addView(Button(this).apply {
            text = "アプリ情報を開く（バックグラウンド許可）"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })

        root.addView(CheckBox(this).apply {
            text = "G2 カメラが終了したら自動で停止する（終了の合図、または 2 分間応答がないとき）"
            isChecked = Prefs.autoStop(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setAutoStop(this@MainActivity, checked) }
        })

        root.addView(TextView(this).apply {
            text = "使いかた\n" +
                "・G2 カメラを開くと自動でカメラを始めるには、上の「待機モード」をオンにして「他のアプリの上に表示」を許可してください（G2 カメラを終了すると自動で止まります）。\n" +
                "・起動しないときは、このアプリを開いて「カメラを開始」を押してから G2 カメラを開いてください（この画面を出したまま押すこと。Android の制約で、裏からは開始できません）。\n" +
                "・画面を消しても通知が出ている間は動きます。手で止めるときは「停止」\n" +
                "・録画は G2 カメラのメニュー「動画 録画/停止」から。写真も動画も、上で選んだ保存先にすぐ保存されます（選ばなければ「写真」アプリの G2Camera フォルダ。音声はマイクの許可があるときだけ）\n\n" +
                "vivo / OriginOS など：「電池の最適化から除外」に加えて、アプリ情報の電池設定で「バックグラウンドでの高消費電力を許可」をオンにしてください。止まるときは最近のタスクでこのアプリを鍵（ロック）してください。"
            setPadding(0, dp(16), 0, 0)
            gravity = Gravity.START
            visibility = View.VISIBLE
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun onToggle() {
        if (CameraService.running) {
            startService(Intent(this, CameraService::class.java).setAction(CameraService.ACTION_STOP))
        } else {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestRuntimePermissions()
                return
            }
            ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
        }
        handler.postDelayed({ render() }, 300)
    }

    private fun requestRuntimePermissions() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 録画の音声用（断っても無音で録画できる）
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun render() {
        val pm = getSystemService(PowerManager::class.java)
        val camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        val lines = ArrayList<String>()
        lines.add(if (CameraService.running) "● 動作中（http://127.0.0.1:${CameraService.PORT}）" else "○ 停止中")
        if (CameraService.running) {
            lines.add("配信したフレーム：${CameraService.framesServed}　撮影した枚数：${CameraService.photosTaken}")
            CameraService.recordingLabel?.let { lines.add("● 録画中 $it") }
        }
        lines.add("カメラ権限：" + if (camera) "OK" else "未許可")
        lines.add("録画の音声（マイク）：" + if (mic) "OK" else "未許可（無音で録画。許可はアプリ情報から）")
        lines.add("電池の最適化の除外：" + if (battery) "OK" else "未設定（推奨）")
        val overlayOk = Settings.canDrawOverlays(this)
        lines.add(
            "待機モード：" + (if (StandbyService.running) "● 待機中（起動要求 ${StandbyService.launchCount} 回）" else "○ オフ") +
                "　他のアプリの上に表示：" + if (overlayOk) "OK" else "未許可",
        )
        CameraService.lastError?.let { lines.add("⚠ $it") }
        CameraService.diagText?.let { lines.add("構成：$it") }
        saveDir.text = "保存先：" + Storage.describe(this)
        status.text = lines.joinToString("\n")
        toggle.text = if (CameraService.running) "停止" else "カメラを開始"
    }
}
