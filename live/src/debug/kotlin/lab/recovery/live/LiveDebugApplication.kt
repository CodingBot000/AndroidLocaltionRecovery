package lab.recovery.live

import android.app.Application
import android.os.StrictMode

class LiveDebugApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
            .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build())
    }
}
