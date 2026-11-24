package lab.recovery.live

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import java.io.IOException

/** Runs in a separate process on an emulator, with no access to a Live session. */
class FaultHarnessActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.HARDWARE !in setOf("ranchu", "goldfish")) {
            finish()
            return
        }
        val fault = intent.getStringExtra("fault")
        val button = Button(this).apply { text = "Run diagnostic" }
        setContentView(button)
        when (fault) {
            "block" -> button.setOnClickListener {
                Log.i(tag, "bounded_main_block_begin")
                Thread.sleep(7_500)
                Log.i(tag, "bounded_main_block_end")
            }
            "crash" -> handler.postDelayed({ error("Intentional emulator diagnostic") }, 500)
            "inspect" -> if (Build.VERSION.SDK_INT >= 30) {
                Thread({
                    val count = traceSize()
                    Log.i(tag, "trace_bytes_read=$count")
                }, "exit-trace-inspection").start()
            }
            else -> finish()
        }
    }

    @android.annotation.TargetApi(30)
    private fun traceSize(): Int? {
        val manager = getSystemService(ActivityManager::class.java)
        val record = manager.getHistoricalProcessExitReasons(packageName, 0, 5)
            .firstOrNull { it.processName.endsWith(":fault") } ?: return null
        return try {
            record.traceInputStream?.use { stream ->
                val bytes = ByteArray(4096)
                var total = 0
                while (total < 65_537) {
                    val read = stream.read(bytes, 0, minOf(bytes.size, 65_537 - total))
                    if (read <= 0) break
                    total += read
                }
                total
            }
        } catch (_: IOException) {
            null
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val tag = "LiveFaultHarness"
    }
}
