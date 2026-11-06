package me.kavishdevar.librepods.presentation.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.DeepFilterMicProcessor
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "TelegramCallsScreen"
private const val TELEGRAM_URL = "https://web.telegram.org/k/"
private const val TELEGRAM_ORIGIN = "https://web.telegram.org"
private const val AUDIO_PORT_MESSAGE = "__librepods_audio_port_v1__"

@Composable
fun TelegramCallsScreen(viewModel: AirPodsViewModel) {
    val context = LocalContext.current
    val micState by viewModel.hiResRecordingState.collectAsState()
    var micActive by remember { mutableStateOf(false) }
    var deepFilterEnabled by rememberSaveable { mutableStateOf(false) }
    var deepFilterStatus by remember { mutableStateOf(DeepFilterMicProcessor.Status.DISABLED) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pendingCaptureRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    val playbackRouteRecovery = remember(context) { AirPodsPlaybackRouteRecovery(context) }
    val webViewRef = remember { AtomicReference<WebView?>(null) }
    val audioPortRef = remember { AtomicReference<WebMessagePortCompat?>(null) }
    val currentMicActive by rememberUpdatedState(micActive)
    val currentWebView by rememberUpdatedState(webView)

    // DeepFilterMicProcessor now emits fixed-rate 48 kHz mono PCM16. Send the
    // byte array as a WebMessage ArrayBuffer; this avoids Base64 expansion and atob.
    val deepFilter = remember(context, audioPortRef) {
        DeepFilterMicProcessor(
            context = context,
            onPcm = { pcm ->
                val port = audioPortRef.get()
                if (port != null) {
                    try {
                        port.postMessage(WebMessageCompat(pcm))
                    } catch (t: Throwable) {
                        Log.w(TAG, "Could not deliver binary PCM to Telegram WebView", t)
                        if (audioPortRef.compareAndSet(port, null)) {
                            runCatching { port.close() }
                        }
                    }
                }
            },
            onStatus = { status ->
                Handler(Looper.getMainLooper()).post { deepFilterStatus = status }
            }
        )
    }

    // Telegram can use either the high-resolution AACP stream or Android's normal
    // microphone. Ask for the Android runtime permissions only for the normal path.
    val capturePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val request = pendingCaptureRequest
        pendingCaptureRequest = null
        if (request != null) {
            val appHasMicPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val appHasCameraPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            val grantable = request.resources.filter { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> currentMicActive || appHasMicPermission
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
            if (currentMicActive) {
                viewModel.stopTelegramHiResMic()
                playbackRouteRecovery.refreshAfterHiResCapture()
            }
            webViewRef.set(null)
            audioPortRef.getAndSet(null)?.let { runCatching { it.close() } }
            currentWebView?.apply {
                stopLoading()
                destroy()
            }
            deepFilter.close()
            pendingCaptureRequest?.deny()
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
                        playbackRouteRecovery.refreshAfterHiResCapture()
                    } else {
                        // Capture the current route before AACP capture can disturb Android's
                        // Bluetooth communication routing.
                        playbackRouteRecovery.captureBeforeHiResCapture()
                        micActive = viewModel.startTelegramHiResMic(deepFilter::offer)
                        if (!micActive) playbackRouteRecovery.clearSnapshot()
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

                    val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    if (documentStartSupported) {
                        WebViewCompat.addDocumentStartJavaScript(this, HI_RES_AUDIO_SHIM, setOf(TELEGRAM_ORIGIN))
                    } else {
                        Log.e(TAG, "WebView does not support DOCUMENT_START_SCRIPT; synthetic mic cannot be installed")
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
                            val needsAudioPermission = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                                !currentMicActive && ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) != PackageManager.PERMISSION_GRANTED
                            val needsCameraPermission = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                            val missingPermissions = buildList {
                                if (needsAudioPermission) add(Manifest.permission.RECORD_AUDIO)
                                if (needsCameraPermission) add(Manifest.permission.CAMERA)
                            }
                            if (missingPermissions.isNotEmpty()) {
                                pendingCaptureRequest?.deny()
                                pendingCaptureRequest = request
                                capturePermissionLauncher.launch(missingPermissions.distinct().toTypedArray())
                                return
                            }
                            val appHasMicPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            val cameraGranted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            val grantable = request.resources.filter { resource ->
                                when (resource) {
                                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> currentMicActive || appHasMicPermission
                                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> cameraGranted
                                    else -> false
                                }
                            }
                            if (grantable.size == request.resources.size) request.grant(grantable.toTypedArray()) else request.deny()
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            audioPortRef.getAndSet(null)?.let { runCatching { it.close() } }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (Uri.parse(url).host == "web.telegram.org") {
                                attachPcmMessageChannel(view, audioPortRef)
                                view.evaluateJavascript("window.__lpSetHiResMicEnabled && window.__lpSetHiResMicEnabled(${currentMicActive});", null)
                            }
                        }

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
                it.evaluateJavascript("window.__lpSetHiResMicEnabled && window.__lpSetHiResMicEnabled(${currentMicActive});", null)
            }
        )
    }
}

private fun attachPcmMessageChannel(
    webView: WebView,
    portRef: AtomicReference<WebMessagePortCompat?>
) {
    val requiredFeatures = listOf(
        WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL,
        WebViewFeature.POST_WEB_MESSAGE,
        WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE,
        WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK,
        WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER
    )
    val missing = requiredFeatures.filterNot { WebViewFeature.isFeatureSupported(it) }
    if (missing.isNotEmpty()) {
        Log.e(TAG, "Binary WebView PCM transport unavailable; unsupported features: $missing")
        return
    }

    var nativePortToClose: WebMessagePortCompat? = null
    var javascriptPortToClose: WebMessagePortCompat? = null
    try {
        val channel = WebViewCompat.createWebMessageChannel(webView)
        val nativePort = channel[0]
        val javascriptPort = channel[1]
        nativePortToClose = nativePort
        javascriptPortToClose = javascriptPort
        nativePort.setWebMessageCallback(object : WebMessagePortCompat.WebMessageCallbackCompat() {
            override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                if (message?.type == WebMessageCompat.TYPE_STRING && message.data == "ready") {
                    Log.i(TAG, "Telegram binary PCM message channel is ready")
                }
            }
        })

        portRef.getAndSet(nativePort)?.let { runCatching { it.close() } }
        WebViewCompat.postWebMessage(
            webView,
            WebMessageCompat(AUDIO_PORT_MESSAGE, arrayOf(javascriptPort)),
            Uri.parse(TELEGRAM_ORIGIN)
        )
        // Ownership of the JavaScript endpoint was transferred to the page.
        javascriptPortToClose = null
    } catch (t: Throwable) {
        nativePortToClose?.let { port ->
            portRef.compareAndSet(port, null)
            runCatching { port.close() }
        }
        javascriptPortToClose?.let { port -> runCatching { port.close() } }
        Log.e(TAG, "Could not establish binary PCM message channel", t)
    }
}

/**
 * Best-effort recovery for Android's communication route after AACP capture.
 *
 * EarPort can cycle a PipeWire/PulseAudio sink with pactl. An ordinary Android
 * application cannot globally disconnect/reselect the Bluetooth A2DP media sink.
 * The public Android API only lets an app clear/reselect its communication route,
 * so this class snapshots that route before capture, clears it on stop, waits 500 ms,
 * and restores it if it is still available.
 */
private class AirPodsPlaybackRouteRecovery(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var snapshot: RouteSnapshot? = null
    private var pendingRestore: Runnable? = null

    private data class RouteSnapshot(
        val mode: Int,
        val communicationDeviceId: Int?,
        val communicationDeviceType: Int?,
        val communicationDeviceAddress: String?,
        val communicationDeviceName: String?
    )

    fun captureBeforeHiResCapture() {
        val audioManager = audioManager()
        val communicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice
        } else {
            null
        }
        snapshot = RouteSnapshot(
            mode = audioManager.mode,
            communicationDeviceId = communicationDevice?.id,
            communicationDeviceType = communicationDevice?.type,
            communicationDeviceAddress = communicationDevice?.address,
            communicationDeviceName = communicationDevice?.productName?.toString()
        )
        val bluetoothOutputs = bluetoothOutputNames(audioManager)
        Log.i(
            TAG,
            "Captured audio route before hi-res mic: mode=${audioManager.mode}, " +
                "communicationDevice=${communicationDevice?.productName}, bluetoothOutputs=$bluetoothOutputs"
        )
    }

    fun clearSnapshot() {
        snapshot = null
    }

    fun refreshAfterHiResCapture() {
        val audioManager = audioManager()
        val previous = snapshot ?: RouteSnapshot(
            mode = audioManager.mode,
            communicationDeviceId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.communicationDevice?.id else null,
            communicationDeviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.communicationDevice?.type else null,
            communicationDeviceAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.communicationDevice?.address else null,
            communicationDeviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.communicationDevice?.productName?.toString() else null
        )
        snapshot = null

        pendingRestore?.let(handler::removeCallbacks)
        pendingRestore = null

        val hadCommunicationDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            previous.communicationDeviceId != null
        val hadNonNormalMode = previous.mode != AudioManager.MODE_NORMAL
        if (!hadCommunicationDevice && !hadNonNormalMode) {
            Log.i(
                TAG,
                "No app-owned communication route to cycle. Bluetooth media outputs are " +
                    "${bluetoothOutputNames(audioManager)}; Android exposes no public global A2DP sink toggle."
            )
            return
        }

        try {
            if (hadCommunicationDevice) {
                audioManager.clearCommunicationDevice()
                Log.i(TAG, "Released communication route after hi-res mic stop (${previous.communicationDeviceName})")
            }
            if (hadNonNormalMode) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not release Android communication route", t)
        }

        val restore = Runnable {
            try {
                // Restore the call mode before reselecting its output device.
                audioManager.mode = previous.mode
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hadCommunicationDevice) {
                    val devices = audioManager.availableCommunicationDevices
                    val restoredDevice = devices.firstOrNull {
                        it.id == previous.communicationDeviceId
                    } ?: devices.firstOrNull {
                        it.type == previous.communicationDeviceType &&
                            it.address == previous.communicationDeviceAddress
                    }
                    if (restoredDevice != null) {
                        val restored = audioManager.setCommunicationDevice(restoredDevice)
                        Log.i(TAG, "Restored communication route ${restoredDevice.productName}: $restored")
                    } else {
                        Log.w(TAG, "Previous communication device is no longer available after 500 ms route reset")
                    }
                }
                Log.i(TAG, "Audio route recovery complete; bluetoothOutputs=${bluetoothOutputNames(audioManager)}")
            } catch (t: Throwable) {
                Log.w(TAG, "Could not restore Android audio route after hi-res mic stop", t)
            } finally {
                pendingRestore = null
            }
        }
        pendingRestore = restore
        handler.postDelayed(restore, ROUTE_CYCLE_DELAY_MS)
    }

    private fun audioManager(): AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun bluetoothOutputNames(audioManager: AudioManager): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        (it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER))
            }
            .map { "${it.productName}(type=${it.type},id=${it.id})" }
    }

    private companion object {
        const val ROUTE_CYCLE_DELAY_MS = 500L
    }
}

private fun isTelegramHost(host: String?): Boolean =
    host == "telegram.org" || host?.endsWith(".telegram.org") == true ||
        host == "t.me" || host?.endsWith(".t.me") == true

/** Injected before Telegram's scripts so its getUserMedia hook is in place early. */
private val HI_RES_AUDIO_SHIM = """
(() => {
  const md = navigator.mediaDevices;
  if (!md || !md.getUserMedia) return;
  const nativeGetUserMedia = md.getUserMedia.bind(md);
  const AUDIO_PORT_MESSAGE = '__librepods_audio_port_v1__';
  const INPUT_RATE = 48000;
  const PREFILL_SAMPLES = 960;
  const MAX_PENDING_SAMPLES = 24000;

  // Default to Android's real microphone until the user explicitly starts the
  // AACP high-resolution capture. This avoids returning a silent synthetic track.
  let hiResMicEnabled = false;
  window.__lpSetHiResMicEnabled = enabled => {
    hiResMicEnabled = Boolean(enabled);
    window.__lpHiResMicEnabled = hiResMicEnabled;
  };
  window.__lpHiResMicEnabled = false;

  let nativeAudioPort = null;
  let context = null;
  let node = null;
  let legacyProcessor = null;
  let legacyQueue = new Float32Array(0);
  let legacyPosition = 0;
  let pendingBuffers = [];
  let pendingSamples = 0;

  function appendLegacy(buffer) {
    const input = new Int16Array(buffer);
    const unread = legacyQueue.subarray(legacyPosition);
    const combined = new Float32Array(unread.length + input.length);
    combined.set(unread, 0);
    for (let i = 0; i < input.length; i++) combined[unread.length + i] = input[i] / 32768;
    legacyQueue = combined.length > MAX_PENDING_SAMPLES ? combined.slice(-MAX_PENDING_SAMPLES) : combined;
    legacyPosition = 0;
  }

  function queueIncomingBuffer(buffer) {
    if (!(buffer instanceof ArrayBuffer)) return;
    if (node) {
      node.port.postMessage(buffer, [buffer]);
    } else if (legacyProcessor) {
      appendLegacy(buffer);
    } else {
      const samples = buffer.byteLength >> 1;
      pendingBuffers.push(buffer);
      pendingSamples += samples;
      while (pendingSamples > MAX_PENDING_SAMPLES && pendingBuffers.length) {
        pendingSamples -= pendingBuffers.shift().byteLength >> 1;
      }
    }
  }

  window.addEventListener('message', event => {
    // Android WebView's native postWebMessage source origin is the empty string.
    // The injected script itself is restricted to TELEGRAM_ORIGIN, and native sends
    // with TELEGRAM_ORIGIN as targetOrigin.
    if (event.origin !== '' || event.source !== null || event.data !== AUDIO_PORT_MESSAGE || !event.ports || !event.ports[0]) return;
    if (nativeAudioPort) nativeAudioPort.close();
    nativeAudioPort = event.ports[0];
    nativeAudioPort.onmessage = messageEvent => queueIncomingBuffer(messageEvent.data);
    nativeAudioPort.start();
    nativeAudioPort.postMessage('ready');
  }, false);

  md.getUserMedia = async constraints => {
    if (!constraints || !constraints.audio || !hiResMicEnabled) {
      return nativeGetUserMedia(constraints);
    }

    if (!context) {
      try {
        context = new AudioContext({ sampleRate: INPUT_RATE, latencyHint: 'interactive' });
      } catch (_error) {
        context = new AudioContext({ latencyHint: 'interactive' });
      }
    }
    await context.resume();
    const destination = context.createMediaStreamDestination();

    const moduleSource = `
      class LibrePodsInput extends AudioWorkletProcessor {
        constructor() {
          super();
          this.capacity = 32768;
          this.ring = new Float32Array(this.capacity);
          // Keep 31 samples of history before the read cursor for FIR conversion
          // when Android's actual AudioContext rate is not 48 kHz.
          this.left = 15;
          this.right = 16;
          this.writePosition = 31;
          this.readPosition = 31;
          this.started = false;
          this.step = 48000 / sampleRate;
          this.exactRate = Math.abs(this.step - 1) < 0.000001;
          this.phaseCount = 256;
          this.coefficients = this.exactRate ? null : this.makeCoefficients();
          this.port.onmessage = event => {
            if (!(event.data instanceof ArrayBuffer)) return;
            const input = new Int16Array(event.data);
            for (let i = 0; i < input.length; i++) {
              if (this.writePosition - this.readPosition >= this.capacity - 2) {
                this.readPosition = this.writePosition - (this.capacity >> 1);
                this.started = false;
              }
              this.ring[this.writePosition % this.capacity] = input[i] / 32768;
              this.writePosition++;
            }
          };
        }
        makeCoefficients() {
          const taps = 32;
          const left = this.left;
          const radius = 16;
          const cutoff = 0.5 / Math.max(1, this.step) * 0.94;
          const table = new Array(this.phaseCount);
          for (let phase = 0; phase < this.phaseCount; phase++) {
            const fraction = phase / this.phaseCount;
            const row = new Float32Array(taps);
            let total = 0;
            for (let tap = 0; tap < taps; tap++) {
              const distance = tap - left - fraction;
              const normalized = distance / radius;
              const window = Math.abs(normalized) <= 1
                ? 0.42 + 0.5 * Math.cos(Math.PI * normalized) + 0.08 * Math.cos(2 * Math.PI * normalized)
                : 0;
              const x = 2 * cutoff * distance;
              const sinc = Math.abs(x) < 1e-12 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
              const coefficient = 2 * cutoff * sinc * window;
              row[tap] = coefficient;
              total += coefficient;
            }
            if (Math.abs(total) > 1e-12) {
              for (let tap = 0; tap < taps; tap++) row[tap] /= total;
            }
            table[phase] = row;
          }
          return table;
        }
        process(_inputs, outputs) {
          const output = outputs[0][0];
          output.fill(0);
          const requiredPrefill = 960 + (this.exactRate ? 0 : this.right);
          if (!this.started) {
            if (this.writePosition - this.readPosition < requiredPrefill) return true;
            this.started = true;
          }
          for (let i = 0; i < output.length; i++) {
            const index = Math.floor(this.readPosition);
            if (this.exactRate) {
              if (index >= this.writePosition) {
                this.readPosition = this.writePosition;
                this.started = false;
                break;
              }
              output[i] = this.ring[index % this.capacity];
            } else {
              if (index + this.right >= this.writePosition) {
                this.readPosition = this.writePosition;
                this.started = false;
                break;
              }
              const fraction = this.readPosition - index;
              const phase = Math.round(fraction * this.phaseCount) % this.phaseCount;
              const taps = this.coefficients[phase];
              let sample = 0;
              for (let tap = 0; tap < taps.length; tap++) {
                const sourceIndex = index + tap - this.left;
                sample += this.ring[sourceIndex % this.capacity] * taps[tap];
              }
              output[i] = sample;
            }
            this.readPosition += this.step;
          }
          return true;
        }
      }
      registerProcessor('librepods-hires-input-48k', LibrePodsInput);
    `;

    const mute = context.createGain();
    mute.gain.value = 0;
    try {
      const moduleUrl = URL.createObjectURL(new Blob([moduleSource], { type: 'application/javascript' }));
      try {
        await context.audioWorklet.addModule(moduleUrl);
      } finally {
        URL.revokeObjectURL(moduleUrl);
      }
      node = new AudioWorkletNode(context, 'librepods-hires-input-48k', {
        numberOfInputs: 0,
        numberOfOutputs: 1,
        outputChannelCount: [1]
      });
      node.connect(destination);
      node.connect(mute);
      for (const buffer of pendingBuffers) node.port.postMessage(buffer, [buffer]);
      pendingBuffers = [];
      pendingSamples = 0;
    } catch (workletError) {
      console.warn('[LibrePods] AudioWorklet unavailable; using ScriptProcessor fallback', workletError);
      legacyProcessor = context.createScriptProcessor(1024, 1, 1);
      legacyProcessor.onaudioprocess = event => {
        const output = event.outputBuffer.getChannelData(0);
        output.fill(0);
        const step = INPUT_RATE / context.sampleRate;
        for (let i = 0; i < output.length; i++) {
          const index = Math.floor(legacyPosition);
          if (index + 1 >= legacyQueue.length) break;
          const fraction = legacyPosition - index;
          output[i] = legacyQueue[index] + (legacyQueue[index + 1] - legacyQueue[index]) * fraction;
          legacyPosition += step;
        }
        const consumed = Math.floor(legacyPosition);
        if (consumed > 0) {
          legacyQueue = legacyQueue.slice(consumed);
          legacyPosition -= consumed;
        }
      };
      legacyProcessor.connect(destination);
      legacyProcessor.connect(mute);
      for (const buffer of pendingBuffers) appendLegacy(buffer);
      pendingBuffers = [];
      pendingSamples = 0;
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
