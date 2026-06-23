package com.ncmdump.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ncmdump.app.IFileService
import com.ncmdump.app.ncm.FlacTagInjectingStream
import com.ncmdump.app.ncm.NcmDecryptor
import com.ncmdump.app.ncm.NcmException
import com.ncmdump.app.ncm.TagWriter
import com.ncmdump.app.source.NcmFile
import com.ncmdump.app.source.NcmFileSource
import com.ncmdump.app.source.SafNcmSource
import com.ncmdump.app.source.ShizukuNcmSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

    data class UiState(
        val ncmSourceReady: Boolean = false,
        val ncmFolderName: String = "",
        val outputFolderUri: String? = null,
        val outputFolderName: String = "",
        val statusMessage: String = "",
        val isProcessing: Boolean = false,
        val progressText: String = "",
        val totalFilesFound: Int = 0,
        val filesProcessed: Int = 0,
        val filesSucceeded: Int = 0,
        val filesFailed: Int = 0,
        val conversionLog: List<String> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // The active NCM source — either SAF or Shizuku backed. Not persisted directly;
    // the Activity reconstructs it on launch (SAF from its tree URI, Shizuku after bind).
    @Volatile
    private var ncmSource: NcmFileSource? = null

    fun loadPreferences(prefs: PreferencesManager, context: Context) {
        val outUri = prefs.outputFolderUri
        _uiState.value = _uiState.value.copy(
            outputFolderUri = outUri,
            outputFolderName = resolveFolderName(context, outUri)
        )
        // Restore a previously chosen SAF source if one was saved. A Shizuku source
        // can only be rebuilt once the service is bound, so the Activity drives that.
        prefs.ncmFolderUri?.let { uriString ->
            runCatching { setNcmFolder(Uri.parse(uriString), context, prefs) }
        }
    }

    /** SAF backend (fallback / public directories). */
    fun setNcmFolder(uri: Uri, context: Context, prefs: PreferencesManager) {
        prefs.ncmFolderUri = uri.toString()
        applySource(SafNcmSource(context.applicationContext, uri))
    }

    /** Shizuku backend (Android/data). */
    fun setNcmSourceShizuku(service: IFileService, dirPath: String, prefs: PreferencesManager) {
        prefs.shizukuSourcePath = dirPath
        applySource(ShizukuNcmSource(service, dirPath))
    }

    private fun applySource(source: NcmFileSource) {
        ncmSource = source
        _uiState.value = _uiState.value.copy(
            ncmSourceReady = true,
            ncmFolderName = source.label,
            totalFilesFound = 0,
            statusMessage = "正在统计文件夹中的 NCM 文件..."
        )
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching { source.listNcmFiles().size }.getOrDefault(0)
            }
            // Ignore if the source changed underneath us while listing.
            if (ncmSource === source) {
                _uiState.value = _uiState.value.copy(
                    totalFilesFound = count,
                    statusMessage = ""
                )
            }
        }
    }

    fun setOutputFolder(uri: Uri, context: Context, prefs: PreferencesManager) {
        prefs.outputFolderUri = uri.toString()
        val doc = DocumentFile.fromTreeUri(context, uri)
        _uiState.value = _uiState.value.copy(
            outputFolderUri = uri.toString(),
            outputFolderName = doc?.name ?: uri.lastPathSegment ?: ""
        )
    }

    fun startConversion(context: Context, removeSource: Boolean = false) {
        val state = _uiState.value
        val source = ncmSource ?: run {
            _uiState.value = state.copy(statusMessage = "请先选择 NCM 文件来源")
            return
        }
        val outputFolderUri = state.outputFolderUri ?: run {
            _uiState.value = state.copy(statusMessage = "请先选择输出文件夹")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                statusMessage = "",
                progressText = "正在扫描 NCM 文件...",
                filesProcessed = 0,
                filesSucceeded = 0,
                filesFailed = 0,
                conversionLog = emptyList()
            )

            val log = mutableListOf<String>()

            val outputDoc = DocumentFile.fromTreeUri(context, Uri.parse(outputFolderUri))
            if (outputDoc == null || !outputDoc.exists()) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = "输出文件夹无法访问，请重新选择"
                )
                return@launch
            }

            val ncmFiles = withContext(Dispatchers.IO) {
                runCatching { source.listNcmFiles() }.getOrDefault(emptyList())
            }

            _uiState.value = _uiState.value.copy(
                totalFilesFound = ncmFiles.size,
                progressText = "找到 ${ncmFiles.size} 个 NCM 文件，开始转换..."
            )

            if (ncmFiles.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = "未找到任何 .ncm 文件（来源是否可访问？）"
                )
                return@launch
            }

            var succeeded = 0
            var failed = 0

            for ((index, file) in ncmFiles.withIndex()) {
                _uiState.value = _uiState.value.copy(
                    filesProcessed = index + 1,
                    progressText = "(${index + 1}/${ncmFiles.size}) ${file.name}"
                )

                try {
                    withContext(Dispatchers.IO) {
                        convertSingleFile(context, file, outputDoc)
                    }
                    succeeded++
                    log.add("✓ ${file.name.removeSuffix(".ncm")}")
                } catch (e: Exception) {
                    failed++
                    log.add("✗ ${file.name} — ${e.message ?: "未知错误"}")
                }

                _uiState.value = _uiState.value.copy(
                    filesSucceeded = succeeded,
                    filesFailed = failed,
                    conversionLog = log.toList()
                )

                if (removeSource) {
                    runCatching { file.delete() }
                }
            }

            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                statusMessage = "完成：成功 $succeeded 个，失败 $failed 个",
                progressText = ""
            )
        }
    }

    private fun convertSingleFile(
        context: Context,
        ncmFile: NcmFile,
        outputDir: DocumentFile
    ) {
        val fileName = ncmFile.name
        if (!fileName.endsWith(".ncm", ignoreCase = true)) {
            throw NcmException("不是 .ncm 文件")
        }
        val baseName = fileName.dropLast(4) // 去掉 ".ncm"

        val inputStream = ncmFile.openInputStream()

        // 第一步：只解析头部，不解密音频，先确定格式（mp3/flac）
        val decryptor = NcmDecryptor()
        inputStream.use {
            decryptor.parseHeader(it)

            val format = decryptor.probeFormat(it)
            val outputFileName = "$baseName.$format"
            val mimeType = if (format == "mp3") "audio/mpeg" else "audio/flac"

            // 删除同名旧文件（DocumentFile 不会自动覆盖）
            outputDir.findFile(outputFileName)?.delete()

            val outputFile = outputDir.createFile(mimeType, outputFileName)
                ?: throw NcmException("无法创建输出文件：$outputFileName")

            val outputStream = context.contentResolver.openOutputStream(outputFile.uri)
                ?: throw NcmException("无法写入输出文件")

            // 准备元数据和封面
            val meta = decryptor.parseMetadata()
            val image = decryptor.imageData
            val imageMime = decryptor.imageMimeType()

            outputStream.use { out ->
                if (format == "mp3") {
                    // MP3：先写 ID3v2 标签（含封面），再写音频帧
                    val id3 = TagWriter.buildMp3Id3Tag(meta, image, imageMime)
                    if (id3.isNotEmpty()) out.write(id3)
                    decryptor.decryptRemaining(it, out)
                } else {
                    // FLAC：用注入流，在 metadata 区插入 VORBIS_COMMENT + PICTURE
                    val blocks = TagWriter.buildFlacBlocks(meta, image, imageMime)
                    val injecting = FlacTagInjectingStream(out, blocks.vorbisComment, blocks.picture)
                    decryptor.decryptRemaining(it, injecting)
                    injecting.finish()
                    injecting.flush()
                }
            }
        }
    }

    private fun resolveFolderName(context: Context, uriString: String?): String {
        if (uriString == null) return ""
        return try {
            val uri = Uri.parse(uriString)
            DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: ""
        } catch (_: Exception) { "" }
    }
}
