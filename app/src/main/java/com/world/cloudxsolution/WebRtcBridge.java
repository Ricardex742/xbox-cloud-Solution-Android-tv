package com.world.cloudxsolution;

import android.util.Log;
import android.webkit.JavascriptInterface;

import java.util.HashMap;

/**
 * Still lives in the MAIN process -- addJavascriptInterface requires being
 * in the same process as the WebView it's attached to, so this class can't
 * move to :stream. What changes: instead of calling `webRtcReceiver.*` /
 * `activity.*` directly, every method now relays across AIDL to whichever
 * IStreamingService is currently bound. Keep-alive logic (HTTP polling) is
 * is scoped to the lifetime of the current session.
 */
public class WebRtcBridge {
    private static final String TAG = "WebRtcBridge";
    private final MainActivity activity;
    private String keepAliveUrl;
    private String keepAliveToken;
    private boolean keepAliveActive;
    private int keepAliveGeneration;
    private final android.os.Handler keepAliveHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            if (!keepAliveActive || keepAliveUrl == null || keepAliveToken == null) return;
            final int generation = keepAliveGeneration;

            RequestNetwork request = new RequestNetwork(activity);
            HashMap<String, Object> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + keepAliveToken);
            request.setHeaders(headers);

            request.startRequestNetwork(RequestNetworkController.POST, keepAliveUrl, "keepalive", new RequestNetwork.RequestListener() {
                @Override
                public void onResponse(String tag, String response, HashMap<String, Object> responseHeaders) {
                    if (!keepAliveActive || generation != keepAliveGeneration) return;
                    Log.i(TAG, "Keep-alive success; retaining session WebView");
                    keepAliveHandler.postDelayed(keepAliveRunnable, 60000);
                }
                @Override
                public void onErrorResponse(String tag, String message) {
                    if (!keepAliveActive || generation != keepAliveGeneration) return;
                    if ("HTTP 401".equals(message) || "HTTP 403".equals(message)
                            || "HTTP 404".equals(message) || "HTTP 410".equals(message)) {
                        stopKeepAlive();
                        activity.showCustomToast("A sessão não aceitou a renovação. Encerre e reabra o jogo.");
                        return;
                    }
                    Log.w(TAG, "Keep-alive request failed; retrying");
                    keepAliveHandler.postDelayed(keepAliveRunnable, 15000);
                }
            });
        }
    };

    public WebRtcBridge(MainActivity activity) {
        this.activity = activity;
        // NOTE: no more `WebRtcReceiver webRtcReceiver` param -- WebRtcReceiver
        // now lives inside StreamingService, in the other process. Every method
        // below goes through activity.getStreamingService() (the bound AIDL
        // proxy) instead. See MainActivity ServiceConnection changes.
    }

    private IStreamingService service() {
        IStreamingService s = activity.getStreamingService();
        if (s == null) activity.reportSignalingError("service-unavailable");
        return s;
    }

    @JavascriptInterface
    public void onOfferReceived(final String sdp) {
        activity.runOnUiThread(() -> {
            IStreamingService s = service();
            if (s == null) return;
            try { s.handleRemoteOffer(sdp); } catch (Exception e) { Log.e(TAG, "onOfferReceived", e); activity.reportSignalingError("onOfferReceived"); }
        });
    }

    @JavascriptInterface
    public void onIceCandidateReceived(String sdpMid, int sdpMLineIndex, String candidate) {
        activity.runOnUiThread(() -> {
            IStreamingService s = service();
            if (s == null) return;
            try { s.addIceCandidate(sdpMid, sdpMLineIndex, candidate); } catch (Exception e) { Log.e(TAG, "iceCandidate", e); }
        });
    }

    @JavascriptInterface
    public void onOfferRequested(int bitrateMbps) {
        activity.runOnUiThread(() -> {
            IStreamingService s = service();
            if (s == null) return;
            try { s.createOffer(Math.max(0, Math.min(20, bitrateMbps)) * 1000, activity.prefersHighVideoProfile()); } catch (Exception e) { Log.e(TAG, "createOffer", e); activity.reportSignalingError("createOffer"); }
        });
    }

    @JavascriptInterface
    public void onPeerConnectionConfig(String configJson) {
        activity.onPeerConnectionConfigReceived(configJson);
    }

    @JavascriptInterface
    public void onAnswerReceived(String sdp) {
        activity.runOnUiThread(() -> {
            IStreamingService s = service();
            if (s == null) return;
            try { s.handleRemoteAnswer(sdp); } catch (Exception e) { Log.e(TAG, "onAnswerReceived", e); activity.reportSignalingError("onAnswerReceived"); }
        });
    }

    @JavascriptInterface
    public void onDataChannelCreate(String label) {
        activity.runOnUiThread(() -> {
            IStreamingService s = service();
            if (s == null) return;
            try { s.createDataChannel(label); } catch (Exception e) { Log.e(TAG, "createDataChannel", e); }
        });
    }

    @JavascriptInterface
    public void onDataChannelSend(String label, byte[] binary, String data, boolean isBinary) {
        activity.onDataChannelSend(label, binary, data, isBinary);
    }

    @JavascriptInterface
    public void gamepadIndex(int idx) {
        activity.runOnUiThread(() -> activity.setGamepadIndex(idx));
        activity.showCustomToast("Índice do controle: " + idx);
    }

    @JavascriptInterface
    public void setNativeGamepadEnabled(boolean enabled) {
        activity.runOnUiThread(() -> activity.setNativeGamepadEnabled(enabled));
    }

    @JavascriptInterface
    public void onAddTransceiver(String trackOrKind, String direction) {
        activity.runOnUiThread(() -> {
            IStreamingService s = service();
            if (s == null) return;
            try { s.addTransceiver(trackOrKind, direction); } catch (Exception e) { Log.e(TAG, "addTransceiver", e); }
        });
    }

    @JavascriptInterface
    public void onPeerConnectionClose() {
        activity.runOnUiThread(() -> {
            stopKeepAlive();
            IStreamingService s = service();
            if (s == null) return;
            try { s.closeSession(); } catch (Exception e) { Log.e(TAG, "closeSession", e); }
        });
    }

    @JavascriptInterface
    public void setGamepadSettings(float deadzone, float sensitivity) {
        activity.showCustomToast("Controle: zona morta=" + deadzone + ", sensibilidade=" + sensitivity);
        activity.runOnUiThread(() -> activity.updateGamepadSettings(deadzone, sensitivity));
    }

    @JavascriptInterface
    public void setStreamingState(boolean isStreaming) {
        activity.runOnUiThread(() -> {
            activity.setStreamingState(isStreaming);
            IStreamingService s = service();
            if (s == null) return;
            try { s.setStreamingState(isStreaming); } catch (Exception e) { Log.e(TAG, "setStreamingState", e); }
        });
    }

    @JavascriptInterface
    public void reloadWebview() {
        activity.runOnUiThread(() -> {
            stopKeepAlive();
            if (activity.webView != null) activity.webView.reload();
        });
    }

    @JavascriptInterface
    public void setWebviewVisible() {
        activity.runOnUiThread(activity::setWebviewVisible);
    }

    @JavascriptInterface
    public void keepalive(String url, String token) {
        activity.runOnUiThread(() -> {
            if (url == null || token == null || token.isEmpty()) return;
            if (keepAliveActive && url.equals(keepAliveUrl) && token.equals(keepAliveToken)) return;
            stopKeepAlive();
            keepAliveUrl = url;
            keepAliveToken = token;
            keepAliveActive = true;
            keepAliveHandler.post(keepAliveRunnable);
        });
    }

    // Called on the UI thread; stale HTTP callbacks cannot resurrect a stopped session.
    public void stopKeepAlive() {
        keepAliveActive = false;
        keepAliveGeneration++;
        keepAliveHandler.removeCallbacks(keepAliveRunnable);
        keepAliveUrl = null;
        keepAliveToken = null;
    }
}
