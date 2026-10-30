package com.world.cloudxsolution;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.WindowCallbackWrapper;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;

import org.json.JSONObject;
import org.webrtc.IceCandidate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "XboxFramesMain";

    public WebView webView;
    private WebRtcBridge webRtcBridge;
    private SurfaceView surfaceView;
    private View loadingLayout;
    private TextView loadingText;
    private PerformanceDialog performanceDialog;
    private boolean isPerformanceOverlayVisible = false;
    private FrameLayout rootLayout;
    private AudioManager audioManager;
    private Dialog activeToastDialog;
    private boolean nativeGamepadEnabled = false;
    private volatile boolean isStreaming = false;

    private AndroidGamepadListener gamepadListener;
    private AndroidControllerLe controllerLe;
    private InputChannel legacyInputChannel;
    private String activeInputChannel = "";
    private final InputSessionState inputSession = new InputSessionState();
    private boolean acceptInputCallbacks, gamepadAnnounced;
    private boolean documentStartInjection, closingSession, signalingFailureShown;
    private Runnable firstFrameTimeout;
    private volatile long sessionGeneration;
    private String sessionStatus = "Catálogo";
    private int activeVideoProfile, activeSharpness, activeOutputScale;
    private boolean microphoneEnabled;
    private final InputTrace inputTrace = new InputTrace();
    private long lastPerformanceAtMs = -1;
    private String lastPerformanceReport = "Sem amostra de desempenho. Abra as estatísticas durante o jogo.";
    private static final String KEY_LOW_LATENCY = "prefer_minimal_post_processing";
    private int nativeGamepadIndex;
    private long handledControllerEvents, remoteInputAcks;
    private GamepadRumbleHandler rumbleHandler;

    private static final String PREFS_NAME = "CloudXPrefs";
    private static final String KEY_REGION = "target_region";
    private static final String KEY_RESOLUTION = "target_resolution";
    private static final String KEY_BITRATE = "target_bitrate";
    private static final String KEY_HIGH_VIDEO_PROFILE = "prefer_high_video_profile";
    private static final String KEY_USER_AGENT = "target_user_agent";
    private static final String KEY_INITIAL_SCALE = "target_initial_scale";
    private static final String KEY_SHOW_HELP = "show_intro_help";
    private static final String KEY_USE_UNRELIABLE_INPUT = "use_unreliable_input";

    private static final java.util.Map<String, String> USER_AGENTS = new java.util.HashMap<String, String>() {{
        put("default", ""); // Empty uses system default
        put("edge", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0");
        put("chrome", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");
        put("tizen", "Mozilla/5.0 (SMART-TV; LINUX; Tizen 7.0) AppleWebKit/537.36 (KHTML, like Gecko) 94.0.4606.54/7.0 TV Safari/537.36");
        put("android_tv", "Mozilla/5.0 (Linux; Android 11; Sony Bravia 4K Build/RP1A.200720.011) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.159 Mobile Safari/537.36");
        put("fire_tv", "Mozilla/5.0 (Linux; Android 9; AFTS Build/NS6281; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/70.0.3538.110 Mobile Safari/537.36");
        put("xbox", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; Xbox; Xbox Series X) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edge/44.18363.8131");
        put("ps5", "Mozilla/5.0 (PlayStation; PlayStation 5/6.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15");
        put("android_mobile", "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36");
        put("ios", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1");
        put("mac", "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_2_1) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2.1 Safari/605.1.15");
    }};

    private boolean isSelectPressed = false;

    // Inside MainActivity.java:
    public boolean isNativeGamepadEnabled() {
        return nativeGamepadEnabled && isStreaming;
    }

    public boolean isStreaming() {
        return isStreaming;
    }
    private volatile IStreamingService streamingService;
    private final android.os.Handler startupHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    // A single bounded-in-practice stream of native work. Never spawn replacement workers
    // when a driver/Binder call times out: interrupt cannot cancel a native driver hang.
    private final java.util.concurrent.ExecutorService videoWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "CloudX-video-startup"));
    private final java.util.concurrent.ExecutorService diagnosticWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "CloudX-startup-diagnostics"));
    private final StartupGate startup = new StartupGate();
    private final SurfaceDispatchGate surfaceDispatch = new SurfaceDispatchGate();
    private long startupStartedAt;
    private boolean diagnosticWorkInFlight;
    private boolean serviceBound, nativeWorkInFlight, surfaceDirty, activityDestroyed;
    private Runnable startupTimeout, catalogTimeout, catalogProbe;
    private int catalogGeneration;
    private boolean catalogFailed;
    private Button startupRetry, startupAndroidSettings;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (activityDestroyed) return;
            streamingService = IStreamingService.Stub.asInterface(binder);
            Log.i(TAG, "startup: service binder connected");
            if (startup.stage() == StartupGate.Stage.BINDING) initializeVideo(startup.token());
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            streamingService = null;
            failStartup("Serviço de vídeo desconectado.");
        }
        @Override public void onBindingDied(ComponentName name) {
            streamingService = null;
            failStartup("A conexão com o serviço de vídeo foi encerrada.");
        }
        @Override public void onNullBinding(ComponentName name) {
            failStartup("O serviço de vídeo não ficou disponível.");
        }
    };

    private void beginStartup() {
        if (activityDestroyed) return;
        if (nativeWorkInFlight) {
            showStartupFailure("O serviço de vídeo ainda não respondeu. Use Configurações do Android para forçar a parada do app e abrir novamente.");
            return;
        }
        int token = startup.begin();
        surfaceDispatch.requested();
        startupStartedAt = System.currentTimeMillis();
        showStartupProgress("Conectando ao serviço de vídeo…");
        if (startupTimeout != null) startupHandler.removeCallbacks(startupTimeout);
        startupTimeout = () -> {
            if (startup.accepts(token)) failStartup("Tempo esgotado: " + startup.stageLabel() + ".");
        };
        startupHandler.postDelayed(startupTimeout, 45000);
        if (streamingService != null) {
            initializeVideo(token);
            return;
        }
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        try {
            serviceBound = bindService(new Intent(this, StreamingService.class), serviceConnection, BIND_AUTO_CREATE);
            Log.i(TAG, "startup: bind request accepted=" + serviceBound);
            if (!serviceBound) failStartup("Não foi possível conectar ao serviço de vídeo.");
        } catch (RuntimeException e) {
            Log.e(TAG, "startup: bind failed", e);
            failStartup("Falha ao conectar ao serviço de vídeo.");
        }
    }

    private void initializeVideo(int token) {
        if (!startup.advance(token, StartupGate.Stage.INITIALIZING) || nativeWorkInFlight) return;
        showStartupProgress("Preparando áudio e vídeo…");
        IStreamingService service = streamingService;
        nativeWorkInFlight = true;
        videoWorker.execute(() -> {
            try {
                Log.i(TAG, "startup: native initialization started");
                service.registerCallback(streamingCallback);
                service.initPeerConnectionFactory();
                startupHandler.post(() -> {
                    nativeWorkInFlight = false;
                    if (activityDestroyed || !startup.advance(token, StartupGate.Stage.SURFACE)) return;
                    showStartupProgress("Preparando saída de vídeo…");
                    pushSurfaceIfReady();
                });
            } catch (RemoteException | RuntimeException e) {
                Log.e(TAG, "startup: native initialization failed", e);
                startupHandler.post(() -> {
                    nativeWorkInFlight = false;
                    if (startup.accepts(token)) failStartup("Falha ao preparar áudio e vídeo.");
                });
            }
        });
    }

    private void showStartupProgress(String message) {
        if (activityDestroyed) return;
        Log.i(TAG, "startup: " + message);
        loadingLayout.setVisibility(View.VISIBLE);
        loadingText.setText(message);
        findViewById(R.id.loadingIndicator).setVisibility(View.VISIBLE);
        startupRetry.setVisibility(View.GONE);
        startupAndroidSettings.setVisibility(View.GONE);
    }

    private void failStartup(String message) {
        if (activityDestroyed) return;
        boolean failedWhileBinding = startup.stage() == StartupGate.Stage.BINDING;
        startup.fail();
        if (startupTimeout != null) startupHandler.removeCallbacks(startupTimeout);
        showStartupFailure(message);
        if (failedWhileBinding && !diagnosticWorkInFlight) {
            diagnosticWorkInFlight = true;
            int token = startup.token();
            long since = startupStartedAt;
            Context context = getApplicationContext();
            diagnosticWorker.execute(() -> {
                String report = StartupDiagnostics.read(context, since);
                startupHandler.post(() -> {
                    diagnosticWorkInFlight = false;
                    if (!activityDestroyed && token == startup.token() && startup.stage() == StartupGate.Stage.FAILED) {
                        loadingText.setText(message + "\n" + report);
                        Log.w(TAG, "startup: " + report);
                    }
                });
            });
        }
    }

    private void showStartupFailure(String message) {
        if (activityDestroyed) return;
        loadingLayout.setVisibility(View.VISIBLE);
        loadingLayout.bringToFront();
        loadingText.setText(message);
        findViewById(R.id.loadingIndicator).setVisibility(View.GONE);
        startupRetry.setVisibility(View.VISIBLE);
        startupAndroidSettings.setVisibility(View.VISIBLE);
        startupRetry.requestFocus();
        Log.w(TAG, "startup: " + message);
    }

    private void retryLoading() {
        if (closingSession) { showStartupFailure("Encerramento ainda pendente. Use Configurações Android para reiniciar o aplicativo."); return; }
        if (!startup.ready()) { beginStartup(); return; }
        catalogFailed = false;
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("https://www.xbox.com/pt-BR/play");
        } else showWebview();
    }

    private void openAndroidAppSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (android.content.ActivityNotFoundException e) {
            showStartupFailure("Abra as configurações da TV → Aplicativos → CloudX para forçar a parada.");
        }
    }

    private void beginCatalogLoading(WebView view) {
        if (view != webView || isStreaming || activityDestroyed) return;
        catalogFailed = false;
        sessionStatus = "Carregando catálogo";
        if (catalogProbe != null) startupHandler.removeCallbacks(catalogProbe);
        catalogProbe = null;
        int generation = ++catalogGeneration;
        if (catalogTimeout != null) startupHandler.removeCallbacks(catalogTimeout);
        showStartupProgress("Carregando catálogo ou login Xbox…");
        catalogTimeout = () -> {
            if (view == webView && generation == catalogGeneration && !isStreaming && !activityDestroyed) {
                catalogFailed = true;
                view.stopLoading();
                showStartupFailure("O catálogo não concluiu o carregamento. Confira a conexão, a data/hora da TV e a versão do Android System WebView.");
            }
        };
        startupHandler.postDelayed(catalogTimeout, 45000);
    }

    private void catalogVisible(WebView view) {
        if (view != webView || catalogFailed || activityDestroyed || isStreaming || catalogProbe != null) return;
        final int generation = catalogGeneration;
        catalogProbe = new Runnable() {
            @Override public void run() {
                if (view != webView || generation != catalogGeneration || catalogFailed || isStreaming || activityDestroyed) return;
                // A finished document can still be an empty SPA. Wait for visible catalog/login content.
                view.evaluateJavascript("(function(){var b=document.body;if(!b)return false;"
                        + "var nodes=document.querySelectorAll('a[href*=\"/play/games/\"],input[type=email],input[type=password],input[name=loginfmt]');"
                        + "for(var i=0;i<nodes.length;i++)if(nodes[i].getClientRects().length)return true;"
                        + "return b.innerText.trim().length>120 && !!b.querySelector('a,button');})()", ready -> {
                    if (view != webView || generation != catalogGeneration || catalogFailed || isStreaming || activityDestroyed) return;
                    if ("true".equals(ready)) {
                        if (catalogTimeout != null) startupHandler.removeCallbacks(catalogTimeout);
                        sessionStatus = "Catálogo";
                        setWebviewVisible();
                    } else startupHandler.postDelayed(this, 1000);
                });
            }
        };
        startupHandler.post(catalogProbe);
    }

    public IStreamingService getStreamingService() { return startup.ready() ? streamingService : null; }
    private IStreamingCallback.Stub streamingCallback = newStreamingCallback(0);
    private IStreamingCallback.Stub newStreamingCallback(long generation) {
        return new IStreamingCallback.Stub() {
        private void post(Runnable task) {
            runOnUiThread(() -> { if (!activityDestroyed && generation == sessionGeneration) task.run(); });
        }
        @Override public void onAnswerReady(String sdp) { post(() -> sendAnswerToJs(sdp)); }
        @Override public void onOfferReady(String sdp) { post(() -> sendOfferToJs(sdp)); }

        @Override public void onRemoteDescriptionSet() {
            post(() -> {
                if (webView != null) webView.evaluateJavascript(
                        "if(window.handleRemoteDescriptionSet) window.handleRemoteDescriptionSet();", null);
            });
        }
        @Override public void onSignalingError(String operation) { post(() -> reportSignalingError(operation)); }

        @Override public void onLocalIceCandidate(String mid, int idx, String cand) {
            post(() -> {
                IceCandidate candidate = (mid == null && cand == null) ? null : new IceCandidate(mid, idx, cand);
                sendIceCandidateToJs(candidate);
            });
        }

        @Override public void onIceConnectionChange(String state) {
            post(() -> onIceConnectionStateChanged(state));
        }

        @Override public void onIceGatheringChange(String state) {
            post(() -> {
                if (webView == null) return;
                String stateStr = state.toLowerCase(Locale.ROOT);
                String script = "if (window.handleIceGatheringState) { window.handleIceGatheringState('" + stateStr + "'); }";
                webView.evaluateJavascript(script, null);
            });
        }

        @Override public void onTrackReceived(String kind, String id) {
            post(() -> {
                if (webView == null) return;
                String script = "if (window.simulateOnTrack) { window.simulateOnTrack('" + kind + "', '" + id + "'); }";
                webView.evaluateJavascript(script, null);
            });

        }

        @Override public void onPerformanceStatsReceived(String stats) {
            post(() -> {
                if (performanceDialog != null && performanceDialog.isShowing()) {
                    android.graphics.Rect size = surfaceView.getHolder().getSurfaceFrame();
                    performanceDialog.updateStats(stats + "\nControle: " + (activeInputChannel.isEmpty() ? "aguardando canal" : activeInputChannel)
                            + " | Autorização enviada: " + (inputSession.authorized() ? "sim" : "não")
                            + "\nAnúncio enviado: " + (gamepadAnnounced ? "sim" : "não")
                            + " | Eventos tratados: " + handledControllerEvents + "\nACKs input recebidos: " + remoteInputAcks + " (não confirmam ação no jogo)\nSuperfície: " + size.width() + "x" + size.height() + "\n" + displayLatencyStatus());
                }
            });
        }

        @Override public void onRenderSurfaceRequested() {
            post(() -> {
                surfaceDispatch.requested();
                pushSurfaceIfReady();
            });
        }

        @Override public void onVideoSizeChanged(int w, int h) {
            post(() -> {
                if (surfaceView == null || w <= 0 || h <= 0 || w > 3840 || h > 2160) return;
                android.view.Display display = surfaceView.getDisplay();
                android.view.Display.Mode mode = display == null ? null : display.getMode();
                int[] output = VideoOutputPolicy.size(w, h, mode == null ? w : mode.getPhysicalWidth(),
                        mode == null ? h : mode.getPhysicalHeight(), activeOutputScale);
                android.graphics.Rect size = surfaceView.getHolder().getSurfaceFrame();
                if (size.width() != output[0] || size.height() != output[1]) {
                    // Avoid downscaling 1080p video to a 720p TV launcher surface.
                    surfaceView.getHolder().setFixedSize(output[0], output[1]);
                }
            });
        }

        @Override public void onFirstFrameRendered(int w, int h) {
            post(() -> {
                if (!acceptInputCallbacks || activityDestroyed) return;
                if (firstFrameTimeout != null) startupHandler.removeCallbacks(firstFrameTimeout);
                requestInputSnapshot();
                Log.i(TAG, "onFirstFrameRendered callback received: " + w + "x" + h);
                setStreamingState(true);
                if (legacyInputChannel != null) {
                    legacyInputChannel.onResolutionChange(w, h);
                }
                showCustomToast("Sessão ativa: " + w + "x" + h);
                if (webView != null) {
                    webView.setVisibility(View.INVISIBLE);
                }

                    if(surfaceView!=null) {
                        ViewGroup parent = (ViewGroup) surfaceView.getParent();
                        //originalIndex = parent.indexOfChild(surfaceView);
                        surfaceView.setZOrderOnTop(true);
                        surfaceView.bringToFront();
                        parent.requestLayout();
                        parent.invalidate();
                    }
                    if (webView!=null&&Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        webView.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK);
                    }

            });
        }
        @Override public void showToast(String message) { post(() -> showCustomToast(message)); }
        @Override public void onStreamingStateChanged(boolean streaming) {
            post(() -> setStreamingState(streaming));
        }
        @Override public void setWebViewVisibility(int visibility) {
            post(() -> { if (webView != null) webView.setVisibility(visibility); });
        }
        @Override public void onInputChannelSnapshot(boolean legacy, boolean modern, boolean control) {
            post(() -> {
                if (!acceptInputCallbacks || activityDestroyed) return;
                inputSession.snapshot(legacy, modern, control);
                if (legacy || modern) setStreamingState(true);
                updateInputRoute();
                announceNativeGamepad();
            });
        }
        @Override public void onDataChannelStateChanged(String label, String state) {
            post(() -> MainActivity.this.onDataChannelStateChanged(label, state));
        }
        @Override public void onDataChannelMessageReceived(String label, byte[] data, String str, boolean binary) {
            post(() -> MainActivity.this.onDataChannelMessageReceived(label, data,str, binary));
        }
        @Override public void requestShowStreamingMenu() { post(MainActivity.this::showStreamingMenuDialog); }
        @Override public void setNativeGamepadEnabledOnUi(boolean enabled) {
            post(() -> nativeGamepadEnabled = enabled);
        }
    };
    }

    @SuppressLint({"SetJavaScriptEnabled", "RestrictedApi"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        android.content.SharedPreferences gpPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (TvCompatibilityProfile.isTl042(Build.MODEL)) {
            SharedPreferences.Editor defaults = gpPrefs.edit();
            if (!gpPrefs.contains(KEY_RESOLUTION)) defaults.putString(KEY_RESOLUTION, TvCompatibilityProfile.RESOLUTION);
            if (!gpPrefs.contains(KEY_BITRATE)) defaults.putInt(KEY_BITRATE, TvCompatibilityProfile.BITRATE_MBPS);
            if (!gpPrefs.contains(KEY_HIGH_VIDEO_PROFILE)) defaults.putBoolean(KEY_HIGH_VIDEO_PROFILE, false);
            defaults.apply();
        }
        float dz = gpPrefs.getFloat("camera_deadzone", 0.12f);
        float sens = gpPrefs.getFloat("gamepad_sensitivity", 1.5f);
        boolean useUnreliable = gpPrefs.getBoolean(KEY_USE_UNRELIABLE_INPUT, true);
        inputSession.preferModern(useUnreliable);
        
        rumbleHandler = new GamepadRumbleHandler(idx -> 
                gamepadListener != null ? gamepadListener.getLastDevice() : null);
        
        gamepadListener = new AndroidGamepadListener(0, sens, dz, buffer -> {
            if (isStreaming && streamingService != null && "unreliableinput".equals(activeInputChannel)) {
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                inputTrace.packet(android.os.SystemClock.elapsedRealtime(), "unreliableinput", data);
                try {
                    streamingService.onDataChannelSend("unreliableinput", data, null, true);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to send gamepad frame", e);
                }
            }
        });
        gamepadListener.setOnMenuTrigger(this::showStreamingMenuDialog);

        legacyInputChannel = new InputChannel(buffer -> {
            if (isStreaming && streamingService != null && "input".equals(activeInputChannel)) {
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                inputTrace.packet(android.os.SystemClock.elapsedRealtime(), "input", data);
                try {
                    streamingService.onDataChannelSend("input", data, null, true);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to send legacy gamepad frame", e);
                }
            }
        }, 1920, 1080);
        controllerLe = new AndroidControllerLe(legacyInputChannel, 0, sens, dz);

        // The splash must never wait for a driver, Binder or a network page.
        splashScreen.setKeepOnScreenCondition(() -> false);

        getWindow().setCallback(new WindowCallbackWrapper(getWindow().getCallback()) {

            @Override
            public boolean dispatchGenericMotionEvent(MotionEvent event) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (inputTrace.motionDue(now)) inputTrace.add(now, "eixos dev=" + event.getDeviceId()
                        + " src=" + event.getSource() + " LT=" + event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
                        + " RT=" + event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
                        + " brake=" + event.getAxisValue(MotionEvent.AXIS_BRAKE) + " gas=" + event.getAxisValue(MotionEvent.AXIS_GAS));
                if (isStreaming) {
                    if ("unreliableinput".equals(activeInputChannel) && gamepadListener != null) {
                        showCustomToast(gamepadListener.prepare(event.getDevice()));
                        if (gamepadListener.onGenericMotion(event)) { handledControllerEvents++; return true; }
                    } else if ("input".equals(activeInputChannel) && controllerLe != null) {
                        controllerLe.detectAxisLayout(event.getDevice());
                        if (controllerLe.onGenericMotion(event)) { handledControllerEvents++; return true; }
                    }
                }
                return super.dispatchGenericMotionEvent(event);
            }

            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (inputTrace.enabled(now)) inputTrace.add(now, "tecla dev=" + event.getDeviceId()
                        + " src=" + event.getSource() + " key=" + event.getKeyCode() + " scan=" + event.getScanCode()
                        + " ação=" + event.getAction() + " rep=" + event.getRepeatCount());
                if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_SELECT) {
                    isSelectPressed = event.getAction() == KeyEvent.ACTION_DOWN;
                }
                if (isSelectPressed && event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_START
                        && event.getAction() == KeyEvent.ACTION_DOWN) {
                    showStreamingMenuDialog();
                    return true;
                }
                if (isStreaming) {
                    if ("unreliableinput".equals(activeInputChannel) && gamepadListener != null) {
                        showCustomToast(gamepadListener.prepare(event.getDevice()));
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            if (gamepadListener.onKeyDown(event.getKeyCode(), event)) { handledControllerEvents++; return true; }
                        } else if (event.getAction() == KeyEvent.ACTION_UP) {
                            if (gamepadListener.onKeyUp(event.getKeyCode(), event)) { handledControllerEvents++; return true; }
                        }
                    } else if ("input".equals(activeInputChannel) && controllerLe != null) {
                        controllerLe.detectAxisLayout(event.getDevice());
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            if (controllerLe.onKeyDown(event.getKeyCode(), event)) { handledControllerEvents++; return true; }
                        } else if (event.getAction() == KeyEvent.ACTION_UP) {
                            if (controllerLe.onKeyUp(event.getKeyCode(), event)) { handledControllerEvents++; return true; }
                        }
                    }
                }
                return super.dispatchKeyEvent(event);
            }

        });

        rootLayout = findViewById(R.id.rootLayout);
        surfaceView = findViewById(R.id.surfaceView);
        loadingLayout = findViewById(R.id.loadingLayout);
        loadingText = findViewById(R.id.loadingText);


        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                surfaceDispatch.created();
                pushSurfaceIfReady();
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { pushSurfaceIfReady(); }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceDispatch.destroyed();
                IStreamingService service = streamingService;
                long generation = sessionGeneration;
                if (service != null && !activityDestroyed) videoWorker.execute(() -> {
                    if (generation != sessionGeneration) return;
                    try { service.clearRenderSurface(); } catch (RemoteException | RuntimeException e) {
                        Log.w(TAG, "clearRenderSurface failed", e);
                    }
                });
            }
        });


        startupRetry = findViewById(R.id.startup_retry);
        startupAndroidSettings = findViewById(R.id.startup_android_settings);
        startupRetry.setOnClickListener(v -> retryLoading());
        startupAndroidSettings.setOnClickListener(v -> openAndroidAppSettings());
        findViewById(R.id.startup_menu).setOnClickListener(v -> showStreamingMenuDialog());
        findViewById(R.id.startup_exit).setOnClickListener(v -> finish());
        beginStartup();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!isStreaming && webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else if (isStreaming) {
                    showStreamingMenuDialog();
                } else {
                    finish();
                }
            }
        });
// Request unbuffered dispatch specifically for joystick input sources
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().getDecorView().requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK);
        }
        // Debug: Launch Bluetooth Test Activity on start

        showIntroHelpDialog();
    }
    private void pushSurfaceIfReady() {
        if (activityDestroyed || streamingService == null || surfaceView == null
                || (!startup.ready() && startup.stage() != StartupGate.Stage.SURFACE)) return;
        if (!surfaceDispatch.needsDelivery()) return;
        if (nativeWorkInFlight) { surfaceDirty = true; return; }
        long surfaceGeneration = surfaceDispatch.generation();
        Surface surface = surfaceView.getHolder().getSurface();
        if (surface == null || !surface.isValid()) return;
        surfaceView.setKeepScreenOn(true);
        android.graphics.Rect size = surfaceView.getHolder().getSurfaceFrame();
        int width = size.width(), height = size.height(), token = startup.token();
        IStreamingService service = streamingService;
        nativeWorkInFlight = true;
        surfaceDirty = false;
        videoWorker.execute(() -> {
            try {
                service.setRenderSurface(surface, width, height);
                startupHandler.post(() -> {
                    nativeWorkInFlight = false;
                    if (activityDestroyed || token != startup.token() || service != streamingService) return;
                    surfaceDispatch.delivered(surfaceGeneration);
                    if (!surfaceDispatch.available()) return;
                    if (surfaceDispatch.needsDelivery()) {
                        pushSurfaceIfReady();
                        return;
                    }
                    if (startup.advance(token, StartupGate.Stage.READY)) {
                        if (startupTimeout != null) startupHandler.removeCallbacks(startupTimeout);
                        showWebview();
                    }
                    if (surfaceDirty) pushSurfaceIfReady();
                });
            } catch (RemoteException | RuntimeException e) {
                Log.e(TAG, "startup: render surface failed", e);
                startupHandler.post(() -> {
                    nativeWorkInFlight = false;
                    if (token == startup.token()) failStartup("Falha ao preparar a saída de vídeo.");
                });
            }
        });
    }
    private void showIntroHelpDialog() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_SHOW_HELP, true)) return;

        Dialog dialog = new Dialog(this, R.style.XboxDialogTheme);
        dialog.setContentView(R.layout.dialog_intro_help);
        dialog.setCancelable(true);

        Button btnOk = dialog.findViewById(R.id.btn_ok);
        Button btnDontShow = dialog.findViewById(R.id.btn_dont_show_again);

        btnOk.setOnClickListener(v -> dialog.dismiss());
        btnDontShow.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_SHOW_HELP, false).apply();
            dialog.dismiss();
        });

        dialog.show();
        btnOk.requestFocus();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        // Apply saved Initial Scale
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int initialScale = prefs.getInt(KEY_INITIAL_SCALE, 0);
        if (initialScale > 0) {
            webView.setInitialScale(initialScale);
        }

        // Fix scaling issues for Desktop/TV User Agents
        webSettings.setUseWideViewPort(false);
        webSettings.setLoadWithOverviewMode(false);
        webSettings.setSupportZoom(false);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);

        // Apply saved User-Agent
        String uaKey = prefs.getString(KEY_USER_AGENT, "default");
        String customUa = USER_AGENTS.get(uaKey);
        if (customUa != null && !customUa.isEmpty()) {
            webSettings.setUserAgentString(customUa);
        }

        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);
        webSettings.setDatabaseEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (view != webView || activityDestroyed || catalogFailed) return;
                catalogVisible(view);

                // Inject viewport meta tag to force fit
                String viewportJs = "var meta = document.createElement('meta'); " +
                        "meta.name = 'viewport'; " +
                        "meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'; " +
                        "document.getElementsByTagName('head')[0].appendChild(meta);";
                view.evaluateJavascript(viewportJs, null);

                android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String region = prefs.getString(KEY_REGION, "br");
                String resolution = prefs.getString(KEY_RESOLUTION, "1080HQ");
                int bitrate = prefs.getInt(KEY_BITRATE, 20); // 0 means Auto
                boolean useUnreliable = prefs.getBoolean(KEY_USE_UNRELIABLE_INPUT, true);

                @SuppressLint("DefaultLocale") String initJs = String.format("window.BX_TARGET_REGION = '%s'; window.BX_TARGET_RES = '%s'; window.BX_TARGET_BITRATE = %d; window.BX_USE_UNRELIABLE_INPUT = %b;",
                        region, resolution, bitrate, useUnreliable);
                view.evaluateJavascript(initJs, null);

            if (gamepadListener != null) {
                // Multiply by 100 to send as percentages (e.g., 0.12 -> 12, 1.5 -> 150)
                float dz = gamepadListener.getStickDeadzone() * 100;
                float sens = gamepadListener.getCameraSensitivity() * 100;

                // Use String.format for cleaner JS string generation
                String js = String.format(Locale.US,
                        "if (window.setControllerSettings) { window.setControllerSettings(%.0f, %.0f); }",
                        dz, sens);
                view.evaluateJavascript(js, null);
            }
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                catalogVisible(view);
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) catalogError(view, "Falha ao carregar o catálogo (código " + error.getErrorCode() + "). Confira a conexão da TV.");
            }

            @Override
            public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request,
                                            android.webkit.WebResourceResponse response) {
                if (request.isForMainFrame()) catalogError(view, "O site Xbox respondeu com erro HTTP " + response.getStatusCode() + ". Tente novamente mais tarde.");
            }

            private void catalogError(WebView view, String message) {
                if (view != webView || activityDestroyed || isStreaming) return;
                catalogFailed = true;
                if (catalogTimeout != null) startupHandler.removeCallbacks(catalogTimeout);
                showStartupFailure(message);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                if (view != webView) { view.destroy(); return true; }
                exitGameToCatalog();
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                beginCatalogLoading(view);
                super.onPageStarted(view, url, favicon);
                if (url != null && (url.contains("/fr-FR") || url.contains("/fr-fr"))) {
                    view.stopLoading();
                    view.loadUrl(url.replace("/fr-FR", "/pt-BR").replace("/fr-fr", "/pt-BR"));
                    return;
                }
                Log.i(TAG, "Page started: " + url);
                if (webView != null && webView.getWebChromeClient() instanceof CustomWebChromeClient) {
                    ((CustomWebChromeClient) webView.getWebChromeClient()).resetInjection();
                }
            }

        });
        webRtcBridge = new WebRtcBridge(this);
        webView.addJavascriptInterface(webRtcBridge, "AndroidBridge");
        webView.setWebChromeClient(new CustomWebChromeClient());
        documentStartInjection = androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT);
        if (documentStartInjection) androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView,
                webConfigurationScript() + loadScriptFromAssets(), java.util.Collections.singleton("https://www.xbox.com"));
    }

    public void setWebviewVisible(){
       if (activityDestroyed || isStreaming || catalogFailed || !"Catálogo".equals(sessionStatus)) return;
       if(webView!=null){
           webView.setVisibility(WebView.VISIBLE);}
       if (loadingLayout != null) {
           loadingLayout.setVisibility(View.GONE);
       }
        if (performanceDialog != null) {
            performanceDialog.dismiss();
        }
    }
private boolean debug=false;
    public void showCustomToast(final String message) {
        if (message == null || message.isEmpty() || (!debug && message.startsWith("[debug]"))) return;
        runOnUiThread(() -> {
            if(message.isEmpty()){return;}
            if(!debug&&message.startsWith("[debug]")){return;}
            if (activeToastDialog != null && activeToastDialog.isShowing()) {
                activeToastDialog.dismiss();
            }

            final Dialog dialog = new Dialog(this, R.style.XboxToastTheme);
            activeToastDialog = dialog;
            
            View toastView = getLayoutInflater().inflate(R.layout.layout_custom_toast, null);
            TextView textView = toastView.findViewById(R.id.toast_text);
            textView.setText(message);

            dialog.setContentView(toastView);
            
            WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL ;
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            dialog.getWindow().setAttributes(lp);

            dialog.show();

            Animation enterAnim = AnimationUtils.loadAnimation(this, R.anim.toast_enter);
            toastView.startAnimation(enterAnim);

            toastView.postDelayed(() -> {
                if (!dialog.isShowing()) return;

                Animation exitAnim = AnimationUtils.loadAnimation(this, R.anim.toast_exit);
                exitAnim.setAnimationListener(new Animation.AnimationListener() {
                    @Override public void onAnimationStart(Animation animation) {}
                    @Override public void onAnimationRepeat(Animation animation) {}
                    @Override
                    public void onAnimationEnd(Animation animation) {
                        if (dialog.isShowing()) dialog.dismiss();
                    }
                });
                toastView.startAnimation(exitAnim);
            }, 8000);
        });
    }

    private Bundle webViewState;

    public void showWebview() {
        if (activityDestroyed || !startup.ready()) return;
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }
        runOnUiThread(() -> {
            if (activityDestroyed || !startup.ready()) return;
            if (webView == null) {
                try { initWebView(); } catch (RuntimeException e) {
                    Log.e(TAG, "startup: WebView creation failed", e);
                    webView = null;
                    showStartupFailure("Não foi possível iniciar o navegador da TV. Verifique o Android System WebView.");
                    return;
                }
                rootLayout.addView(webView, 1);
                if (webViewState != null) {
                    webView.restoreState(webViewState);
                    if(webView.canGoBack()){webView.goBack();}
                } else {
                    webView.loadUrl("https://www.xbox.com/pt-BR/play");
                }
            }
            webView.setVisibility(WebView.VISIBLE);
            webView.resumeTimers();
            webView.onResume();
            if (performanceDialog != null) {
                performanceDialog.dismiss();
            }
        });
    }

    public void closeWebview() {
        runOnUiThread(() -> {
            if (webRtcBridge != null) webRtcBridge.stopKeepAlive();
            if (webView != null) {
                webViewState = new Bundle();
                webView.saveState(webViewState);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    webView.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK);
                }
                //rootLayout.requestUnbufferedDispatch(InputDevice.SOURCE_JOYSTICK);
                rootLayout.removeView(webView);
                webView.stopLoading();
                // Keep the catalog cache; it reduces startup downloads on slower TVs.
                webView.loadUrl("about:blank");
                webView.onPause();
                webView.pauseTimers();
                webView.destroy();
                webView = null;
                // Request unbuffered dispatch specifically for joystick input sources
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    getWindow().getDecorView().requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK);
                }

                // Toast.makeText(MainActivity.this,"Webview closed",Toast.LENGTH_LONG).show();
            }
        });


    }

    // before (the whole try block built List<PeerConnection.IceServer> and
// called webRtcReceiver.setSignalingListener(null)/createPeerConnection(finalServers)/setupSignalingListener()):

    public void onPeerConnectionConfigReceived(String configJson) { configurePeer(configJson, true); }
    public void onPeerConnectionConfigUpdated(String configJson) { configurePeer(configJson, false); }

    private void configurePeer(String configJson, boolean newSession) {
        if (configJson == null || configJson.isEmpty()) return;
        runOnUiThread(() -> {
            if (streamingService == null || closingSession || activityDestroyed) return;
            IStreamingService service = streamingService;
            IStreamingCallback previous = streamingCallback;
            if (newSession) {
                onNativeSessionClosing();
                gamepadListener.resetSession(); controllerLe.resetSession();
                setGamepadIndex(0);
                signalingFailureShown = false;
                sessionGeneration++;
                streamingCallback = newStreamingCallback(sessionGeneration);
                acceptInputCallbacks = true;
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                activeVideoProfile = savedVideoProfile();
                activeSharpness = prefs.getInt("video_sharpness", 0);
                activeOutputScale = prefs.getInt("video_output_scale", 0);
                microphoneEnabled = false;
                sessionStatus = "Conectando";
                surfaceView.setVisibility(View.VISIBLE);
                final long waitingGeneration = sessionGeneration;
                firstFrameTimeout = () -> {
                    if (waitingGeneration == sessionGeneration && !activityDestroyed)
                        reportSignalingError("tempo-esgotado-primeiro-quadro");
                };
                startupHandler.postDelayed(firstFrameTimeout, 35000);
            }
            IStreamingCallback current = streamingCallback;
            long generation = sessionGeneration;
            runNativeOperation(() -> {
                if (generation != sessionGeneration) return;
                try {
                    if (newSession) {
                        service.unregisterCallback(previous);
                        service.closeSession();
                        service.configureVideoOptions(activeVideoProfile, activeSharpness);
                        service.registerCallback(current);
                    }
                    service.createPeerConnection(configJson);
                    service.requestInputChannelSnapshot();
                } catch (RemoteException e) { reportSignalingError("configuração"); }
            });
        });
    }

    public void runNativeOperation(Runnable operation) {
        if (!activityDestroyed && !videoWorker.isShutdown()) videoWorker.execute(operation);
    }

    public void closeNativeSession() {
        onNativeSessionClosing();
        sessionGeneration++;
        IStreamingService service = streamingService;
        if (service != null) runNativeOperation(() -> {
            try { service.closeSession(); } catch (RemoteException e) { Log.w(TAG, "Session close failed", e); }
        });
    }

    public void reportSignalingError(String operation) {
        runOnUiThread(() -> {
            if (activityDestroyed || closingSession) return;
            sessionStatus = "Erro de conexão";
            showCustomToast("Falha na conexão de vídeo (" + operation + "). Encerre e reabra o jogo.");
            if (!signalingFailureShown && activeVideoProfile == 3 && acceptInputCallbacks) {
                signalingFailureShown = true;
                acceptInputCallbacks = false;
                new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("High exclusivo não conectou")
                        .setMessage("O perfil pode não ser compatível com esta sessão. Você pode voltar ao catálogo usando Baseline.")
                        .setPositiveButton("Usar Baseline", (d,w) -> {
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt("video_profile_mode", 0)
                                    .putBoolean(KEY_HIGH_VIDEO_PROFILE, false).apply(); exitGameToCatalog();
                        }).setNegativeButton("Voltar ao catálogo", (d,w) -> exitGameToCatalog()).show();
            }
            if (!signalingFailureShown) {
                signalingFailureShown = true;
                new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("A sessão não conectou corretamente")
                        .setMessage("Falha: " + operation + ". Volte ao catálogo para tentar uma nova sessão.")
                        .setPositiveButton("Voltar ao catálogo", (d,w) -> exitGameToCatalog())
                        .setNegativeButton("Fechar", null).show();
            }
            if (webView != null) webView.evaluateJavascript(
                    "if(window.handleSignalingError) window.handleSignalingError(" + JSONObject.quote(operation) + ");", null);
        });
    }

    public void sendAnswerToJs(String sdp) {
        if (webView == null) return;
        String script = "if (typeof handleAnswer === 'function') { handleAnswer(" + JSONObject.quote(sdp) + "); }";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    public void sendOfferToJs(String sdp) {
        if (webView == null) return;
        String script = "if (typeof handleOffer === 'function') { handleOffer(" + JSONObject.quote(sdp) + "); }";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    public void sendIceCandidateToJs(IceCandidate candidate) {
        if (webView == null) return;
        String script;
        if (candidate == null) {
            script = "if (typeof handleIceCandidate === 'function') { handleIceCandidate(null, 0, null); }";
        } else {
            script = "if (typeof handleIceCandidate === 'function') { handleIceCandidate('" + 
                    candidate.sdpMid + "', " + candidate.sdpMLineIndex + ", " + JSONObject.quote(candidate.sdp) + "); }";
        }
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    public void onDataChannelMessageReceived(String label, byte[] bin, String message, boolean isBinary) {

        if (("reliableinput".equalsIgnoreCase(label) || "input".equalsIgnoreCase(label)) && bin != null) {
            InputMessageParser.parse(bin,
                    (w, h) -> {
                        Log.i(TAG, "Server resolution update: " + w + "x" + h);
                        if (legacyInputChannel != null) {
                            legacyInputChannel.onResolutionChange(w, h);
                        }
                    },
                    (idx, left, right, leftTrig, rightTrig, duration, delay, repeat) -> {
                        if (rumbleHandler != null && getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("rumble_enabled", true)) {
                            rumbleHandler.onRumble(idx, left, right, leftTrig, rightTrig, duration, delay, repeat);
                        }
                    },
                    token -> {
                        remoteInputAcks++;
                        inputTrace.add(android.os.SystemClock.elapsedRealtime(), "ACK input recebido");
                    });
            return;
        }
        if (webView == null) return;

        // 2. Filter: Only forward 'control', 'message', and 'reliableinput' to JS
        boolean shouldSendToJs = "control".equalsIgnoreCase(label) ||
                "message".equalsIgnoreCase(label) ||"chat".equalsIgnoreCase(label);

        if (shouldSendToJs) {
            // Ensure message is populated for binary data so JS gets something via Base64
            if (isBinary && message == null && bin != null) {
                message = Base64.encodeToString(bin, Base64.NO_WRAP);
            }

            if (message != null) {
                // JS signature: (label, binaryData, textData, isBinary)
                String binaryArg = isBinary ? JSONObject.quote(message) : "null";
                String textArg = isBinary ? "null" : JSONObject.quote(message);

                String script = "if (window.handleDataChannelMessage) { window.handleDataChannelMessage('" +
                        label + "', " + binaryArg + ", " + textArg + ", " + isBinary + "); }";

                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript(script, null);
                });
            }
        }
    }

    public void onDataChannelStateChanged(String label, String state) {
        if (!acceptInputCallbacks || activityDestroyed) return;
        if (("input".equalsIgnoreCase(label) || "unreliableinput".equalsIgnoreCase(label))
                && "OPEN".equalsIgnoreCase(state)) setStreamingState(true);
        inputSession.channel(label, state);
        if (!inputSession.canAnnounce()) gamepadAnnounced = false;
        updateInputRoute();
        announceNativeGamepad();
        if (webView != null) webView.evaluateJavascript(
                "if(window.handleDataChannelState) window.handleDataChannelState("
                        + JSONObject.quote(label) + "," + JSONObject.quote(state) + ");", null);
    }

    private void requestInputSnapshot() {
        if (!acceptInputCallbacks || streamingService == null) return;
        try { streamingService.requestInputChannelSnapshot(); }
        catch (RemoteException e) { Log.w(TAG, "Input snapshot unavailable", e); }
    }

    private void updateInputRoute() {
        String selected = inputSession.activeChannel();
        if (selected.equals(activeInputChannel)) return;
        activeInputChannel = selected;
        if ("input".equals(selected) && legacyInputChannel != null) {
            legacyInputChannel.start(0);
            if (controllerLe != null) controllerLe.sendCurrentState();
        } else if ("unreliableinput".equals(selected) && gamepadListener != null) {
            gamepadListener.sendCurrentState();
        }
    }

    public void onControlAuthorizationSent() {
        if (!acceptInputCallbacks || activityDestroyed) return;
        inputSession.authorizationSent();
        gamepadAnnounced = false;
        requestInputSnapshot();
        announceNativeGamepad();
    }

    private void announceNativeGamepad() {
        if (gamepadAnnounced || !inputSession.canAnnounce() || streamingService == null) return;
        try {
            String message = new JSONObject().put("message", "gamepadChanged")
                    .put("gamepadIndex", nativeGamepadIndex).put("wasAdded", true).toString();
            streamingService.onDataChannelSend("control", message.getBytes(StandardCharsets.UTF_8), null, false);
            gamepadAnnounced = true;
            if ("unreliableinput".equals(activeInputChannel) && gamepadListener != null) gamepadListener.sendCurrentState();
            if ("input".equals(activeInputChannel) && controllerLe != null) controllerLe.sendCurrentState();
        } catch (RemoteException | org.json.JSONException e) { Log.w(TAG, "Gamepad announcement failed", e); }
    }

    public void onNativeSessionClosing() {
        if (firstFrameTimeout != null) startupHandler.removeCallbacks(firstFrameTimeout);
        acceptInputCallbacks = false;
        inputSession.reset();
        gamepadAnnounced = false;
        activeInputChannel = "";
        handledControllerEvents = remoteInputAcks = 0;
        isSelectPressed = false;
        if (gamepadListener != null) gamepadListener.releaseAll();
        if (controllerLe != null) controllerLe.releaseAll();
        nativeGamepadEnabled = false;
        setStreamingState(false);
    }

    public void onIceConnectionStateChanged(String state) {
        if (webView == null) return;
        String script = "if (window.handleIceConnectionState) { window.handleIceConnectionState('" + state + "'); }";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    public void onDataChannelSend(String label, byte[] binary, String data, boolean isBinary) {
        if (streamingService == null) return;
        try {

            streamingService.onDataChannelSend(label, binary, data, isBinary);
        } catch (RemoteException e) {
            Log.e(TAG, "onDataChannelSend", e);
        }
    }

    public void setNativeGamepadEnabled(boolean enabled) {
        Log.i(TAG, "Native Gamepad " + (enabled ? "ENABLED" : "DISABLED"));
        this.nativeGamepadEnabled = enabled;
    }

    public void setGamepadIndex(int idx) {
        if (idx < 0 || idx > 3) return;
        nativeGamepadIndex = idx;
        if (gamepadListener != null) {
            gamepadListener.setgamepadIndex(idx);
        }
        if (controllerLe != null) {
            controllerLe.setgamepadIndex(idx);
        }
    }

    public void updateGamepadSettings(float deadzone, float sensitivity) {
        if (gamepadListener != null) {
            gamepadListener.setStickDeadzone(deadzone);
            gamepadListener.setCameraSensitivity(sensitivity);
        }
        if (controllerLe != null) {
            controllerLe.setStickDeadzone(deadzone);
            controllerLe.setCameraSensitivity(sensitivity);
        }
        android.content.SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putFloat("camera_deadzone", deadzone);
        editor.putFloat("gamepad_sensitivity", sensitivity);
        editor.apply();
    }

    private void resetGamepadDispatch() {
        Log.i(TAG, "Resetting gamepad dispatch state");
    }

    private int originalIndex=0;
    public void setStreamingState(boolean isStreaming) {
        Log.i(TAG, "Streaming State: " + (isStreaming ? "ACTIVE" : "INACTIVE"));
        this.isStreaming = isStreaming;
        if (isStreaming) sessionStatus = "Jogando";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getWindow().setPreferMinimalPostProcessing(isStreaming && prefersMinimalPostProcessing());
        runOnUiThread(() -> {
//            if (isStreaming ) {
//                if(surfaceView!=null) {
//                    ViewGroup parent = (ViewGroup) surfaceView.getParent();
//                    //originalIndex = parent.indexOfChild(surfaceView);
//                    surfaceView.setZOrderOnTop(true);
//                    surfaceView.bringToFront();
//                    parent.requestLayout();
//                    parent.invalidate();
//                }
//                if (webView!=null&&Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//                    webView.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK);
//                }
//            } else {
//                if(surfaceView!=null){
//                    ViewGroup parent = (ViewGroup) surfaceView.getParent();
//                    surfaceView.setZOrderOnTop(false);
//                    parent.removeView(surfaceView);
//                    parent.addView(surfaceView, 0);
//                }
//                resetGamepadDispatch();
//                if (webView != null) {
//                    webView.setVisibility(View.VISIBLE);
//                }
//            }
        });
    }
    private boolean prefersMinimalPostProcessing() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_LOW_LATENCY, false);
    }

    private String displayLatencyStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Baixa latência da tela: API indisponível";
        android.view.Display display = surfaceView.getDisplay();
        boolean supported = display != null && display.isMinimalPostProcessingSupported();
        return "Baixa latência da tela: " + (isStreaming && prefersMinimalPostProcessing() ? "solicitada" : "desativada")
                + " | Suporte: " + (supported ? "sim" : "não informado")
                + "\nJogo Mestre TCL: ativação não verificável pelo app";
    }
    public void showNativeKeyboardDialog(String inputValue,String title) {
        Dialog dialog = new Dialog(this, R.style.XboxDialogTheme);
        dialog.setContentView(R.layout.dialog_keyboard);
        dialog.setCancelable(false);

        EditText input = dialog.findViewById(R.id.keyboard_input);
        Button btnClose = dialog.findViewById(R.id.btn_close);
        Button btnSubmit = dialog.findViewById(R.id.btn_submit);
        TextView dialogtitle = dialog.findViewById(R.id.keyboard_title);
        input.setText(inputValue);
        dialogtitle.setText(title);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (webView == null) return;
                String text = s.toString();
                String script = "if (window.updateKeyboardInput) { window.updateKeyboardInput(" + JSONObject.quote(text) + "); }";
                webView.evaluateJavascript(script, null);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        input.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                if (webView != null) webView.evaluateJavascript("if (window.submitKeyboardInput) { window.submitKeyboardInput(); }", null);
                dialog.dismiss();
                return true;
            }
            return false;
        });

        btnClose.setOnClickListener(v -> {
            if (webView != null) webView.evaluateJavascript("if (window.closeKeyboardInput) { window.closeKeyboardInput(); }", null);
            dialog.dismiss();
        });

        btnSubmit.setOnClickListener(v -> {
            if (webView != null) webView.evaluateJavascript("if (window.submitKeyboardInput) { window.submitKeyboardInput(); }", null);
            dialog.dismiss();
        });

        dialog.show();
        btnSubmit.requestFocus();
    }






    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        startup.close();
        startupHandler.removeCallbacksAndMessages(null);
        diagnosticWorker.shutdown();
        if (webRtcBridge != null) webRtcBridge.dispose();
        if (performanceDialog != null) {
            performanceDialog.dismiss();
            performanceDialog = null;
        }

        if (activeToastDialog != null) {
            activeToastDialog.dismiss();
            activeToastDialog = null;
        }

        if (legacyInputChannel != null) {
            legacyInputChannel.shutdown();
        }

        IStreamingService service = streamingService;
        streamingService = null;
        if (service != null) videoWorker.execute(() -> {
            try {
                service.unregisterCallback(streamingCallback);
                // Last unbind invokes service teardown; do not race a second release.
            } catch (RemoteException | RuntimeException e) { Log.w(TAG, "Video cleanup failed", e); }
        });
        videoWorker.shutdown();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }

        if (webView != null) {
            if (rootLayout != null) rootLayout.removeView(webView);
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }

    private class CustomWebChromeClient extends WebChromeClient {
        private boolean isScriptInjected = false;
        public void resetInjection() { isScriptInjected = false; }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (view != webView || activityDestroyed) return;
            if (loadingText != null && !catalogFailed && !isStreaming) {
                loadingText.setText("Carregando catálogo ou login Xbox… " + newProgress + "%");
            }
            if (!documentStartInjection && newProgress > 0 && !isScriptInjected && view.getUrl() != null && view.getUrl().contains("play")) {
                isScriptInjected = true;

                view.evaluateJavascript(webConfigurationScript() + loadScriptFromAssets(), null);
            }
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            Log.d(TAG, "WebView Console: " + consoleMessage.message());
            return true;
        }
    }

    private String webConfigurationScript() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return "window.BX_TARGET_REGION=" + org.json.JSONObject.quote(prefs.getString(KEY_REGION, "br"))
                + ";window.BX_TARGET_RES=" + org.json.JSONObject.quote(prefs.getString(KEY_RESOLUTION, "1080HQ"))
                + ";window.BX_TARGET_BITRATE=" + prefs.getInt(KEY_BITRATE, 20)
                + ";window.BX_USE_UNRELIABLE_INPUT=" + prefs.getBoolean(KEY_USE_UNRELIABLE_INPUT, true) + ";\n";
    }

    private String loadScriptFromAssets() {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getAssets().open("index.js");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        } catch (IOException ignored) {}
        return sb.toString();
    }

    public void showStreamingMenuDialog() {
        if (activityDestroyed) return;
        if (!startup.ready()) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Inicialização do CloudX")
                    .setMessage(startup.stageLabel())
                    .setPositiveButton("Tentar novamente", (d, w) -> retryLoading())
                    .setNeutralButton("Configurações Android", (d, w) -> openAndroidAppSettings())
                    .setNegativeButton("Sair do aplicativo", (d, w) -> finish()).show();
            return;
        }
        Dialog dialog = new Dialog(this, R.style.XboxDialogTheme);
        dialog.setContentView(R.layout.dialog_streaming_menu);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    getWindow().getDecorView().requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK);
                }
            }
        });
        Button btnExit = dialog.findViewById(R.id.btn_exit_game);
        Button btnSettings = dialog.findViewById(R.id.btn_settings);
        Button btnAppSettings = dialog.findViewById(R.id.btn_app_settings);
        Button btnReloadXbox = dialog.findViewById(R.id.btn_reload_xbox);
        Button btnPerformanceToggle = dialog.findViewById(R.id.btn_performance_toggle);
        Button btnNexus = dialog.findViewById(R.id.btn_open_nexus);
        Button btnMic = dialog.findViewById(R.id.btn_mic_toggle);
        Button btnClose = dialog.findViewById(R.id.btn_close_dialog);
        if(!isStreaming()){
            btnExit.setVisibility(View.GONE);
            btnPerformanceToggle.setVisibility(View.GONE);
            btnNexus.setVisibility(View.GONE);
            btnMic.setVisibility(View.GONE);
        }

        // Initial Mic State
        boolean micCurrentlyEnabled = microphoneEnabled;
        btnMic.setText(micCurrentlyEnabled ? "Microfone: solicitado" : "Microfone: Desativado");

        btnMic.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 101);
                dialog.dismiss();
                return;
            }

            try {
                if (streamingService != null) {
                    boolean newState = !microphoneEnabled;
                    microphoneEnabled = newState;
                    streamingService.setMicrophoneEnabled(newState);
                    btnMic.setText(newState ? "Microfone: solicitado" : "Microfone: Desativado");
                    showCustomToast(newState ? "Ativação do microfone solicitada" : "Microfone desativado");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to toggle mic", e);
            }
        });
        btnPerformanceToggle.setText(isPerformanceOverlayVisible ? "Ocultar desempenho no jogo" : "Mostrar desempenho no jogo");
        btnPerformanceToggle.setOnClickListener(v -> {
            isPerformanceOverlayVisible = !isPerformanceOverlayVisible;
            if (isPerformanceOverlayVisible) {
                if (performanceDialog == null) {
                    performanceDialog = new PerformanceDialog(this);
                }
                performanceDialog.show();
                try { streamingService.setPerformanceStatsEnabled(true); }
                catch (RemoteException e) { Log.e(TAG, "Cannot show performance states:"+ e.getMessage()); }
            } else {
                if (performanceDialog != null) {
                    try { streamingService.setPerformanceStatsEnabled(false); }
                    catch (RemoteException e) { Log.e(TAG, "Cannot close performance states:"+ e.getMessage()); }
                    performanceDialog.dismiss();
                }
            }
            dialog.dismiss();
        });

        btnExit.setOnClickListener(v -> { dialog.dismiss(); exitGameToCatalog(); });

        btnSettings.setOnClickListener(v -> {
            showSettingsDialog();
            dialog.dismiss();
        });

        btnAppSettings.setOnClickListener(v -> {
            showAppSettingsDialog();
            dialog.dismiss();
        });

        btnReloadXbox.setOnClickListener(v -> { dialog.dismiss(); exitGameToCatalog(); });

        btnNexus.setOnClickListener(v -> {
            if ("unreliableinput".equals(activeInputChannel) && gamepadListener != null) {
                gamepadListener.pressNexusOnce();
            } else if ("input".equals(activeInputChannel) && controllerLe != null) {
                controllerLe.pressNexusOnce();
            }
            dialog.dismiss();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        Button traceButton = dialog.findViewById(R.id.btn_input_trace);
        traceButton.setText(inputTrace.enabled(android.os.SystemClock.elapsedRealtime()) ? "Parar diagnóstico do controle" : "Diagnóstico do controle (10 min)");
        traceButton.setOnClickListener(v -> {
            long now = android.os.SystemClock.elapsedRealtime();
            if (inputTrace.enabled(now)) inputTrace.disable(); else inputTrace.enable(now);
            dialog.dismiss();
        });
        dialog.findViewById(R.id.btn_copy_report).setOnClickListener(v -> { dialog.dismiss(); showDiagnosticReport(); });
        btnExit.requestFocus();
    }


    private void destroySessionPage() {
        if (webRtcBridge != null) webRtcBridge.dispose();
        webViewState = null;
        if (webView != null) {
            WebView old = webView;
            webView = null;
            rootLayout.removeView(old);
            old.removeJavascriptInterface("AndroidBridge");
            old.setWebViewClient(null);
            old.setWebChromeClient(null);
            old.stopLoading();
            old.destroy();
        }
    }

    private void exitGameToCatalog() {
        if (closingSession || activityDestroyed) return;
        closingSession = true;
        sessionStatus = "Encerrando";
        onNativeSessionClosing();
        long generation = ++sessionGeneration;
        if (catalogTimeout != null) startupHandler.removeCallbacks(catalogTimeout);
        if (performanceDialog != null) performanceDialog.dismiss();
        surfaceView.setZOrderOnTop(false);
        surfaceView.setVisibility(View.GONE);
        destroySessionPage();
        showStartupProgress("Encerrando sessão…");
        Runnable deadline = () -> {
            if (closingSession && generation == sessionGeneration && !activityDestroyed)
                showStartupFailure("O serviço não terminou de encerrar. Abra Configurações Android para forçar a parada do CloudX.");
        };
        startupHandler.postDelayed(deadline, 15000);
        IStreamingService service = streamingService;
        runNativeOperation(() -> {
            boolean closed = true;
            try { if (service != null) service.closeSession(); }
            catch (RemoteException | RuntimeException e) { closed = false; Log.w(TAG, "Close failed", e); }
            final boolean success = closed;
            startupHandler.post(() -> {
                if (activityDestroyed || generation != sessionGeneration) return;
                startupHandler.removeCallbacks(deadline);
                closingSession = false;
                if (!success) { showStartupFailure("Falha ao encerrar o serviço. Reabra o aplicativo nas configurações Android."); return; }
                sessionStatus = "Catálogo";
                catalogFailed = false;
                showWebview();
            });
        });
    }

    private class PerformanceDialog extends Dialog {
        private TextView tvStats;

        public PerformanceDialog(@NonNull Context context) {
            super(context, android.R.style.Theme_Translucent_NoTitleBar);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.layout_performance_overlay);
            tvStats = findViewById(R.id.tv_performance_stats);

            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = 20; // top margin
            
            // Critical flags for non-modal behavior
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            
            getWindow().setAttributes(lp);
        }

        @Override public void dismiss() {
            super.dismiss();
            isPerformanceOverlayVisible = false;
            IStreamingService service = streamingService;
            if (service != null) {
                try { service.setPerformanceStatsEnabled(false); }
                catch (RemoteException e) { Log.w(TAG, "Cannot stop statistics", e); }
            }
        }

        public void updateStats(String stats) {
            if (tvStats != null) {
                lastPerformanceReport = stats;
                lastPerformanceAtMs = android.os.SystemClock.elapsedRealtime();
                boolean compact = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt("stats_detail", 0) == 1;
                tvStats.setText(compact ? compactStats(stats) : stats);
            }
        }
    }


    private class CXdialoge extends Dialog {
        private SeekBar sbDeadzone;
        private SeekBar sbSensitivity;
        private androidx.appcompat.widget.SwitchCompat swUnreliableInput;
        private StickTestView stickTestLeft;
        private StickTestView stickTestRight;
        private Button btnStartTesting;

        private boolean testingActive = false;

        private final float[] leftProcessed = new float[2];
        private final float[] rightProcessed = new float[2];

        CXdialoge(@NonNull Context context) {
            super(context, R.style.XboxDialogTheme);
            setCancelable(true);
        }

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.dialog_settings);

            TextView tvDeadzone = findViewById(R.id.tv_deadzone);
            sbDeadzone = findViewById(R.id.sb_deadzone);
            TextView tvSensitivity = findViewById(R.id.tv_sensitivity);
            sbSensitivity = findViewById(R.id.sb_sensitivity);
            swUnreliableInput = findViewById(R.id.sw_unreliable_input);
            stickTestLeft = findViewById(R.id.stick_test_left);
            stickTestRight = findViewById(R.id.stick_test_right);
            Button btnCancel = findViewById(R.id.btn_cancel);
            Button btnApply = findViewById(R.id.btn_apply);
            btnStartTesting = findViewById(R.id.btn_start_testing);

            btnStartTesting.setOnClickListener(v -> {
                if (controllerLe != null) controllerLe.releaseAll();
                testingActive = !testingActive;
                btnStartTesting.setText(testingActive ? "Encerrar teste" : "Testar analógicos");
                if (gamepadListener != null) {
                    gamepadListener.setTestModeActive(testingActive);
                }
                if (!testingActive) {
                    // Snap both dots back to center so the visualizer doesn't show a stale reading
                    // while testing is off.
                    stickTestLeft.setPositions(0f, 0f, 0f, 0f);
                    stickTestRight.setPositions(0f, 0f, 0f, 0f);
                }
            });

            if (gamepadListener != null) {
                float currentDeadzone = gamepadListener.getStickDeadzone();
                float currentSensitivity = gamepadListener.getCameraSensitivity();
                sbDeadzone.setProgress((int) (currentDeadzone * 100));
                tvDeadzone.setText(String.format(Locale.getDefault(), "Zona morta esquerda: %.2f", currentDeadzone));
                stickTestLeft.setDeadzone(currentDeadzone);
                stickTestRight.setDeadzone(currentDeadzone);
                sbSensitivity.setProgress((int) (currentSensitivity * 10));
                tvSensitivity.setText(String.format(Locale.getDefault(), "Sensibilidade da câmera: %.1f", currentSensitivity));

                SharedPreferences gpPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                swUnreliableInput.setChecked(gpPrefs.getBoolean(KEY_USE_UNRELIABLE_INPUT, true));
            }

            sbDeadzone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float deadzone = progress / 100.0f;
                    tvDeadzone.setText(String.format(Locale.getDefault(), "Zona morta esquerda: %.2f", deadzone));
                    stickTestLeft.setDeadzone(deadzone);
                    stickTestRight.setDeadzone(deadzone);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            sbSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    tvSensitivity.setText(String.format(Locale.getDefault(), "Sensibilidade da câmera: %.1f", progress / 10.0f));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            btnCancel.setOnClickListener(v -> dismiss());

            btnApply.setOnClickListener(v -> {
                float newDeadzone = InputMath.leftDeadzone(sbDeadzone.getProgress() / 100.0f);
                float newSensitivity = sbSensitivity.getProgress() / 10.0f;
                boolean useUnreliable = swUnreliableInput.isChecked();

                if (gamepadListener != null) {
                    gamepadListener.setStickDeadzone(newDeadzone);
                    gamepadListener.setCameraSensitivity(newSensitivity);
                }
                if (controllerLe != null) {
                    controllerLe.setStickDeadzone(newDeadzone);
                    controllerLe.setCameraSensitivity(newSensitivity);
                }
                
                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                editor.putFloat("camera_deadzone", newDeadzone);
                editor.putFloat("gamepad_sensitivity", newSensitivity);
                editor.putBoolean(KEY_USE_UNRELIABLE_INPUT, useUnreliable);
                editor.apply();

                if (gamepadListener != null) gamepadListener.releaseAll();
                if (controllerLe != null) controllerLe.releaseAll();
                inputSession.preferModern(useUnreliable);
                updateInputRoute();
                if (webView != null) {
                    webView.evaluateJavascript("if (window.setUseUnreliableInput) { window.setUseUnreliableInput(" + useUnreliable + "); }", null);
                }

                dismiss();
            });
        }

        @Override
        public void show() {
            super.show();
            Button btnApply = findViewById(R.id.btn_apply);
            if (btnApply != null) btnApply.requestFocus();
        }

        @Override
        public void dismiss() {
            testingActive = false;
            if (gamepadListener != null) gamepadListener.setTestModeActive(false);
            if (controllerLe != null) controllerLe.releaseAll();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Objects.requireNonNull(getWindow()).getDecorView().requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK);
            }
            super.dismiss();
        }

        // --- Live stick test: intercepted directly from the window's joystick events. No dependency
        // on AndroidGamepadListener's callback pipeline — the dot positions are computed straight from
        // whatever the sliders currently show (even pre-Apply), same as before. ---



        @Override
        public boolean dispatchGenericMotionEvent(@NonNull MotionEvent event) {
            if (!testingActive) {
                return super.dispatchGenericMotionEvent(event);
            }

            if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
                return super.dispatchGenericMotionEvent(event);
            }

            InputDevice device = event.getDevice();
            if (device == null || device.isVirtual()) {
                return super.dispatchGenericMotionEvent(event);
            }

            int axisRSX = MotionEvent.AXIS_Z;
            int axisRSY = MotionEvent.AXIS_RZ;
            float curveExponent = 1.0f;
            if (gamepadListener != null) {
                axisRSX = gamepadListener.getRightStickAxisX();
                axisRSY = gamepadListener.getRightStickAxisY();
                curveExponent = gamepadListener.getRightStickResponseCurve();
            }

            float rawLeftX = event.getAxisValue(MotionEvent.AXIS_X);
            float rawLeftY = event.getAxisValue(MotionEvent.AXIS_Y);
            float rawRightX = event.getAxisValue(axisRSX);
            float rawRightY = event.getAxisValue(axisRSY);

            float liveDeadzone = sbDeadzone.getProgress() / 100.0f;
            float liveSensitivity = sbSensitivity.getProgress() / 10.0f;

            // Left stick: deadzone only, no camera sensitivity (matches the real input path).
            AndroidGamepadListener.computeStickResponse(
                    rawLeftX, rawLeftY, InputMath.leftDeadzone(liveDeadzone), 1.0f, 1.0f, leftProcessed);

            // Right stick: deadzone + curve + the sensitivity value currently on the slider.
            AndroidGamepadListener.computeStickResponse(
                    rawRightX, rawRightY, 0f, curveExponent, liveSensitivity, rightProcessed);

            stickTestLeft.setPositions(rawLeftX, rawLeftY, leftProcessed[0], leftProcessed[1]);
            stickTestRight.setPositions(rawRightX, rawRightY, rightProcessed[0], rightProcessed[1]);

            return true; // consumed — swallow so it doesn't also drive game input while the dialog is open
        }
    }

    // --- Activity method: now just builds and shows the dialog above ---
    private void showSettingsDialog() {
        CXdialoge dialog = new CXdialoge(this);
        dialog.show();
    }

    private int savedVideoProfile() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return Math.max(0, Math.min(3, p.getInt("video_profile_mode", p.getBoolean(KEY_HIGH_VIDEO_PROFILE, false) ? 1 : 0)));
    }
    public boolean prefersHighVideoProfile() {
        int mode = acceptInputCallbacks ? activeVideoProfile : savedVideoProfile();
        return mode == 1 || mode == 3;
    }

    private void setupChoices(android.widget.Spinner spinner, String[] values, int selected) {
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setSelection(Math.max(0, Math.min(values.length - 1, selected)));
    }
    private static String compactStats(String stats) {
        StringBuilder out = new StringBuilder();
        for (String line : stats.split("\n")) {
            if (line.startsWith("Vídeo recebido") || line.startsWith("FPS recebidos") || line.startsWith("Codec:")
                    || line.startsWith("Último quadro") || line.startsWith("Saída de áudio") || line.startsWith("Controle:")
                    || line.startsWith("Telemetria")) out.append(line).append('\n');
        }
        return out.toString();
    }
    private void showDiagnosticReport() {
        String version = "n/d";
        try { android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException ignored) {}
        String report = "CloudX " + version + "\nTV: " + Build.MODEL
                + " | Android " + Build.VERSION.RELEASE + "\nEstado: " + sessionStatus + "\n"
                + "Última amostra: " + (lastPerformanceAtMs < 0 ? "não coletada" : ((android.os.SystemClock.elapsedRealtime() - lastPerformanceAtMs) / 1000) + " s atrás") + "\n"
                + lastPerformanceReport + "\n\nControle (Menu moderno é contador de transições):\n" + inputTrace.report();
        TextView text = new TextView(this); text.setText(report); text.setTextIsSelectable(true); text.setPadding(24,24,24,24);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(text);
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Diagnóstico local").setView(scroll)
                .setPositiveButton("Copiar", (d,w) -> ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(android.content.ClipData.newPlainText("CloudX diagnóstico", report)))
                .setNegativeButton("Fechar", null).show();
    }

    private void showAppSettingsDialog() {
        Dialog dialog = new Dialog(this, R.style.XboxDialogTheme);
        dialog.setContentView(R.layout.dialog_app_settings);
        dialog.setCancelable(true);

        androidx.appcompat.widget.AppCompatSpinner spinnerRegion = dialog.findViewById(R.id.spinner_region);
        androidx.appcompat.widget.AppCompatSpinner spinnerRes = dialog.findViewById(R.id.spinner_resolution);
        androidx.appcompat.widget.AppCompatSpinner spinnerBitrate = dialog.findViewById(R.id.spinner_bitrate);
        androidx.appcompat.widget.AppCompatSpinner spinnerUA = dialog.findViewById(R.id.spinner_user_agent);
        androidx.appcompat.widget.AppCompatSpinner spinnerVideoProfile = dialog.findViewById(R.id.spinner_video_profile);
        androidx.appcompat.widget.SwitchCompat lowLatencySwitch = dialog.findViewById(R.id.switch_low_latency);
        lowLatencySwitch.setChecked(prefersMinimalPostProcessing());
        lowLatencySwitch.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        String[] profileLabels = {"Preferir Baseline (estável)", "Preferir High", "Automático", "Somente High (experimental)"};
        android.widget.ArrayAdapter<String> profileAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, profileLabels);
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerVideoProfile.setAdapter(profileAdapter);
        spinnerVideoProfile.setSelection(savedVideoProfile());
        SharedPreferences qualityPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        android.widget.Spinner outputScale = dialog.findViewById(R.id.spinner_output_scale);
        android.widget.Spinner sharpness = dialog.findViewById(R.id.spinner_sharpness);
        android.widget.Spinner statsDetail = dialog.findViewById(R.id.spinner_stats_detail);
        setupChoices(outputScale, new String[]{"Original (padrão)", "Ampliar até 1080p", "Ampliar até a tela / 4K (maior custo)"}, qualityPrefs.getInt("video_output_scale", 0));
        setupChoices(sharpness, new String[]{"Desligada", "Leve", "Moderada"}, qualityPrefs.getInt("video_sharpness", 0));
        setupChoices(statsDetail, new String[]{"Detalhadas", "Compactas"}, qualityPrefs.getInt("stats_detail", 0));
        androidx.appcompat.widget.SwitchCompat rumble = dialog.findViewById(R.id.switch_rumble);
        rumble.setChecked(qualityPrefs.getBoolean("rumble_enabled", true));
        TextView tvScale = dialog.findViewById(R.id.tv_initial_scale);
        SeekBar sbScale = dialog.findViewById(R.id.sb_initial_scale);
        Button btnCancel = dialog.findViewById(R.id.btn_cancel);
        Button btnApply = dialog.findViewById(R.id.btn_apply);

        // Region options
        String[] regions = {"us", "br", "kr", "jp", "pl", "es", "uk", "fr"};
        String[] regionLabels = {"Estados Unidos", "Brasil", "Coreia do Sul", "Japão", "Polônia", "Espanha", "Reino Unido", "França"};
        android.widget.ArrayAdapter<String> regionAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, regionLabels);
        regionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRegion.setAdapter(regionAdapter);

        // Resolution options
        String[] resolutions = {"Auto","720","720HQ", "1080","1080HQ", "1440"};
        android.widget.ArrayAdapter<String> resAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, resolutions);
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRes.setAdapter(resAdapter);

        // Bitrate options
        Integer[] bitrateValues = {0, 4, 6, 8, 10, 12, 15, 18, 20};
        String[] bitrateLabels = {"Automático (20 Mbps)", "4 Mbps (menor qualidade)", "6 Mbps", "8 Mbps", "10 Mbps", "12 Mbps", "15 Mbps", "18 Mbps", "20 Mbps (teto recomendado)"};
        android.widget.ArrayAdapter<String> bitrateAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, bitrateLabels);
        bitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBitrate.setAdapter(bitrateAdapter);

        // User Agent options
        String[] uaKeys = {"default", "edge", "chrome", "tizen", "android_tv", "fire_tv", "xbox", "ps5", "android_mobile", "ios", "mac"};
        String[] uaLabels = {"Padrão", "Edge (Desktop)", "Chrome (Desktop)", "Samsung TV (Tizen)", "Android TV", "Fire TV", "Xbox", "PlayStation 5", "Android Mobile", "iOS Phone", "macOS (Safari)"};
        android.widget.ArrayAdapter<String> uaAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, uaLabels);
        uaAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerUA.setAdapter(uaAdapter);

        // Load current selection
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String currentRegion = prefs.getString(KEY_REGION, "br");
        String currentRes = prefs.getString(KEY_RESOLUTION, "1080HQ");
        int currentBitrate = prefs.getInt(KEY_BITRATE, 20);
        String currentUA = prefs.getString(KEY_USER_AGENT, "default");
        int currentScale = prefs.getInt(KEY_INITIAL_SCALE, 0);

        for (int i = 0; i < regions.length; i++) {
            if (regions[i].equals(currentRegion)) {
                spinnerRegion.setSelection(i);
                break;
            }
        }
        for (int i = 0; i < resolutions.length; i++) {
            if (resolutions[i].equals(currentRes)) {
                spinnerRes.setSelection(i);
                break;
            }
        }
        for (int i = 0; i < bitrateValues.length; i++) {
            if (bitrateValues[i] == currentBitrate) {
                spinnerBitrate.setSelection(i);
                break;
            }
        }
        for (int i = 0; i < uaKeys.length; i++) {
            if (uaKeys[i].equals(currentUA)) {
                spinnerUA.setSelection(i);
                break;
            }
        }

        dialog.findViewById(R.id.btn_tl042_preset).setOnClickListener(v -> {
            spinnerRes.setSelection(java.util.Arrays.asList(resolutions).indexOf(TvCompatibilityProfile.RESOLUTION));
            spinnerBitrate.setSelection(java.util.Arrays.asList(bitrateValues).indexOf(TvCompatibilityProfile.BITRATE_MBPS));
            spinnerVideoProfile.setSelection(0); outputScale.setSelection(0); sharpness.setSelection(0); lowLatencySwitch.setChecked(false);
            showCustomToast("Perfil TL042: 720p, até 8 Mbps e modo Estável. Selecione Aplicar e reabra o jogo.");
        });

        dialog.findViewById(R.id.btn_tcl_quality).setOnClickListener(v -> {
            spinnerRes.setSelection(java.util.Arrays.asList(resolutions).indexOf("1080HQ"));
            spinnerBitrate.setSelection(java.util.Arrays.asList(bitrateValues).indexOf(20));
            spinnerVideoProfile.setSelection(0); outputScale.setSelection(0); sharpness.setSelection(0); lowLatencySwitch.setChecked(false);
        });
        dialog.findViewById(R.id.btn_tcl_balanced).setOnClickListener(v -> {
            spinnerRes.setSelection(java.util.Arrays.asList(resolutions).indexOf("1080"));
            spinnerBitrate.setSelection(java.util.Arrays.asList(bitrateValues).indexOf(15));
            spinnerVideoProfile.setSelection(0); outputScale.setSelection(0); sharpness.setSelection(0); lowLatencySwitch.setChecked(false);
        });
        sbScale.setProgress(currentScale);
        tvScale.setText(currentScale == 0 ? "Escala inicial: automática" : "Escala inicial: " + currentScale + "%");

        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvScale.setText(progress == 0 ? "Escala inicial: automática" : "Escala inicial: " + progress + "%");
                // Preview is applied only after confirmation.
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnApply.setOnClickListener(v -> {
            String selectedRegion = regions[spinnerRegion.getSelectedItemPosition()];
            String selectedRes = resolutions[spinnerRes.getSelectedItemPosition()];
            int selectedBitrate = bitrateValues[spinnerBitrate.getSelectedItemPosition()];
            String selectedUA = uaKeys[spinnerUA.getSelectedItemPosition()];
            int selectedScale = sbScale.getProgress();

            boolean uaChanged = !selectedUA.equals(currentUA);
            boolean scaleChanged = selectedScale != currentScale;

            prefs.edit()
                    .putString(KEY_REGION, selectedRegion)
                    .putString(KEY_RESOLUTION, selectedRes)
                    .putInt(KEY_BITRATE, selectedBitrate)
                    .putInt("video_profile_mode", spinnerVideoProfile.getSelectedItemPosition())
                    .putInt("video_output_scale", outputScale.getSelectedItemPosition())
                    .putInt("video_sharpness", sharpness.getSelectedItemPosition())
                    .putInt("stats_detail", statsDetail.getSelectedItemPosition())
                    .putBoolean("rumble_enabled", rumble.isChecked())
                    .putBoolean(KEY_HIGH_VIDEO_PROFILE, spinnerVideoProfile.getSelectedItemPosition() == 1 || spinnerVideoProfile.getSelectedItemPosition() == 3)
                    .putBoolean(KEY_LOW_LATENCY, lowLatencySwitch.isChecked())
                    .putString(KEY_USER_AGENT, selectedUA)
                    .putInt(KEY_INITIAL_SCALE, selectedScale)
                    .apply();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                getWindow().setPreferMinimalPostProcessing(isStreaming && lowLatencySwitch.isChecked());
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Configurações salvas")
                    .setMessage("Perfil, resolução, taxa, escala e nitidez serão aplicados na próxima sessão. Somente High pode ser recusado pelo serviço. A escala 4K aumenta o custo da GPU.")
                    .setPositiveButton("OK", null).show();

            if (webView != null) {
                @SuppressLint("DefaultLocale") String js = String.format("window.BX_TARGET_REGION = '%s'; window.BX_TARGET_RES = '%s'; window.BX_TARGET_BITRATE = %d;",
                        selectedRegion, selectedRes, selectedBitrate);
                webView.evaluateJavascript(js, null);

                if (scaleChanged && !isStreaming) {
                    webView.setInitialScale(selectedScale);
                }
                
                if (!isStreaming && (uaChanged || scaleChanged)) {
                    if (uaChanged) {
                        String uaString = USER_AGENTS.get(selectedUA);
                        webView.getSettings().setUserAgentString(uaString);
                        showCustomToast("Perfil do navegador alterado. Recarregando...");
                        closeWebview();
                        showWebview();
                    } else {
                        webView.setInitialScale(selectedScale);

                        showCustomToast("Escala alterada.");
                    }

                } else {
                    showCustomToast("Configurações salvas. Encerre e reabra o jogo para aplicar o vídeo.");
                }
            }
            dialog.dismiss();
        });

        dialog.show();
        btnApply.requestFocus();
    }
}
