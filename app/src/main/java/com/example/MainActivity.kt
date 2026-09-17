package com.example

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme
import android.util.Base64
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
          AppContent(modifier = Modifier.padding(innerPadding))
        }
      }
    }
  }
}

class WebAppInterface(
    private val onScanRequested: () -> Unit,
    private val onChatRequested: (String) -> Unit
) {
    @JavascriptInterface
    fun startArScan() {
        onScanRequested()
    }

    @JavascriptInterface
    fun sendChatMessage(message: String) {
        onChatRequested(message)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppContent(modifier: Modifier = Modifier) {
    val coroutineScope = rememberCoroutineScope()
    var webViewRef: WebView? = null

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            Toast.makeText(webViewRef?.context, "Analyzing layout...", Toast.LENGTH_SHORT).show()
            coroutineScope.launch {
                val jsonResult = analyzeWorksheet(bitmap)
                // Escape string for JS
                val escapedJson = jsonResult.replace("\"", "\\\"").replace("\n", "")
                
                webViewRef?.post {
                    webViewRef?.evaluateJavascript("updateFromScan(\"$escapedJson\");", null)
                }
            }
        }
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                addJavascriptInterface(
                    WebAppInterface(
                        onScanRequested = {
                            try {
                                takePictureLauncher.launch(null)
                            } catch (e: Exception) {
                                Toast.makeText(context, "No camera app available", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onChatRequested = { message ->
                            coroutineScope.launch {
                                val result = sendChatToGemini(message)
                                val b64Chat = Base64.encodeToString(result.first.toByteArray(), Base64.NO_WRAP)
                                val b64Json = Base64.encodeToString(result.second.toByteArray(), Base64.NO_WRAP)
                                webViewRef?.post {
                                    webViewRef?.evaluateJavascript("receiveChatMessage(\"$b64Chat\", \"$b64Json\");", null)
                                }
                            }
                        }
                    ), 
                    "Android"
                )
                loadUrl("file:///android_asset/index.html")
                webViewRef = this
            }
        },
        modifier = modifier.fillMaxSize()
    )
}

