package ai.syne.app

import android.util.Log

/** 모든 로그는 "SYNE" 태그 하나로. `adb logcat -s SYNE` */
object SyneLog {
    private const val TAG = "SYNE"
    fun d(msg: String) = Log.d(TAG, msg)
    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String, t: Throwable? = null) = Log.w(TAG, msg, t)
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
}
