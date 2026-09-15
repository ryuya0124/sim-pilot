package dev.simpilot

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ShellUserServiceManager {
    @Volatile private var service: IShellService? = null
    @Volatile private var latch = CountDownLatch(1)
    @Volatile private var binding = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellUserService::class.java.name))
            .processNameSuffix("sim_shell")
            .tag("sim-pilot-shell-v6")
            .version(6)
            .daemon(false)
            .debuggable(BuildConfig.DEBUG)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IShellService.Stub.asInterface(binder)
            binding = false
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            latch = CountDownLatch(1)
        }
    }

    @Synchronized
    private fun beginBind() {
        if (service != null || binding) return
        binding = true
        latch = CountDownLatch(1)
        Shizuku.bindUserService(args, connection)
    }

    fun requireService(): IShellService {
        service?.let { return it }
        beginBind()
        check(latch.await(12, TimeUnit.SECONDS)) { "Shizuku UserServiceの起動がタイムアウトしました" }
        return checkNotNull(service) { "Shizuku UserServiceへ接続できません" }
    }
}
