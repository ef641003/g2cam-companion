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

    private lateinit var status: TextView
    private lateinit var toggle: Button
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
                "・G2 カメラを開くと、自動でこのアプリが起動してカメラを始めます（G2 カメラを終了すると自動で止まります）。\n" +
                "・起動しないときは、このアプリを開いて「カメラを開始」を押してから G2 カメラを開いてください（この画面を出したまま押すこと。Android の制約で、裏からは開始できません）。\n" +
                "・画面を消しても通知が出ている間は動きます。手で止めるときは「停止」\n\n" +
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
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        val lines = ArrayList<String>()
        lines.add(if (CameraService.running) "● 動作中（http://127.0.0.1:${CameraService.PORT}）" else "○ 停止中")
        if (CameraService.running) {
            lines.add("配信したフレーム：${CameraService.framesServed}　撮影した枚数：${CameraService.photosTaken}")
        }
        lines.add("カメラ権限：" + if (camera) "OK" else "未許可")
        lines.add("電池の最適化の除外：" + if (battery) "OK" else "未設定（推奨）")
        CameraService.lastError?.let { lines.add("⚠ $it") }
        status.text = lines.joinToString("\n")
        toggle.text = if (CameraService.running) "停止" else "カメラを開始"
    }
}
