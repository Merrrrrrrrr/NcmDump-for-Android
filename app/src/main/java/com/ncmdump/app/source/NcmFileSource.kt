package com.ncmdump.app.source

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.ncmdump.app.IFileService
import com.ncmdump.app.ncm.NcmException
import java.io.InputStream

/**
 * A single NCM file exposed uniformly regardless of backend.
 * [openInputStream] yields a forward-only stream suitable for NcmDecryptor.
 */
class NcmFile(
    val name: String,
    private val opener: () -> InputStream,
    private val deleter: () -> Boolean,
) {
    fun openInputStream(): InputStream = opener()
    fun delete(): Boolean = deleter()
}

/**
 * Abstraction over "where the .ncm files live". Two backends:
 *  - [SafNcmSource]: Storage Access Framework / DocumentFile — works for public dirs
 *    (the fallback when Shizuku is unavailable).
 *  - [ShizukuNcmSource]: shell-process file service — the only way to reach Android/data.
 *
 * [listNcmFiles] does blocking I/O; call it off the main thread.
 */
interface NcmFileSource {
    val label: String
    fun listNcmFiles(): List<NcmFile>
}

/** SAF backend — preserves the original DocumentFile behavior (non-recursive). */
class SafNcmSource(
    private val context: Context,
    private val treeUri: Uri,
) : NcmFileSource {

    private val root: DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    override val label: String
        get() = root?.name ?: treeUri.lastPathSegment ?: ""

    override fun listNcmFiles(): List<NcmFile> {
        val dir = root ?: return emptyList()
        return dir.listFiles()
            .filter { it.isFile && it.name?.endsWith(".ncm", ignoreCase = true) == true }
            .map { doc ->
                NcmFile(
                    name = doc.name ?: "unknown.ncm",
                    opener = {
                        context.contentResolver.openInputStream(doc.uri)
                            ?: throw NcmException("无法读取文件")
                    },
                    deleter = { runCatching { doc.delete() }.getOrDefault(false) },
                )
            }
    }
}

/** Shizuku backend — path-based access to Android/data via the shell-process service. */
class ShizukuNcmSource(
    private val service: IFileService,
    dirPath: String,
) : NcmFileSource {

    private val dir = dirPath.trimEnd('/')

    override val label: String
        get() = dir.substringAfterLast('/').ifEmpty { dir }

    override fun listNcmFiles(): List<NcmFile> {
        val names = runCatching { service.listNcmNames(dir) }.getOrNull() ?: return emptyList()
        return names.map { name ->
            val full = "$dir/$name"
            NcmFile(
                name = name,
                opener = {
                    val pfd = service.openRead(full)
                        ?: throw NcmException("无法打开文件：$name")
                    ParcelFileDescriptor.AutoCloseInputStream(pfd)
                },
                deleter = { runCatching { service.deleteFile(full) }.getOrDefault(false) },
            )
        }
    }
}
