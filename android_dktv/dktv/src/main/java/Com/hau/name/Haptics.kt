package Com.hau.name

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Rung ngan khi cham phim. Dung truc tiep Vibrator (quyen VIBRATE) nen van rung ke ca khi
 * "rung khi cham" trong cai dat he thong dang tat - khac voi performHapticFeedback().
 */
object Haptics {
    private const val TICK_MS = 25L
    private const val KEY_MS = 40L          // phím bàn phím: rung dài hơn chút cho dễ cảm nhận

    /** Rung khi bấm phím bàn phím gõ sang TV (rung ngay lúc chạm xuống). */
    fun key(view: View) = vibrate(view, KEY_MS)

    fun tick(view: View) = vibrate(view, TICK_MS)

    @Suppress("DEPRECATION")
    private fun vibrate(view: View, ms: Long) {
        val vib = view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vib != null && vib.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vib.vibrate(ms)
            }
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}
