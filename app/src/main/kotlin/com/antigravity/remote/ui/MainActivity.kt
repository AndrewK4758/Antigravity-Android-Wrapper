package com.antigravity.remote.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.antigravity.remote.R
import com.antigravity.remote.session.SessionUiState
import com.antigravity.remote.session.SessionViewModel
import com.antigravity.remote.session.UiEffect
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : AppCompatActivity() {

    private val viewModel: SessionViewModel by viewModels()

    private lateinit var geckoView: GeckoView
    private lateinit var errorOverlay: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: Button
    private lateinit var noticeBanner: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingPickerRequestId: String? = null

    private val singleFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val requestId = pendingPickerRequestId ?: return@registerForActivityResult
            pendingPickerRequestId = null
            viewModel.onFilePickerResult(requestId, uri?.let { listOf(it) })
        }

    private val multiFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val requestId = pendingPickerRequestId ?: return@registerForActivityResult
            pendingPickerRequestId = null
            viewModel.onFilePickerResult(requestId, uris.takeIf { it.isNotEmpty() })
        }

    private val geckoViewPort = object : SessionHostViewPort<GeckoSession> {
        override fun currentAttachedSession(): GeckoSession? = geckoView.session

        override fun releaseAttachedSession() {
            geckoView.releaseSession()
        }

        override fun attachSession(session: GeckoSession) {
            geckoView.setSession(session)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        pendingPickerRequestId = savedInstanceState?.getString(KEY_PENDING_PICKER_REQUEST_ID)

        val rootContainer = findViewById<View>(R.id.root_container)
        geckoView = findViewById(R.id.gecko_view)
        errorOverlay = findViewById(R.id.error_overlay)
        errorMessage = findViewById(R.id.error_message)
        retryButton = findViewById(R.id.retry_button)
        noticeBanner = findViewById(R.id.notice_banner)

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val mask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            val barsAndIme = insets.getInsets(mask)
            view.setPadding(barsAndIme.left, barsAndIme.top, barsAndIme.right, barsAndIme.bottom)
            insets
        }

        retryButton.setOnClickListener { viewModel.onRetry() }
        noticeBanner.setOnClickListener { noticeBanner.visibility = View.GONE }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    viewModel.onBackPressed()
                }
            },
        )

        observeViewModel()
        viewModel.initializeIfNeeded(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.onWarmIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val attached = GeckoViewAttachmentHelper.rebindIfNeeded(
            geckoViewPort,
            viewModel.currentDisplaySession(),
        )
        viewModel.verifySessionAttachedOnStart(attached)
    }

    override fun onStop() {
        GeckoViewAttachmentHelper.detachOnStop(geckoViewPort)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPickerRequestId?.let { outState.putString(KEY_PENDING_PICKER_REQUEST_ID, it) }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state -> renderState(state) }
                }
                launch {
                    viewModel.displaySession.collect { session ->
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            GeckoViewAttachmentHelper.rebindIfNeeded(geckoViewPort, session)
                        }
                    }
                }
                launch {
                    viewModel.effects.collect { effect -> handleEffect(effect) }
                }
            }
        }
    }

    private fun renderState(state: SessionUiState) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            GeckoViewAttachmentHelper.rebindIfNeeded(geckoViewPort, viewModel.currentDisplaySession())
        }
        if (state is SessionUiState.Error) {
            geckoView.visibility = View.GONE
            errorOverlay.visibility = View.VISIBLE
            errorMessage.text = state.message
        } else {
            errorOverlay.visibility = View.GONE
            geckoView.visibility = View.VISIBLE
        }
    }

    private fun handleEffect(effect: UiEffect) {
        when (effect) {
            is UiEffect.OpenExternalUrl -> {
                ExternalActivityRouter.launchExternalUrl(effect.url, viewModel.coordinator) { url ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            is UiEffect.LaunchDocumentPicker -> {
                pendingPickerRequestId = effect.requestId
                val mimeArray = if (effect.mimeTypes.isEmpty()) arrayOf("*/*") else effect.mimeTypes.toTypedArray()
                if (effect.allowMultiple) {
                    multiFileLauncher.launch(mimeArray)
                } else {
                    singleFileLauncher.launch(mimeArray)
                }
            }
            is UiEffect.ShowNonFatalNotice -> showNotice(effect.message)
            UiEffect.CloseApp -> finish()
        }
    }

    private fun showNotice(message: String) {
        noticeBanner.text = message
        noticeBanner.visibility = View.VISIBLE
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ noticeBanner.visibility = View.GONE }, NOTICE_TIMEOUT_MS)
    }

    private companion object {
        const val KEY_PENDING_PICKER_REQUEST_ID = "pending_picker_request_id"
        const val NOTICE_TIMEOUT_MS = 4000L
    }
}
