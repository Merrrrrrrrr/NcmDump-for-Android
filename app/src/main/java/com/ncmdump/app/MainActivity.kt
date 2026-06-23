package com.ncmdump.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ncmdump.app.databinding.ActivityMainBinding
import com.ncmdump.app.shizuku.ShizukuManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    // 正确使用 viewModels() 委托，确保生命周期绑定正确
    private val viewModel: MainViewModel by viewModels()
    private lateinit var prefs: PreferencesManager
    private lateinit var shizuku: ShizukuManager

    companion object {
        private const val SHIZUKU_PERMISSION_CODE = 1001
    }

    // --- Shizuku lifecycle listeners ---
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshShizukuStatus() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizuku.onBinderDead()
        refreshShizukuStatus()
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_PERMISSION_CODE) {
                refreshShizukuStatus()
            }
        }

    private val ncmFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
            viewModel.setNcmFolder(uri, this, prefs)
        }
    }

    private val outputFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
            viewModel.setOutputFolder(uri, this, prefs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)
        shizuku = ShizukuManager(this)
        viewModel.loadPreferences(prefs, this)

        binding.etNcmPath.setText(prefs.shizukuSourcePath)

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        setupListeners()
        observeState()
        refreshShizukuStatus()
    }

    override fun onResume() {
        super.onResume()
        // Shizuku may have been started/authorized while we were backgrounded.
        refreshShizukuStatus()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    private fun setupListeners() {
        binding.btnShizukuGrant.setOnClickListener {
            when {
                !shizuku.isAvailable() ->
                    binding.tvShizukuStatus.text = "Shizuku：未安装或未运行，请先启动 Shizuku"
                shizuku.isPermissionGranted() -> refreshShizukuStatus()
                else -> shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
            }
        }

        binding.btnScanShizuku.setOnClickListener {
            scanViaShizuku()
        }

        binding.btnSelectNcmFolder.setOnClickListener {
            ncmFolderLauncher.launch(null)
        }

        binding.btnSelectOutputFolder.setOnClickListener {
            outputFolderLauncher.launch(null)
        }

        binding.btnStartConvert.setOnClickListener {
            val removeSource = binding.switchRemoveSource.isChecked
            viewModel.startConversion(this, removeSource)
        }
    }

    private fun scanViaShizuku() {
        val path = binding.etNcmPath.text.toString().trim()
        if (path.isEmpty()) {
            binding.tvShizukuStatus.text = "Shizuku：请填写 NCM 目录路径"
            return
        }
        lifecycleScope.launch {
            binding.tvShizukuStatus.text = "Shizuku：正在连接服务…"
            val service = shizuku.bind()
            if (service == null) {
                binding.tvShizukuStatus.text = "Shizuku：连接失败（未授权或服务未启动）"
                return@launch
            }
            refreshShizukuStatus()
            viewModel.setNcmSourceShizuku(service, path, prefs)
        }
    }

    private fun refreshShizukuStatus() {
        val available = shizuku.isAvailable()
        val granted = available && shizuku.isPermissionGranted()

        binding.tvShizukuStatus.text = when {
            !available -> "Shizuku：未运行（可改用下方公共目录模式）"
            !granted -> "Shizuku：已运行，待授权"
            else -> "Shizuku：已授权 ✓ 可扫描 Android/data"
        }
        binding.btnShizukuGrant.visibility =
            if (available && !granted) View.VISIBLE else View.GONE
        binding.etNcmPath.isEnabled = granted
        binding.btnScanShizuku.isEnabled = granted
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                // NCM source
                if (state.ncmFolderName.isNotEmpty()) {
                    binding.tvNcmFolder.text = state.ncmFolderName
                    binding.tvNcmFileCount.text =
                        if (state.totalFilesFound > 0) "找到 ${state.totalFilesFound} 个 NCM 文件"
                        else ""
                } else {
                    binding.tvNcmFolder.text = "未选择"
                    binding.tvNcmFileCount.text = ""
                }

                // Output folder
                binding.tvOutputFolder.text =
                    if (state.outputFolderName.isNotEmpty()) state.outputFolderName
                    else "未选择"

                // Convert button state
                binding.btnStartConvert.isEnabled =
                    state.ncmSourceReady &&
                            state.outputFolderUri != null &&
                            !state.isProcessing

                // Progress
                binding.progressBar.visibility =
                    if (state.isProcessing) View.VISIBLE else View.GONE
                binding.tvProgress.text = state.progressText

                // Status
                binding.tvStatus.text = state.statusMessage

                // Log
                binding.tvLog.text = state.conversionLog.joinToString("\n")
            }
        }
    }
}
