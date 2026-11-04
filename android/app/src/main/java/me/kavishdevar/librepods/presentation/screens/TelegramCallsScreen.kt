package me.kavishdevar.librepods.presentation.screens

import android.util.Base64
import android.os.Handler
import android.os.Looper
import android.Manifest
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.DeepFilterMicProcessor
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel

private const val TELEGRAM_URL = "https://web.telegram.org/k/"
private const val TELEGRAM_ORIGIN = "https://web.telegram.org"

@Composable
fun TelegramCallsScreen(viewModel: AirPodsViewModel) {
    val context = LocalContext.current
    val micState by viewModel.hiResRecordingState.collectAsState()
    var micActive by remember { mutableStateOf(false) }
    var deepFilterEnabled by rememberSaveable { mutableStateOf(false) }
    var deepFilterStatus by remember { mutableStateOf(DeepFilterMicProcessor.Status.DISABLED) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pendingCameraRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    val webViewRef = remember { AtomicReference<WebView?>(null) }
    val currentMicActive by rememberUpdatedState(micActive)
    val currentWebView by rememberUpdatedState(webView)
    val pcmBatcher = remember {
        PcmBatcher { pcm ->
            val target = webViewRef.get() ?: return@PcmBatcher
            val encoded = Base64.encodeToString(pcm, Base64.NO_WRAP)
            target.evaluateJavascript("window.__lpPushPcm && window.__lpPushPcm('$encoded')", null)
        }
    }
    val deepFilter = remember(context, pcmBatcher) {
        DeepFilterMicProcessor(context, onPcm = pcmBatcher::offer, onStatus = { status ->
            Handler(Looper.getMainLooper()).post { deepFilterStatus = status }
        })
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingCameraRequest
        pendingCameraRequest = null
        if (request != null) {
            val appHasCameraPermission = granted || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val grantable = request.resources.filter { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> currentMicActive
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> appHasCameraPermission
                    else -> false
                }
            }
            if (grantable.size == request.resources.size) request.grant(grantable.toTypedArray()) else request.deny()
        }
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    DisposableEffect(viewModel) {
        onDispose {
            if (currentMicActive) viewModel.stopTelegramHiResMic()
            webViewRef.set(null)
            currentWebView?.apply {
                stopLoading()
                destroy()
            }
            pcmBatcher.close()
            deepFilter.close()
            pendingCameraRequest?.deny()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.telegram_calls_mic_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        micState.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            StyledButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (micActive) {
                        viewModel.stopTelegramHiResMic()
                        micActive = false
                    } else {
                        pcmBatcher.clear()
                        micActive = viewModel.startTelegramHiResMic(deepFilter::offer)
                    }
                },
                backdrop = rememberLayerBackdrop(),
                surfaceColor = if (micActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ) {
                Text(
                    stringResource(if (micActive) R.string.telegram_calls_stop_mic else R.string.telegram_calls_start_mic),
                    color = if (micActive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.telegram_calls_deep_filter), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = when (deepFilterStatus) {
                        DeepFilterMicProcessor.Status.LOADING -> stringResource(R.string.telegram_calls_filter_loading)
                        DeepFilterMicProcessor.Status.FAILED -> stringResource(R.string.telegram_calls_filter_error)
                        else -> stringResource(R.string.telegram_calls_deep_filter_description)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (deepFilterStatus == DeepFilterMicProcessor.Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = deepFilterEnabled,
                onCheckedChange = { enabled ->
                    deepFilterEnabled = enabled
                    deepFilter.setEnabled(enabled)
                }
            )
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(this, HI_RES_AUDIO_SHIM, setOf(TELEGRAM_ORIGIN))
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) {
                            val isTelegram = request.origin.toString().trimEnd('/') == TELEGRAM_ORIGIN
                            val supported = request.resources.isNotEmpty() && request.resources.all {
                                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                            }
                            if (!isTelegram || !supported) {
                                request.deny()
                                return
                            }
                            val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && !cameraGranted) {
                                pendingCameraRequest?.deny()
                                pendingCameraRequest = request
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                return
                            }
                            val grantable = request.resources.filter { resource ->
                                when (resource) {
                                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> currentMicActive
                                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> cameraGranted
                                    else -> false
                                }
                            }
                            if (grantable.size == request.resources.size) request.grant(grantable.toTypedArray()) else request.deny()
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !isTelegramHost(request.url.host)

                    }
                    webView = this
                    webViewRef.set(this)
                    loadUrl(TELEGRAM_URL)
                }
            },
            update = {
                webView = it
                webViewRef.set(it)
            }
        )
    }
}

private fun isTelegramHost(host: String?): Boolean =
    host == "telegram.org" || host?.endsWith(".telegram.org") == true || host == "t.me" || host?.endsWith(".t.me") == true

/** Coalesces decoder frames before crossing the WebView boundary. */
private class PcmBatcher(private val deliver: (ByteArray) -> Unit) {
    private val lock = Any()
    private val chunks = ArrayDeque<ByteArray>()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingBytes = 0
    private var closed = false
    private val flush = Runnable {
        val batch = synchronized(lock) {
            if (closed || pendingBytes == 0) return@Runnable
            val output = ByteArrayOutputStream(pendingBytes)
            while (chunks.isNotEmpty()) output.write(chunks.removeFirst())
            pendingBytes = 0
            output.toByteArray()
        }
        deliver(batch)
    }

    fun offer(pcm: ByteArray) {
        synchronized(lock) {
            if (closed) return
            val copy = pcm.copyOf()
            chunks.addLast(copy)
            pendingBytes += copy.size
            // Keep at most about half a second if the UI thread is temporarily busy.
            while (pendingBytes > MAX_PENDING_BYTES && chunks.isNotEmpty()) {
                pendingBytes -= chunks.removeFirst().size
            }
        }
        handler.removeCallbacks(flush)
        handler.postDelayed(flush, FLUSH_DELAY_MS)
    }

    fun clear() {
        synchronized(lock) {
            chunks.clear()
            pendingBytes = 0
        }
        handler.removeCallbacks(flush)
    }

    fun close() {
        synchronized(lock) {
            closed = true
            chunks.clear()
            pendingBytes = 0
        }
        handler.removeCallbacks(flush)
    }

    private companion object {
        const val FLUSH_DELAY_MS = 24L
        const val MAX_PENDING_BYTES = 32_000
    }
}

private val HI_RES_AUDIO_SHIM = """
(() => {
  const md = navigator.mediaDevices;
  if (!md || !md.getUserMedia) return;
  const nativeGetUserMedia = md.getUserMedia.bind(md);
  let context;
  let node;
  let legacyProcessor;
  let legacyQueue = new Float32Array(0);
  let legacyPosition = 0;
  let pending = new Float32Array(0);
  const MAX_PENDING_SAMPLES = 16000;
  window.__lpPushPcm = encoded => {
    const raw = atob(encoded);
    const input = new Float32Array(raw.length >> 1);
    for (let i = 0; i < input.length; i++) {
      let sample = raw.charCodeAt(i * 2) | (raw.charCodeAt(i * 2 + 1) << 8);
      if (sample & 0x8000) sample -= 0x10000;
      input[i] = sample / 32768;
    }
    if (node) {
      node.port.postMessage(input.buffer, [input.buffer]);
    } else if (legacyProcessor) {
      const available = legacyQueue.subarray(legacyPosition);
      const combined = new Float32Array(available.length + input.length);
      combined.set(available);
      combined.set(input, available.length);
      legacyQueue = combined.length > MAX_PENDING_SAMPLES ? combined.slice(-MAX_PENDING_SAMPLES) : combined;
      legacyPosition = 0;
    } else {
      const combined = new Float32Array(pending.length + input.length);
      combined.set(pending);
      combined.set(input, pending.length);
      pending = combined.length > MAX_PENDING_SAMPLES ? combined.slice(-MAX_PENDING_SAMPLES) : combined;
    }
  };
  md.getUserMedia = async constraints => {
    if (!constraints || !constraints.audio) return nativeGetUserMedia(constraints);
    context = context || new AudioContext();
    await context.resume();
    const destination = context.createMediaStreamDestination();
    const moduleSource = `
      class LibrePodsInput extends AudioWorkletProcessor {
        constructor() {
          super();
          this.source = new Float32Array(0);
          this.position = 0;
          this.ready = false;
          this.port.onmessage = event => {
            const input = new Float32Array(event.data);
            const unread = this.source.subarray(this.position | 0);
            const combined = new Float32Array(unread.length + input.length);
            combined.set(unread);
            combined.set(input, unread.length);
            this.source = combined.length > 16000 ? combined.slice(-16000) : combined;
            this.position = 0;
          };
        }
        process(_inputs, outputs) {
          const output = outputs[0][0];
          output.fill(0);
          if (!this.ready) {
            if (this.source.length < 9600) return true;
            this.ready = true;
          }
          const step = 64000 / sampleRate;
          let written = 0;
          while (written < output.length && (this.position | 0) + 1 < this.source.length) {
            const index = this.position | 0;
            const fraction = this.position - index;
            const first = this.source[index];
            output[written++] = first + (this.source[index + 1] - first) * fraction;
            this.position += step;
          }
          const consumed = this.position | 0;
          if (consumed > 0) {
            this.source = this.source.slice(consumed);
            this.position -= consumed;
          }
          if (written < output.length) this.ready = false;
          return true;
        }
      }
      registerProcessor('librepods-hires-input', LibrePodsInput);
    `;
    const mute = context.createGain();
    mute.gain.value = 0;
    try {
      const moduleUrl = URL.createObjectURL(new Blob([moduleSource], { type: 'application/javascript' }));
      await context.audioWorklet.addModule(moduleUrl);
      URL.revokeObjectURL(moduleUrl);
      node = new AudioWorkletNode(context, 'librepods-hires-input', {
        numberOfInputs: 0,
        numberOfOutputs: 1,
        outputChannelCount: [1]
      });
      node.connect(destination);
      node.connect(mute);
      if (pending.length) node.port.postMessage(pending.buffer, [pending.buffer]);
      pending = new Float32Array(0);
    } catch (_error) {
      legacyProcessor = context.createScriptProcessor(1024, 1, 1);
      legacyProcessor.onaudioprocess = event => {
        const output = event.outputBuffer.getChannelData(0);
        const step = 64000 / context.sampleRate;
        for (let i = 0; i < output.length; i++) {
          const index = legacyPosition | 0;
          const fraction = legacyPosition - index;
          const a = legacyQueue[index] || 0;
          const b = legacyQueue[index + 1] || 0;
          output[i] = a + (b - a) * fraction;
          legacyPosition += step;
        }
        const consumed = legacyPosition | 0;
        legacyQueue = legacyQueue.slice(consumed);
        legacyPosition -= consumed;
      };
      legacyProcessor.connect(destination);
      legacyProcessor.connect(mute);
      legacyQueue = pending;
      pending = new Float32Array(0);
    }
    mute.connect(context.destination);
    const stream = destination.stream;
    if (constraints.video) {
      const videoStream = await nativeGetUserMedia({ audio: false, video: constraints.video });
      videoStream.getVideoTracks().forEach(track => stream.addTrack(track));
    }
    return stream;
  };
})();
""".trimIndent()
