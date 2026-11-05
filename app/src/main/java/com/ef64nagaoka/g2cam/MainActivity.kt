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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
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
    private lateinit var loadText: TextView
    private lateinit var logText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
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

    /** 見出しと選択欄（Spinner）。選ぶと onPick に値を渡す */
    private fun addChoice(root: ViewGroup, title: String, labels: List<String>, values: IntArray, current: Int, onPick: (Int) -> Unit) {
        root.addView(TextView(this).apply {
            text = title
            setPadding(0, dp(6), 0, 0)
        })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val idx = values.indexOf(current)
        if (idx >= 0) spinner.setSelection(idx, false)
        var last = current
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val v = values[position]
                if (v == last) return // 最初の表示のときにも呼ばれる
                last = v
                onPick(v)
                AppLog.i("設定を変えました：$title → ${labels[position]}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        root.addView(spinner)
    }

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

        loadText = TextView(this).apply {
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFFF4F0E6.toInt())
        }
        root.addView(loadText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

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
        // ── 負荷を抑える設定（録画の画質には関わらない） ──
        root.addView(TextView(this).apply {
            text = "スマホの負荷を抑える（録画の画質は変わりません）"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
        root.addView(TextView(this).apply { text = "メガネへ配信する映像の上限" })
        val fpsGroup = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        val nowFps = Prefs.streamFps(this)
        for (fps in intArrayOf(10, 15, 30)) {
            val b = RadioButton(this).apply {
                id = View.generateViewId()
                text = "$fps fps"
            }
            fpsGroup.addView(b)
            if (fps == nowFps) fpsGroup.check(b.id)
            b.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    Prefs.setStreamFps(this@MainActivity, fps)
                    notifyPrefsChanged()
                }
            }
        }
        root.addView(fpsGroup)
        root.addView(TextView(this).apply {
            text = "メガネには 5〜9fps しか送れないので 15 で足ります（30 は以前の動き。変換の仕事が倍になります）"
            textSize = 13f
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(CheckBox(this).apply {
            text = "誰も映像を取りに来なくなったら（20 秒）カメラを閉じる。次の要求で開き直す（最初の 1 枚が 1 秒ほど遅れる）"
            isChecked = Prefs.idleCameraOff(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setIdleCameraOff(this@MainActivity, checked) }
        })
        root.addView(CheckBox(this).apply {
            text = "待機中（録画の準備中・録画中ではないとき）はカメラのセンサーを 15fps にする。反映は次にカメラを開始したとき"
            isChecked = Prefs.idleSensorFps(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setIdleSensorFps(this@MainActivity, checked) }
        })
        root.addView(CheckBox(this).apply {
            text = "静止画の高画質（端末の HDR・オート）は撮影の前後だけ使う（待機中は通常の組み方。最初の 1 枚が 1 秒ほど遅れる）。反映は次にカメラを開始したとき"
            isChecked = Prefs.extensionOnDemand(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setExtensionOnDemand(this@MainActivity, checked) }
        })
        root.addView(CheckBox(this).apply {
            text = "待機モードでは透明な窓を出し続ける（Android 15 以降で裏から起動するのに必要。切ると起動要求のときだけ出す。重くなる端末では切って比べる）"
            isChecked = Prefs.standbyOverlayAlways(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setStandbyOverlayAlways(this@MainActivity, checked)
                if (StandbyService.running) startService(Intent(this@MainActivity, StandbyService::class.java).setAction(StandbyService.ACTION_OVERLAY_CHANGED))
                handler.postDelayed({ render() }, 300)
            }
        })

        // ── 高温の判定（0.1.15）。G2 カメラ・G2 録画の両方に効く ──
        root.addView(TextView(this).apply {
            text = "高温の判定（G2 カメラ・G2 録画の両方）"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
        val tempValues = intArrayOf(0, -1) + (38..60).toList().toIntArray()
        val tempLabels = tempValues.map {
            when (it) {
                0 -> "G2 アプリの設定に従う（G2 録画の「止める温度」。G2 カメラは温度では止めない）"
                -1 -> "温度では止めない"
                else -> "$it 度で止める"
            }
        }
        addChoice(root, "録画を止めるスマホの温度（電池の温度）", tempLabels, tempValues, Prefs.heatStopTemp(this)) {
            Prefs.setHeatStopTemp(this, it)
        }
        val stageNames = mapOf(1 to "軽い", 2 to "中くらい", 3 to "重い", 4 to "危険", 5 to "緊急", 6 to "停止寸前")
        val stopValues = intArrayOf(2, 3, 4, 5, 6, 0)
        addChoice(
            root, "録画を止める発熱の段階（Android が知らせる段階）",
            stopValues.map { if (it == 0) "段階では止めない" else "段階 $it（${stageNames[it]}）以上で止める" + if (it == 4) "（既定）" else "" },
            stopValues, Prefs.heatStopThermal(this),
        ) { Prefs.setHeatStopThermal(this, it) }
        val hotValues = intArrayOf(1, 2, 3, 4, 5, 0)
        addChoice(
            root, "「熱い」とみなす発熱の段階（メガネへの配信を半分の fps に・録画の準備と遡り録画の録りためを休む）",
            hotValues.map { if (it == 0) "使わない" else "段階 $it（${stageNames[it]}）以上" + if (it == 3) "（既定）" else "" },
            hotValues, Prefs.heatHotThermal(this),
        ) { Prefs.setHeatHotThermal(this, it) }
        root.addView(TextView(this).apply {
            text = "・止める温度の 3 度手前で、録画の準備と遡り録画の録りためを休みます（G2 録画はメガネにも知らせます）\n" +
                "・止めるときは保存してから止めます。どちらも「止めない」にすると、スマホが熱いまま録り続けます（端末が自分でカメラを止めることがあります）\n" +
                "・5 秒ごとに見て、次の見張りから効きます"
            textSize = 13f
            setPadding(0, dp(2), 0, dp(8))
        })

        root.addView(CheckBox(this).apply {
            text = "手ブレ補正を使う（光学式があれば静止画・配信に、動画は電子式も。非対応の端末では自動で外れます）。反映は次にカメラを開始したとき"
            isChecked = Prefs.stabilization(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setStabilization(this@MainActivity, checked) }
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

        // ── 遡り録画（G2 録画用。既定はオフ） ──
        root.addView(TextView(this).apply {
            text = "遡り録画（G2 録画用）"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
        root.addView(CheckBox(this).apply {
            text = "遡り録画を使う：G2 録画で録画を始めたとき、その直前の数秒も 1 本の動画に入れる。" +
                "G2 録画を開いている間はカメラ（音声ありならマイクも）で録り続けるので、電池の減りと発熱が増えます"
            isChecked = Prefs.preRoll(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setPreRoll(this@MainActivity, checked)
                notifyPreRollChanged()
            }
        })
        root.addView(TextView(this).apply {
            text = "遡る長さ"
            setPadding(0, dp(4), 0, 0)
        })
        val secGroup = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        val nowSec = Prefs.preRollSeconds(this)
        for (sec in intArrayOf(5, 10, 15, 30)) {
            val b = RadioButton(this).apply {
                id = View.generateViewId()
                text = "$sec 秒"
            }
            secGroup.addView(b)
            if (sec == nowSec) secGroup.check(b.id)
            b.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    Prefs.setPreRollSeconds(this@MainActivity, sec)
                    notifyPreRollChanged()
                }
            }
        }
        root.addView(secGroup)
        root.addView(TextView(this).apply {
            text = "・録画を止めたあと、直前の分をつないで 1 本に書き出します（再エンコードなし。長い録画ほど数十秒かかります）。" +
                "書き出し中も G2 録画はそのまま使えます\n" +
                "・スマホが熱いとき・空き容量が少ないときは、録りためを自動で休みます"
            textSize = 13f
            setPadding(0, dp(2), 0, dp(8))
        })

        root.addView(CheckBox(this).apply {
            text = "G2 カメラが終了したら自動で停止する（終了の合図、または 2 分間応答がないとき）"
            isChecked = Prefs.autoStop(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setAutoStop(this@MainActivity, checked) }
        })

        // ── ログ ──
        root.addView(TextView(this).apply {
            text = "ログ（新しい順。起動・停止・起動要求・カメラの開閉・負荷の異常・発熱）"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
        logText = TextView(this).apply {
            textSize = 12f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFFEFEFEF.toInt())
            typeface = Typeface.MONOSPACE
        }
        root.addView(logText)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "ログを共有"
                setOnClickListener {
                    val body = "G2 カメラ コンパニオン ${appVersion()} のログ\n${LoadMonitor.describe()}\n\n${AppLog.all()}"
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "G2 カメラ コンパニオンのログ")
                        putExtra(Intent.EXTRA_TEXT, body)
                    }, "ログを共有"))
                }
            })
            addView(Button(this@MainActivity).apply {
                text = "ログを消す"
                setOnClickListener {
                    AppLog.clear()
                    render()
                }
            })
        })

        root.addView(TextView(this).apply {
            text = "使いかた\n" +
                "・G2 カメラを開くと自動でカメラを始めるには、上の「待機モード」をオンにして「他のアプリの上に表示」を許可してください（G2 カメラを終了すると自動で止まります）。\n" +
                "・起動しないときは、このアプリを開いて「カメラを開始」を押してから G2 カメラを開いてください（この画面を出したまま押すこと。Android の制約で、裏からは開始できません）。\n" +
                "・画面を消しても通知が出ている間は動きます。手で止めるときは「停止」\n" +
                "・録画は G2 カメラのメニュー「動画 録画/停止」、または G2 録画アプリのタップで。写真も動画も、上で選んだ保存先にすぐ保存されます（選ばなければ「写真」アプリの G2Camera フォルダ。音声はマイクの許可があるときだけ）\n" +
                "・G2 録画を開くと、待機中から録画できる組み方にしておくので、タップしてすぐ録画が始まります。スマホが熱すぎるとき・空き容量が少ないときは、保存してから自動で止めます\n\n" +
                "vivo / OriginOS など：「電池の最適化から除外」に加えて、アプリ情報の電池設定で「バックグラウンドでの高消費電力を許可」をオンにしてください。止まるときは最近のタスクでこのアプリを鍵（ロック）してください。"
            setPadding(0, dp(16), 0, 0)
            gravity = Gravity.START
            visibility = View.VISIBLE
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** 動いているサービスに設定が変わったことを伝える（配信の fps など。止まっていれば次の開始時に読む） */
    private fun notifyPrefsChanged() {
        if (CameraService.running) {
            startService(Intent(this, CameraService::class.java).setAction(CameraService.ACTION_PREFS_CHANGED))
        }
        handler.postDelayed({ render() }, 300)
    }

    /** 動いているサービスに遡り録画の設定が変わったことを伝える（止まっていれば次の開始時に読む） */
    private fun notifyPreRollChanged() {
        if (CameraService.running) {
            startService(Intent(this, CameraService::class.java).setAction(CameraService.ACTION_PREROLL_CHANGED))
        }
        handler.postDelayed({ render() }, 300)
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
            CameraService.monitorLabel?.let { lines.add("スマホ：$it") }
            CameraService.streamLabel?.let { lines.add(it) }
        }
        lines.add(
            "遡り録画：" + (
                CameraService.preRollText()
                    ?: if (Prefs.preRoll(this)) "オン（${Prefs.preRollSeconds(this)} 秒）・G2 録画を開くと録りためを始めます" else "オフ"
                ),
        )
        lines.add("カメラ権限：" + if (camera) "OK" else "未許可")
        lines.add("録画の音声（マイク）：" + if (mic) "OK" else "未許可（無音で録画。許可はアプリ情報から）")
        lines.add("電池の最適化の除外：" + if (battery) "OK" else "未設定（推奨）")
        val overlayOk = Settings.canDrawOverlays(this)
        lines.add(
            "待機モード：" + (if (StandbyService.running) "● 待機中（起動要求 ${StandbyService.launchCount} 回・透明な窓 ${if (StandbyService.overlayShown) "あり" else "なし"}・${StandbyService.serverStats}）" else "○ オフ") +
                "　他のアプリの上に表示：" + if (overlayOk) "OK" else "未許可",
        )
        if (StandbyService.running) {
            val restarts = Prefs.standbyStartsWithin(this, 60 * 60_000L)
            if (restarts > 1) lines.add("⚠ 待機サービスがこの 1 時間に $restarts 回始まっています（システムに止められて再起動している可能性）")
            if (StandbyService.cpuAlarms > 0) lines.add("⚠ 待機中なのに CPU が高い状態を ${StandbyService.cpuAlarms} 回見つけました（ログ参照）")
        }
        // 負荷：このアプリ自身の CPU（カメラの HAL・Even アプリ側は含まない）と、スマホの温度・発熱の段階
        val mon = try {
            DeviceMonitor.read(this)
        } catch (e: Exception) {
            null
        }
        val thermal = mon?.thermal ?: 0
        val thermalText = when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> "発熱 段階$thermal（重い・遅くなる段階）"
            thermal >= PowerManager.THERMAL_STATUS_MODERATE -> "発熱 段階$thermal（少し熱い）"
            thermal >= PowerManager.THERMAL_STATUS_LIGHT -> "発熱 段階$thermal（軽い）"
            else -> "発熱なし"
        }
        val temp = mon?.tempC?.takeIf { !it.isNaN() }?.let { String.format(java.util.Locale.US, "%.1f度", it) } ?: "温度不明"
        LoadMonitor.tick()
        loadText.text = "負荷（このアプリ自身。カメラの HAL や Even アプリの分は含まない）\n" +
            (if (CameraService.running || StandbyService.running) LoadMonitor.describe() else "サービスが動いていないので計測していません") +
            "\nスマホ：$temp・$thermalText" + (mon?.let { if (it.batteryPct >= 0) "・電池 ${it.batteryPct}%" else "" } ?: "")
        logText.text = AppLog.recent(40).ifEmpty { "（まだありません）" }
        CameraService.lastError?.let { lines.add("⚠ $it") }
        CameraService.diagText?.let { lines.add("構成：$it") }
        saveDir.text = "保存先：" + Storage.describe(this)
        status.text = lines.joinToString("\n")
        toggle.text = if (CameraService.running) "停止" else "カメラを開始"
    }
}
