package com.ef64nagaoka.g2cam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * g2cam://start で呼ばれる、画面を持たない入口。
 * G2 カメラアプリ（Even アプリ内）から自動でコンパニオンを起動するために使う。
 *
 * カメラ付きのフォアグラウンドサービスは「アプリが画面に出ている間」にしか始められないので、
 * いったんこの透明な画面が前面に出て、サービスを始めたらすぐ閉じる（Even アプリに戻る）。
 * カメラの権限がまだ無いときだけ、権限を頼むために本体の画面を開く。
 */
class LaunchActivity : Activity() {

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (!CameraService.running) {
                ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
            }
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
