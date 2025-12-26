package com.example.vigia.feature.splash

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.Group
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vigia.feature.landing.LandingActivity
import com.example.vigia.R
import com.example.vigia.feature.splash.ui.FileStatusAdapter
import kotlinx.coroutines.launch

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private val vm: SplashViewModel by viewModels { SplashViewModelFactory(application) }

    // Startup UI
    private lateinit var groupStartup: Group
    private lateinit var txtStatus: TextView
    private lateinit var progressBar: ProgressBar

    // Download Panel UI
    private lateinit var panelDownload: CardView
    private lateinit var btnDownload: Button
    private lateinit var btnSkip: Button
    private lateinit var downloadProgress: ProgressBar
    private lateinit var progressCurrentFile: ProgressBar
    private lateinit var txtDownloadPercent: TextView
    private lateinit var txtCurrentFileName: TextView
    private lateinit var rvFileStatus: RecyclerView

    private lateinit var adapter: FileStatusAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        bindViews()
        setupList()
        bindViewModel()

        vm.start()
    }

    private fun bindViews() {
        // Group
        groupStartup = findViewById(R.id.groupStartup)

        // Startup
        txtStatus = findViewById(R.id.txtStatus)
        progressBar = findViewById(R.id.progressBar)

        // Download Panel
        panelDownload = findViewById(R.id.panelDownload)
        btnDownload = findViewById(R.id.btnDownload)
        btnSkip = findViewById(R.id.btnSkip)
        downloadProgress = findViewById(R.id.downloadProgress)
        progressCurrentFile = findViewById(R.id.progressCurrentFile)
        txtDownloadPercent = findViewById(R.id.txtDownloadPercent)
        txtCurrentFileName = findViewById(R.id.txtCurrentFileName)
        rvFileStatus = findViewById(R.id.rvFileStatus)

        btnSkip.setOnClickListener { vm.onSkipDownload() }
        btnDownload.setOnClickListener { vm.onStartDownload() }
    }

    private fun setupList() {
        adapter = FileStatusAdapter(emptyList())
        rvFileStatus.layoutManager = LinearLayoutManager(this)
        rvFileStatus.adapter = adapter
    }

    private fun bindViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    vm.uiState.collect { s ->
                        // Startup status/progress
                        txtStatus.text = s.statusText
                        setProgressSmooth(progressBar, s.startupProgress)

                        // Toggle panels (clean, single-source)
                        val showDownload = s.showDownloadPanel
                        groupStartup.visibility = if (showDownload) View.GONE else View.VISIBLE
                        panelDownload.visibility = if (showDownload) View.VISIBLE else View.GONE

                        // Buttons
                        btnDownload.isEnabled = s.downloadButtonsEnabled
                        btnSkip.isEnabled = s.downloadButtonsEnabled

                        // List + progress
                        adapter.submitList(s.files)

                        txtCurrentFileName.text = s.currentFileName
                        txtDownloadPercent.text = s.downloadPercentText
                        setProgressSmooth(progressCurrentFile, s.currentFileProgress)
                        setProgressSmooth(downloadProgress, s.totalProgress)
                    }
                }

                launch {
                    vm.events.collect { e ->
                        when (e) {
                            is SplashEvent.NavigateToLanding -> navigateToLanding(e.liteMode)
                            is SplashEvent.ToastMessage ->
                                Toast.makeText(this@SplashActivity, e.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun setProgressSmooth(pb: ProgressBar, progress: Int) {
        val anim = ObjectAnimator.ofInt(pb, "progress", pb.progress, progress)
        anim.duration = 300
        anim.interpolator = DecelerateInterpolator()
        anim.start()
    }

    private fun navigateToLanding(liteMode: Boolean) {
        val intent = Intent(this, LandingActivity::class.java)
        intent.putExtra("IS_LITE_MODE", liteMode)
        startActivity(intent)
        finish()
    }
}