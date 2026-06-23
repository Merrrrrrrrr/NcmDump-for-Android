package com.ncmdump.app.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import com.ncmdump.app.IFileService
import java.io.File
import kotlin.system.exitProcess

/**
 * Shizuku UserService implementation.
 *
 * This class is loaded into a separate process spawned by the Shizuku server and
 * therefore runs with the shell uid (2000, when Shizuku was started via ADB) or root.
 * The shell uid is exempt from the scoped-storage block on Android/data, so plain
 * java.io.File access here can read the honor-music NCM directory.
 *
 * IMPORTANT: this runs in a process WITHOUT the app's normal Android context/permissions.
 * Only use filesystem APIs and what is passed across the binder.
 */
class FileService() : IFileService.Stub() {

    // Shizuku may instantiate via either the no-arg or the (Context) constructor.
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    override fun isDirectory(path: String): Boolean =
        try { File(path).isDirectory } catch (_: Throwable) { false }

    override fun listNcmNames(dirPath: String): Array<String> {
        return try {
            val dir = File(dirPath)
            val files = dir.listFiles { f ->
                f.isFile && f.name.endsWith(".ncm", ignoreCase = true)
            } ?: return emptyArray()
            files.map { it.name }.sorted().toTypedArray()
        } catch (_: Throwable) {
            emptyArray()
        }
    }

    override fun openRead(filePath: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(filePath), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun deleteFile(filePath: String): Boolean =
        try { File(filePath).delete() } catch (_: Throwable) { false }
}
