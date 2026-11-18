package com.ef64nagaoka.g2cam

/**
 * 録り始めのピント・露出待ち（0.1.23）。Android に頼らない判定だけ（単体試験できるように）。
 *
 * 録画を始める直前に、撮影結果の AF（ピント）と AE（明るさ）の状態を見て、どちらも探している最中でなくなり、
 * それが少し続いたら「合った」とみなす。束ね直した直後は状態がまだ「何もしていない」のことがあるので、
 * 結果をいくつか見てから判断する。待つのは長くても MAX_WAIT_MS まで（合いきらなくても始める）。
 */
object Converge {
    /** 待つのは長くてもここまで */
    const val MAX_WAIT_MS = 1500L
    /** 「合った」状態がこれだけ続いたら始める */
    const val STABLE_MS = 200L
    /** 判断する前に、少なくともこれだけ結果を見る（束ね直した直後の「何もしていない」を見誤らないように） */
    const val MIN_RESULTS = 5

    // CaptureResult の値（android.hardware.camera2.CameraMetadata と同じ）
    private const val AF_PASSIVE_SCAN = 1
    private const val AF_ACTIVE_SCAN = 3
    private const val AE_SEARCHING = 1
    private const val AE_PRECAPTURE = 5

    /** ピントを探している最中でないか（固定焦点の端末は状態が無い＝合っているとみなす） */
    fun afSettled(af: Int?): Boolean = af == null || (af != AF_PASSIVE_SCAN && af != AF_ACTIVE_SCAN)

    /** 明るさを探している最中でないか */
    fun aeSettled(ae: Int?): Boolean = ae == null || (ae != AE_SEARCHING && ae != AE_PRECAPTURE)

    fun settled(af: Int?, ae: Int?): Boolean = afSettled(af) && aeSettled(ae)

    /** 待ち始めてから届いた結果を順に渡し、始めてよいかを聞く */
    class Watch(private val startAt: Long) {
        private var results = 0
        private var stableSince = -1L
        /** 最後に見た状態（ログ用） */
        var lastAf: Int? = null
            private set
        var lastAe: Int? = null
            private set

        fun update(af: Int?, ae: Int?, now: Long) {
            results += 1
            lastAf = af
            lastAe = ae
            if (settled(af, ae)) {
                if (stableSince < 0) stableSince = now
            } else {
                stableSince = -1L
            }
        }

        /** 合った（始めてよい） */
        fun ready(now: Long): Boolean = results >= MIN_RESULTS && stableSince >= 0 && now - stableSince >= STABLE_MS

        /** 待ちきれなかった（合いきらないまま始める） */
        fun timedOut(now: Long): Boolean = now - startAt >= MAX_WAIT_MS
    }
}
