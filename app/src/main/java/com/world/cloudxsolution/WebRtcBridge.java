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
 * scoped to the current session, without destroying its WebView.
 */
public class WebRtcBridge {
    private static final String TAG = "WebRtcBridge";
    private final MainActivity activity;
    private volatile boolean active = true;
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

    private void runIfActive(Runnable action) {
        activity.runOnUiThread(() -> { if (active) action.run(); });
    }

    public void dispose() {
        active = false;
        stopKeepAlive();
    }

    private IStreamingService service() {
        if (!active) return null;
        IStreamingService s = activity.getStreamingService();
        if (s == null) Log.w(TAG, "StreamingService not bound yet, dropping call");
        return s;
    }

    @JavascriptInterface
    public void onOfferReceived(final String sdp) {
        if (!active) return;
        runIfActive(() -> {
            IStreamingService s = service();
            if (s == null) return;
            try { s.handleRemoteOffer(sdp); } catch (Exception e) { Log.e(TAG, "onOfferReceived", e); }
        });
    }

    @JavascriptInterface
    public void onIceCandidateReceived(String sdpMid, int sdpMLineIndex, String candidate) {
        if (!active) return;
        runIfActive(() -> {
            IStreamingService s = service();
            if (s == null) return;
            try { s.addIceCandidate(sdpMid, sdpMLineIndex, candidate); } catch (Exception e) { Log.e(TAG, "iceCandidate", e); }
        });
    }

    @JavascriptInterface
    public void onOfferRequested(int bitrateMbps) {
        if (!active) return;
        runIfActive(() -> {
            IStreamingService s = service();
            if (s == null) return;
            try { s.createOffer(Math.max(0, Math.min(20, bitrateMbps)) * 1000, activity.prefersHighVideoProfile()); } catch (Exception e) { Log.e(TAG, "createOffer", e); }
        });
    }

    @JavascriptInterface
    public void onPeerConnectionConfig(String configJson) {
        if (!active) return;
        runIfActive(() -> activity.onPeerConnectionConfigReceived(configJson));
    }

    @JavascriptInterface
    public void onPeerConnectionConfigurationUpdate(String configJson) {
        if (!active) return;
        runIfActive(() -> {
            IStreamingService s = service();
            if (s == null) return;
            try { s.updatePeerConfiguration(configJson); }
            catch (Exception e) { Log.e(TAG, "updatePeerConfiguration", e); }
        });
    }

    @JavascriptInterface
    public void onAnswerReceived(String sdp) {
        if (!active) return;
        runIfActive(() -> {
            IStreamingService s = service();
            if (s == null) return;
            try { s.handleRemoteAnswer(sdp); } catch (Exception e) { Log.e(TAG, "onAnswerReceived", e); }
        });
    }

    @JavascriptInterface
    public void onDataChannelCreate(String label) {
        if (!active) return;
        runIfActive(() -> {
            IStreamingService s = service();
            if (s == null) return;
            try { s.createDataChannel(label); } catch (Exception e) { Log.e(TAG, "createDataChannel", e); }
        });
    }

    @JavascriptInterface
    public void onDataChannelSend(String label, byte[] binary, String data, boolean isBinary) {
        if (!active) return;
        activity.onDataChannelSend(label, binary, data, isBinary);
    }

    @JavascriptInterface
    public void onControlAuthorizationSent() {
        if (!active) return;
        runIfActive(activity::onControlAuthorizationSent);
    }

    @JavascriptInterface
    public void gamepadIndex(int idx) {
        if (!active) return;
        runIfActive(() -> activity.setGamepadIndex(idx));
        activity.showCustomToast("Índice do controle: " + idx);
    }

    @JavascriptInterface
    public void setNativeGamepadEnabled(boolean enabled) {
        if (!active) return;
        runIfActive(() -> activity.setNativeGamepadEnabled(enabled));
    }

    @JavascriptInterface
    public void onAddTransceiver(String trackOrKind, String direction) {
        if (!active) return;
        runIfActive(() -> {
            IStreamingService s = service();
            if (s == null) return;
            try { s.addTransceiver(trackOrKind, direction); } catch (Exception e) { Log.e(TAG, "addTransceiver", e); }
        });
    }

    @JavascriptInterface
    public void onPeerConnectionClose() {
        if (!active) return;
        runIfActive(() -> {
            IStreamingService s = service();
            if (s == null) return;
            activity.setStreamingState(false);
            try { s.closeSession(); } catch (Exception e) { Log.e(TAG, "closeSession", e); }
        });
    }

    @JavascriptInterface
    public void setGamepadSettings(float deadzone, float sensitivity) {
        if (!active) return;
        activity.showCustomToast("Controle: zona morta=" + deadzone + ", sensibilidade=" + sensitivity);
        runIfActive(() -> activity.updateGamepadSettings(deadzone, sensitivity));
    }

    @JavascriptInterface
    public void setStreamingState(boolean isStreaming) {
        if (!active) return;
        runIfActive(() -> {
            activity.setStreamingState(isStreaming);
            IStreamingService s = service();
            if (s == null) return;
            try { s.setStreamingState(isStreaming); } catch (Exception e) { Log.e(TAG, "setStreamingState", e); }
        });
    }

    @JavascriptInterface
    public void reloadWebview() {
        if (!active) return;
        runIfActive(() -> {
            stopKeepAlive();
            if (activity.webView != null) activity.webView.reload();
        });
    }

    @JavascriptInterface
    public void setWebviewVisible() {
        if (!active) return;
        runIfActive(activity::setWebviewVisible);
    }

    @JavascriptInterface
    public void keepalive(String url, String token) {
        if (!active) return;
        runIfActive(() -> {
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
