package io.github.a13e300.ksuwebui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.MarginLayoutParams
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.webkit.WebViewAssetLoader
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.File
import android.view.ViewGroup.LayoutParams.MATCH_PARENT

@SuppressLint("SetJavaScriptEnabled")
class WebUIActivity : ComponentActivity(), FileSystemService.Listener {
    private lateinit var webviewInterface: WebViewInterface

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var moduleDir: String

    override fun onCreate(savedInstanceState: Bundle?) {
        // Disable edge to edge to prevent status bar from overlaying content
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        super.onCreate(savedInstanceState)

        val moduleId = intent.getStringExtra("id")
        if (moduleId == null) {
            finish()
            return
        }
        val name = intent.getStringExtra("name") ?: moduleId
        if (name.isNotEmpty()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                setTaskDescription(ActivityManager.TaskDescription(name))
            } else {
                val taskDescription = ActivityManager.TaskDescription.Builder().setLabel(name).build()
                setTaskDescription(taskDescription)
            }
        }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        WebView.setWebContentsDebuggingEnabled(prefs.getBoolean("enable_web_debugging", BuildConfig.DEBUG))

        moduleDir = "/data/adb/modules/$moduleId"

        // Create progress bar
        progressBar = ProgressBar(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, 8)
        }

        webView = WebView(this).apply {
            setBackgroundColor(0xFFFFFFFF)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val inset = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updateLayoutParams<MarginLayoutParams> {
                    leftMargin = inset.left
                    rightMargin = inset.right
                    topMargin = inset.top
                    bottomMargin = inset.bottom
                }
                return@setOnApplyWindowInsetsListener insets
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webviewInterface = WebViewInterface(this@WebUIActivity, this, moduleDir)
        }

        // Set content view with progress bar
        setContentView(webView)
        addContentView(progressBar, ViewGroup.LayoutParams(MATCH_PARENT, 8))
        FileSystemService.start(this)
    }

    private fun setupWebview(fs: FileSystemManager) {
        val webRoot = File("$moduleDir/webroot")
        val webViewAssetLoader = WebViewAssetLoader.Builder()
            .setDomain("mui.kernelsu.org")
            .addPathHandler(
                "/",
                RemoteFsPathHandler(
                    this,
                    webRoot,
                    fs
                )
            )
            .build()
        val webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return webViewAssetLoader.shouldInterceptRequest(request.url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                progressBar.visibility = android.view.View.GONE
                // Show error page
                val errorHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <style>
                            body { font-family: sans-serif; padding: 20px; background: white; color: black; }
                            .error { color: red; font-size: 18px; margin-bottom: 10px; }
                            .info { color: gray; font-size: 14px; }
                        </style>
                    </head>
                    <body>
                        <div class="error">网页加载失败</div>
                        <div class="info">错误: ${error?.description}</div>
                        <div class="info">请检查模块的 webroot 目录是否存在</div>
                    </body>
                    </html>
                """.trimIndent()
                view?.loadData(errorHtml, "text/html", "UTF-8")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = android.view.View.GONE
                // Inject CSS to fix dark theme contrast issue
                view?.evaluateJavascript(
                    """
                    (function() {
                        var style = document.createElement('style');
                        style.textContent = `
                            :root {
                                color-scheme: light dark;
                            }
                            body {
                                background-color: #ffffff !important;
                                color: #000000 !important;
                            }
                        `;
                        document.head.appendChild(style);
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
        val webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
            }
        }
        webView.apply {
            addJavascriptInterface(webviewInterface, "ksu")
            setWebViewClient(webViewClient)
            setWebChromeClient(webChromeClient)
            loadUrl("https://mui.kernelsu.org/index.html")
        }
    }

    override fun onServiceAvailable(fs: FileSystemManager) {
        setupWebview(fs)
    }

    override fun onLaunchFailed() {
        Toast.makeText(this, R.string.please_grant_root, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        FileSystemService.removeListener(this)
    }
}
