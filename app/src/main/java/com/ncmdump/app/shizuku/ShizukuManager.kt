package com.ncmdump.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.ncmdump.app.IFileService
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Thin wrapper around the Shizuku binder + UserService lifecycle.
 *
 * Lifecycle expectations:
 *  - [isAvailable] / [isPermissionGranted] are cheap polls, safe to call from UI.
 *  - [requestPermission] triggers the system/Shizuku permission dialog; the caller must
 *    register an OnRequestPermissionResultListener with Shizuku to learn the outcome.
 *  - [bind] connects the shell-process FileService and returns its binder proxy.
 */
class ShizukuManager(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var service: IFileService? = null

    private var pending: ((IFileService?) -> Unit)? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, FileService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("ncmfile")
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = if (binder != null && binder.pingBinder()) {
                IFileService.Stub.asInterface(binder)
            } else null
            service = svc
            pending?.invoke(svc)
            pending = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Shizuku app installed, running, and a modern (v11+) binder is alive. */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    } catch (_: Throwable) {
        false
    }

    fun isPermissionGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun shouldShowRationale(): Boolean = try {
        Shizuku.shouldShowRequestPermissionRationale()
    } catch (_: Throwable) {
        false
    }

    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (_: Throwable) {
            // Binder may have died between availability check and request.
        }
    }

    /**
     * Bind (or reuse) the shell-process file service.
     * Returns null if Shizuku is unavailable, unauthorized, or the bind fails.
     */
    suspend fun bind(): IFileService? {
        service?.let { return it }
        if (!isAvailable() || !isPermissionGranted()) return null

        return suspendCancellableCoroutine { cont ->
            pending = { svc -> if (cont.isActive) cont.resume(svc) }
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (_: Throwable) {
                pending = null
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /** Currently bound service, if any (no implicit bind). */
    fun current(): IFileService? = service

    /** Drop the cached proxy after the Shizuku binder dies so the next bind reconnects. */
    fun onBinderDead() {
        service = null
    }
}
