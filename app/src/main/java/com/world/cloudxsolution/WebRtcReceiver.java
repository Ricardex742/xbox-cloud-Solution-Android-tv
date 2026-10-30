package com.world.cloudxsolution;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.BuiltinAudioDecoderFactoryFactory;
import org.webrtc.BuiltinAudioEncoderFactoryFactory;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Loggable;
import org.webrtc.Logging;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RtpCapabilities;
import org.webrtc.RtpTransceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;
import org.webrtc.WebRtcLoggingHelper;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class WebRtcReceiver implements Loggable {

    private static final String TAG = "WebRtcReceiver";

    public final Context appContext;
    public final StreamHost host;
    private final EglBase eglBase;

    private PeerConnectionFactory peerConnectionFactory;
    private volatile PeerConnection peerConnection;
    private volatile long connectionGeneration;
    private AudioDeviceModule audioDeviceModule;
    private final java.util.concurrent.ConcurrentHashMap<String, DataChannel> dataChannels = new java.util.concurrent.ConcurrentHashMap<>();
    private long lastSendWarningMs;
    private final java.util.concurrent.ConcurrentHashMap<String, DataChannel.State> channelStates = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long acceptedInputPackets, rejectedInputPackets;
    private volatile long lastDecodedFrameMs = -1;
    private volatile String audioOutputState = "aguardando";
    private volatile boolean remoteAudioSeen;
    private final PlaybackHealthStats playbackHealth = new PlaybackHealthStats();

    private SurfaceViewRenderer remoteRenderer;
    private SignalingListener signalingListener;
    private volatile boolean isReleased = false;

    private boolean codecLogged = false;
    private boolean preferHighProfile;
    private int videoProfileMode;
    public void configureVideoOptions(int mode) { videoProfileMode = Math.max(0, Math.min(3, mode)); }
    private boolean hardwareHighAvailable, highOffered;
    private String offeredProfiles = "[]", answeredProfiles = "[]";
    private final VideoStatsSampler videoStatsSampler = new VideoStatsSampler();
    private android.os.HandlerThread statsThread;
    private android.os.Handler statsHandler;
    private long statsEpoch, statsRequestId;
    private volatile String lastRendererStats = "Renderizador: aguardando amostra";
    private volatile long lastRendererStatsAtMs = -1;
    private String lastStatsSummary = "Recepção: aguardando amostra";
    private long statsRequestedAtMs;
    private final Runnable statsTick = new Runnable() {
        @Override public void run() {
            final long tickEpoch;
            synchronized (statsLock) {
                if (!showStats || isReleased) return;
                tickEpoch = statsEpoch;
            }
            reportVideoStats();
            synchronized (statsLock) {
                // A toggle may have scheduled a replacement tick while this one was running.
                if (showStats && !isReleased && tickEpoch == statsEpoch && statsHandler != null) {
                    statsHandler.postDelayed(this, 4000);
                }
            }
        }
    };
    private boolean statsPending;
    private final Object statsLock = new Object();

    private VideoTrack remoteVideoTrack;
    private org.webrtc.AudioSource audioSource;
    private org.webrtc.AudioTrack localAudioTrack;
    private org.webrtc.RtpSender localAudioSender;
    private boolean micEnabled = false;

    public VideoTrack getRemoteVideoTrack() {
        return remoteVideoTrack;
    }
    private volatile boolean showStats = false;
    public void enablePerfStats(boolean enabled) {
        synchronized (statsLock) {
            showStats = enabled;
            statsEpoch++;
            // Keep an outstanding query occupied when toggling the overlay.
            videoStatsSampler.reset();
            playbackHealth.reset();
            if (statsHandler != null) statsHandler.removeCallbacks(statsTick);
            if (enabled && !isReleased) {
                if (statsThread == null) {
                    statsThread = new android.os.HandlerThread("CloudX-stats", android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    statsThread.start();
                    statsHandler = new android.os.Handler(statsThread.getLooper());
                }
                statsHandler.post(statsTick);
            }
        }
    }

    public void noteDecodedFrame() { lastDecodedFrameMs = android.os.SystemClock.elapsedRealtime(); }

    public interface SignalingListener {
        void onLocalIceCandidate(IceCandidate candidate);
        void onIceConnectionChange(PeerConnection.IceConnectionState state);
        void onIceGatheringChange(PeerConnection.IceGatheringState state);
        void onTrackReceived(String kind, String id);
        void onPerformanceStatsReceived(String stats);
        void onRemoteDescriptionSet();
        void onSignalingError(String operation);
    }

    public WebRtcReceiver(StreamHost host) {
        this.host = host;
        this.appContext = host.getAppContext();
        this.eglBase = EglBase.create();
        Log.i(TAG, "WebRtcReceiver initialized");
    }

    public void setSignalingListener(SignalingListener listener) {
        this.signalingListener = listener;
    }

    public EglBase.Context getEglBaseContext() {
        return eglBase.getEglBaseContext();
    }

    private org.webrtc.VideoSink pendingRenderTarget;
    public void setPendingRenderTarget(org.webrtc.VideoSink renderer) {
        this.pendingRenderTarget = renderer;
    }

    public org.webrtc.VideoSink getPendingRenderTarget() {
        return pendingRenderTarget;
    }


    public synchronized void initPeerConnectionFactory() {
        if (peerConnectionFactory != null) return;
        Log.i(TAG, "Initializing PeerConnectionFactory");

        PeerConnectionFactory.InitializationOptions initOptions =
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        WebRtcLoggingHelper.injectCustomLogger(this, Logging.Severity.LS_INFO);

        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setAudioTrackStateCallback(new JavaAudioDeviceModule.AudioTrackStateCallback() {
                    @Override public void onWebRtcAudioTrackStart() { audioOutputState = "iniciada"; }
                    @Override public void onWebRtcAudioTrackStop() { audioOutputState = "parada"; }
                })
                .setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback() {
                    @Override public void onWebRtcAudioTrackInitError(String message) {
                        audioOutputState = "erro de inicialização";
                        Log.w(TAG, "AudioTrack initialization failed: " + message);
                    }
                    @Override public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode code, String message) {
                        audioOutputState = "erro ao iniciar: " + code;
                        Log.w(TAG, "AudioTrack start failed: " + message);
                    }
                    @Override public void onWebRtcAudioTrackError(String message) {
                        audioOutputState = "erro de reprodução";
                        Log.w(TAG, "AudioTrack failed: " + message);
                    }
                })
                .setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .createAudioDeviceModule();

        DefaultVideoEncoderFactory encoderFactory =
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        org.webrtc.CapabilityVideoDecoderFactory decoderFactory =
                new org.webrtc.CapabilityVideoDecoderFactory(eglBase.getEglBaseContext());
        hardwareHighAvailable = decoderFactory.hasHardwareHigh();

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .setAudioEncoderFactoryFactory(new BuiltinAudioEncoderFactoryFactory())
                .setAudioDecoderFactoryFactory(new BuiltinAudioDecoderFactoryFactory())
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();
    }

    public void setRemoteRenderer(SurfaceViewRenderer remoteRenderer) {
        Log.i(TAG, "Setting remote renderer");
        this.remoteRenderer = remoteRenderer;
    }

    private void logCodecStats() {
        if (peerConnection == null) return;
        try {
            peerConnection.getStats(stats -> {
                try {
                    String activeCodec = "unknown";
                    HashMap<String, RTCStats> statsMap = new HashMap<>();
                    for (RTCStats report : stats.getStatsMap().values()) {
                        statsMap.put(report.getId(), report);
                    }

                    for (RTCStats report : statsMap.values()) {
                        if (report.getType().equals("inbound-rtp") && "video".equals(report.getMembers().get("kind"))) {
                            String codecId = (String) report.getMembers().get("codecId");
                            if (codecId != null && statsMap.containsKey(codecId)) {
                                RTCStats codecStats = statsMap.get(codecId);
                                if (codecStats != null) {
                                    activeCodec = (String) codecStats.getMembers().get("mimeType");
                                }
                            }

                            String decoder = (String) report.getMembers().get("decoderImplementation");
                            Log.i("RTC-Codec", "Active Video Codec: " + activeCodec + " | Decoder: " + (decoder != null ? decoder : "Native"));
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.e("RTC-Codec", "Error parsing stats", e);
                }
            });
        } catch (Exception e) {
            Log.e("RTC-Codec", "Error getting stats", e);
        }
    }

    public void createPeerConnection(String iceServersJson) {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        try {
            if (iceServersJson != null && !iceServersJson.equals("null") && !iceServersJson.isEmpty()) {
                JSONObject config = new JSONObject(iceServersJson);
                if (config.has("iceServers") && !config.isNull("iceServers")) {
                    JSONArray servers = config.getJSONArray("iceServers");
                    for (int i = 0; i < servers.length(); i++) {
                        JSONObject server = servers.getJSONObject(i);
                        if (!server.has("urls")) continue;
                        Object urlsObj = server.get("urls");
                        if (urlsObj instanceof String) {
                            PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder((String) urlsObj);
                            if (server.has("username")) builder.setUsername(server.getString("username"));
                            if (server.has("credential")) builder.setPassword(server.getString("credential"));
                            iceServers.add(builder.createIceServer());
                        } else if (urlsObj instanceof JSONArray) {
                            JSONArray urlsArray = (JSONArray) urlsObj;
                            for (int j = 0; j < urlsArray.length(); j++) {
                                PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(urlsArray.getString(j));
                                if (server.has("username")) builder.setUsername(server.getString("username"));
                                if (server.has("credential")) builder.setPassword(server.getString("credential"));
                                iceServers.add(builder.createIceServer());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing PC config", e);
        }
        if (iceServers.isEmpty()) {
            iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        }
        createPeerConnection(iceServers);
    }

    public void createPeerConnection(List<PeerConnection.IceServer> iceServers) {
        synchronized (this) {
            PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
            rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
            rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
            rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
            rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
            rtcConfig.candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL;
            rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
            rtcConfig.audioJitterBufferFastAccelerate= true;
            if (peerConnection != null) {
                // setConfiguration must not discard the transceivers and data channels.
                if (!peerConnection.setConfiguration(rtcConfig)) reportSignalingError("configuration");
                return;
            }
            acceptedInputPackets = rejectedInputPackets = 0;
            lastDecodedFrameMs = -1;
            remoteAudioSeen = false;
            highOffered = false; offeredProfiles = answeredProfiles = "[]";
            audioOutputState = "aguardando";
            final long generation = ++connectionGeneration;
            peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
                @Override
                public void onIceCandidate(IceCandidate candidate) {
                    if (generation != connectionGeneration) return;
                    if (signalingListener != null) signalingListener.onLocalIceCandidate(candidate);
                }

                @Override
                public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                    if (generation != connectionGeneration) return;
                    if (signalingListener != null) signalingListener.onIceConnectionChange(state);
                    if (state == PeerConnection.IceConnectionState.CONNECTED && !codecLogged) {
                        codecLogged = true;
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> logCodecStats(), 5000);
                    }
                }

                @Override
                public void onTrack(RtpTransceiver transceiver) {
                    if (generation != connectionGeneration) return;
                    MediaStreamTrack track = transceiver.getReceiver().track();
                    if (track == null) return;
                    Log.i(TAG, "onTrack: kind=" + track.kind() + ", id=" + track.id());
                    if (signalingListener != null) signalingListener.onTrackReceived(track.kind(), track.id());

                    if (track instanceof VideoTrack) {
                        Log.i(TAG, "Configuring video track: " + track.id());
                        remoteVideoTrack = (VideoTrack) track;
                        if (pendingRenderTarget != null) {
                            remoteVideoTrack.addSink(pendingRenderTarget);
                        }
                    } else if (track instanceof org.webrtc.AudioTrack) {
                        remoteAudioSeen = true;
                        Log.i(TAG, "Audio track received from server");
                        // Store the audio sender so we can attach our local mic track to it
                        localAudioSender = transceiver.getSender();
                        
                        // We also need to ensure the transceiver is in the right direction
                        // xCloud usually sets this for us, but we ensure our end matches.
                        // Initial state: we want SEND_RECV so we can talk.
                    }
                }

                @Override
                public void onDataChannel(DataChannel dataChannel) {
                    if (generation != connectionGeneration) return;
                    Log.i(TAG, "Remote peer opened data channel: " + dataChannel.label());
                }

                @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
                @Override public void onIceConnectionReceivingChange(boolean receiving) {}
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                    if (generation != connectionGeneration) return;
                    if (state == PeerConnection.IceGatheringState.COMPLETE && signalingListener != null) {
                        signalingListener.onLocalIceCandidate(null);
                    }
                    if (signalingListener != null) signalingListener.onIceGatheringChange(state);
                }
                @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
                @Override public void onAddStream(MediaStream stream) {}
                @Override public void onRemoveStream(MediaStream stream) {}
                @Override public void onRenegotiationNeeded() {}
            });
        }
    }

    private boolean setVideoCodecPreferences(RtpTransceiver transceiver) {
        if (peerConnectionFactory == null) return false;
        try {
            RtpCapabilities capabilities = peerConnectionFactory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO);
            transceiver.setCodecPreferences(CodecSelectionPolicy.select(capabilities.codecs, videoProfileMode, hardwareHighAvailable));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Video preferences unavailable", e);
            return false;
        }
    }

    private void reportSignalingError(String operation) {
        Log.w(TAG, "Native signaling failed: " + operation);
        if (signalingListener != null) signalingListener.onSignalingError(operation);
    }

    private abstract class ReportingSdpObserver extends SdpObserverAdapter {
        private final String operation;
        private final PeerConnection connection = peerConnection;
        ReportingSdpObserver(String operation) { this.operation = operation; }
        @Override public void onCreateFailure(String error) {
            if (peerConnection == connection) reportSignalingError(operation);
        }
        @Override public void onSetFailure(String error) {
            if (peerConnection == connection) reportSignalingError(operation);
        }
    }

    public void handleRemoteOffer(SessionDescription offerSdp, AnswerReadyCallback callback) {
        final PeerConnection connection = peerConnection;
        if (connection == null) { reportSignalingError("remote-offer"); return; }
        connection.setRemoteDescription(new ReportingSdpObserver("remote-offer") {
            @Override public void onSetSuccess() {
                if (peerConnection != connection) return;
                if (signalingListener != null) signalingListener.onRemoteDescriptionSet();
                connection.createAnswer(new ReportingSdpObserver("create-answer") {
                    @Override public void onCreateSuccess(SessionDescription answerSdp) {
                        if (peerConnection != connection) return;
                        connection.setLocalDescription(new ReportingSdpObserver("local-answer") {
                            @Override public void onSetSuccess() {
                                if (peerConnection == connection) callback.onAnswerReady(answerSdp);
                            }
                        }, answerSdp);
                    }
                }, new org.webrtc.MediaConstraints());
            }
        }, offerSdp);
    }

    public void handleRemoteAnswer(SessionDescription answerSdp) {
        final PeerConnection connection = peerConnection;
        if (connection == null) { reportSignalingError("remote-answer"); return; }
        java.util.Set<String> answerProfiles = VideoQualityPolicy.h264Profiles(answerSdp.description);
        answeredProfiles = answerProfiles.toString();
        if (videoProfileMode == 3 && !CodecSelectionPolicy.acceptsHighAnswer(answerSdp.description)) {
            reportSignalingError("high-recusado"); return;
        }
        connection.setRemoteDescription(new ReportingSdpObserver("remote-answer") {
            @Override public void onSetSuccess() {
                if (peerConnection == connection && signalingListener != null) signalingListener.onRemoteDescriptionSet();
            }
        }, answerSdp);
    }

    public void createOffer(int bitrateKbps, boolean preferHigh, OfferReadyCallback callback) {
        final PeerConnection connection = peerConnection;
        if (connection == null) { reportSignalingError("create-offer"); return; }
        preferHighProfile = preferHigh;
        for (RtpTransceiver transceiver : connection.getTransceivers()) {
            if (!transceiver.isStopped() && transceiver.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                if (!setVideoCodecPreferences(transceiver)) { reportSignalingError("high-incompatível"); return; }
            }
        }
        connection.createOffer(new ReportingSdpObserver("create-offer") {
            @Override public void onCreateSuccess(SessionDescription description) {
                if (peerConnection != connection) return;
                final SessionDescription configuredOffer = new SessionDescription(description.type,
                        SdpPolicy.videoBandwidth(description.description, bitrateKbps));
                highOffered = VideoQualityPolicy.offersH264High(configuredOffer.description);
                offeredProfiles = VideoQualityPolicy.h264Profiles(configuredOffer.description).toString();
                connection.setLocalDescription(new ReportingSdpObserver("local-offer") {
                    @Override public void onSetSuccess() {
                        if (peerConnection == connection) callback.onOfferReady(configuredOffer);
                    }
                }, configuredOffer);
            }
        }, new org.webrtc.MediaConstraints());
    }

    public interface AnswerReadyCallback { void onAnswerReady(SessionDescription answerSdp); }
    public interface OfferReadyCallback { void onOfferReady(SessionDescription offerSdp); }

    public void addIceCandidate(IceCandidate candidate) {
        if (peerConnection != null) peerConnection.addIceCandidate(candidate);
    }

    public void addTransceiver(String trackOrKind, String direction) {
        if (peerConnection == null) return;
        MediaStreamTrack.MediaType mediaType = trackOrKind.equals("audio") ?
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO : MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO;
        RtpTransceiver.RtpTransceiverDirection transceiverDirection = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY;
        if ("sendonly".equals(direction)) transceiverDirection = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY;
        else if ("sendrecv".equals(direction)) transceiverDirection = RtpTransceiver.RtpTransceiverDirection.SEND_RECV;

        RtpTransceiver transceiver = peerConnection.addTransceiver(mediaType, new RtpTransceiver.RtpTransceiverInit(transceiverDirection));
        if (mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
            setVideoCodecPreferences(transceiver);
        }
        if (mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) {
            localAudioSender = transceiver.getSender();
            Log.i(TAG, "Stored audio sender from addTransceiver");
            if (micEnabled && localAudioTrack != null) {
                localAudioSender.setTrack(localAudioTrack, true);
                Log.i(TAG, "Delayed mic track attachment");
            }
        }
    }


    public void createDataChannel(String label) {
        synchronized (this) {
            if (peerConnection == null) createPeerConnection(new ArrayList<>());
            DataChannel.Init init = new DataChannel.Init();

            if ("unreliableinput".equalsIgnoreCase(label)) {
                init.protocol = "2.0";
                init.ordered = false;
                init.maxRetransmits = 0;
            } else if ("reliableinput".equalsIgnoreCase(label)) {
                init.protocol = "2.0";
                init.ordered = true;
            } else if ("input".equalsIgnoreCase(label)) {
                init.protocol = "1.0";
                init.ordered = true;
            } else if ("chat".equalsIgnoreCase(label)) {
                init.protocol = "chatV1";
                init.ordered = true;
            } else if ("control".equalsIgnoreCase(label)) {
                init.protocol = "controlV1";
                init.ordered = true;
            } else if ("message".equalsIgnoreCase(label)) {
                init.protocol = "messageV1";
                init.ordered = true;
            }

            DataChannel channel = peerConnection.createDataChannel(label, init);
            setupDataChannel(channel);
        }
    }
    private boolean channelOpen(String label) { return channelStates.get(label) == DataChannel.State.OPEN; }

    public void requestInputChannelSnapshot() {
        host.onInputChannelSnapshot(channelOpen("input"), channelOpen("unreliableinput"), channelOpen("control"));
    }

    private void setupDataChannel(DataChannel channel) {
        String label = channel.label();
        dataChannels.put(label, channel);
        channelStates.put(label, channel.state());

        channel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
                if (dataChannels.get(label) != channel) return;
                if (channel.bufferedAmount() > 1024) {
                    Log.w(TAG, "DataChannel network buffer backing up! Current: " + channel.bufferedAmount());
                }
            }

            @Override
            public void onStateChange() {
                if (dataChannels.get(label) != channel) return;
                channelStates.put(label, channel.state());
                //host.showToast("[debug]:Data channel " + label + " state change: " + channel.state().toString());
                Log.i(TAG, "Data channel " + label + " state change: " + channel.state());
                
                boolean isInputChannel = "unreliableinput".equalsIgnoreCase(label) || "input".equalsIgnoreCase(label);
                
                if (isInputChannel) {
                    boolean anyOpen = channelOpen("input") || channelOpen("unreliableinput");
                    if (anyOpen) host.setStreamingState(true);
                    host.setNativeGamepadEnabled(anyOpen);
                }
                host.onDataChannelStateChanged(label, channel.state().toString());
                requestInputChannelSnapshot();
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                if (dataChannels.get(label) != channel) return;
                //Log.i(TAG, "received data from (" + label + "): " + (buffer.binary?"binary":"data"));

                if ("unreliableinput".equalsIgnoreCase(label)) return;
                if (("input".equalsIgnoreCase(label) || "reliableinput".equalsIgnoreCase(label)) && buffer.binary) {
                    byte[] payload = new byte[buffer.data.remaining()];
                    buffer.data.get(payload);
                    host.onDataChannelMessageReceived(label, payload, null, true);
                    return;
                }

                byte[] data = new byte[buffer.data.remaining()];
                buffer.data.get(data);
                
                String dataStr;
                if (buffer.binary) {
                    dataStr = Base64.encodeToString(data, Base64.NO_WRAP);
                    //Log.i(TAG, "received binary frame (" + label + "): " + HexUtils.toHexString(data, data.length));
                } else {
                    dataStr = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                    //Log.i(TAG, "received text frame (" + label + "): " + dataStr);
                }
                host.onDataChannelMessageReceived(label, null,dataStr, false);
            }
        });
        // Refresh after observer installation too: OPEN can precede registration.
        if (dataChannels.get(label) == channel) {
            channelStates.put(label, channel.state());
            host.onDataChannelStateChanged(label, channel.state().toString());
            requestInputChannelSnapshot();
        }
    }

    public synchronized void sendDataChannelMessage(String label, byte[] data, boolean isBinary) {
        DataChannel channel = dataChannels.get(label);
        boolean input = "input".equals(label) || "unreliableinput".equals(label);
        boolean accepted = channel != null && channel.state() == DataChannel.State.OPEN
                && channel.send(new DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), isBinary));
        if (input) { if (accepted) acceptedInputPackets++; else rejectedInputPackets++; }
        if (!accepted) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastSendWarningMs >= 1000) {
                lastSendWarningMs = now;
                Log.w(TAG, "DataChannel not ready or rejected send: " + label);
            }
        }
    }

    public synchronized void sendDataChannelMessage(String label, String dataStr, boolean isBinary) {
        if (("unreliableinput".equalsIgnoreCase(label) || "input".equalsIgnoreCase(label))
                && host.isNativeGamepadEnabled()) {
            return;
        }
        DataChannel channel = dataChannels.get(label);
        if (channel != null && channel.state() == DataChannel.State.OPEN) {
            byte[] data;

            data = Base64.decode(dataStr, Base64.DEFAULT);
            //Log.i(TAG, "sending text frame (" + label + "): " + Arrays.toString(data));

            if (!channel.send(new DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), isBinary))) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastSendWarningMs >= 1000) {
                    lastSendWarningMs = now;
                    Log.w(TAG, "DataChannel rejected send: " + label);
                }
            }
        }
    }

    public void closeSession() {
        synchronized (this) {
            Log.i(TAG, "Closing game session...");
            codecLogged = false;
            connectionGeneration++;
            preferHighProfile = false;
            PeerConnection closingConnection = peerConnection;
            peerConnection = null;
            synchronized (statsLock) {
                statsEpoch++;
                statsRequestId++;
                statsPending = false;
                videoStatsSampler.reset();
                playbackHealth.reset();
                lastStatsSummary = "Recepção: aguardando amostra";
                lastRendererStats = "Renderizador: aguardando amostra";
                lastRendererStatsAtMs = -1;
                if (statsHandler != null) {
                    statsHandler.removeCallbacks(statsTick);
                    if (showStats && !isReleased) statsHandler.postDelayed(statsTick, 4000);
                }
            }

            List<DataChannel> closingChannels = new ArrayList<>(dataChannels.values());
            dataChannels.clear();
            channelStates.clear();
            lastDecodedFrameMs = -1;
            remoteAudioSeen = false;
            micEnabled = false;
            if (localAudioTrack != null) localAudioTrack.setEnabled(false);
            localAudioSender = null;
            for (DataChannel channel : closingChannels) channel.dispose();

            if (closingConnection != null) {
                closingConnection.close();
                closingConnection.dispose();
            }

            remoteVideoTrack = null;
            pendingRenderTarget = null;
        }
    }

    @Override
    public void onLogMessage(String message, Logging.Severity severity, String tag) {
        if (showStats && message != null && message.startsWith("stream-rendererDuration:")) {
            lastRendererStats = message.substring("stream-rendererDuration:".length()).trim();
            lastRendererStatsAtMs = android.os.SystemClock.elapsedRealtime();
        }
    }

    private String playbackStatus() {
        return PlaybackHealthStats.frameAge(android.os.SystemClock.elapsedRealtime(), lastDecodedFrameMs)
                + "\nSaída de áudio: " + audioOutputState + " | Trilha: " + (remoteAudioSeen ? "recebida" : "aguardando")
                + "\nInput aceito localmente: " + acceptedInputPackets + " | Recusado: " + rejectedInputPackets;
    }

    // Independent of renderer logging: keep sampling during a frozen image, only when enabled.
    // Never query on the service/UI main thread and never queue a second pending getStats.
    private synchronized void reportVideoStats() {
        PeerConnection connection = peerConnection;
        final long epoch, requestId;
        synchronized (statsLock) {
            if (!showStats || connection == null || isReleased) return;
            if (statsPending) {
                if (signalingListener != null) signalingListener.onPerformanceStatsReceived(
                        "Telemetria nativa pendente há " + ((android.os.SystemClock.elapsedRealtime() - statsRequestedAtMs) / 1000)
                                + " s (dados anteriores abaixo)\n" + lastStatsSummary + "\n" + playbackStatus());
                return;
            }
            statsPending = true;
            statsRequestedAtMs = android.os.SystemClock.elapsedRealtime();
            epoch = statsEpoch;
            requestId = ++statsRequestId;
        }
        try {
            connection.getStats(report -> {
                synchronized (statsLock) {
                    if (requestId == statsRequestId) statsPending = false;
                    if (peerConnection != connection || epoch != statsEpoch || isReleased) return;
                    if (!showStats) return;
                    String video = "Vídeo: aguardando RTP", audio = "Áudio: aguardando RTP";
                    java.util.Map<String, Object> selectedVideoCodec = java.util.Collections.emptyMap();
                    for (RTCStats stat : report.getStatsMap().values()) {
                        java.util.Map<String, Object> members = stat.getMembers();
                        if (!"inbound-rtp".equals(stat.getType())) continue;
                        if ("audio".equals(members.get("kind")) || "audio".equals(members.get("mediaType"))) {
                            audio = playbackHealth.audio(stat.getId(), stat.getTimestampUs(), members);
                            continue;
                        }
                        if (!("video".equals(members.get("kind")) || "video".equals(members.get("mediaType")))) continue;
                        if (!members.containsKey("framesDecoded") && !members.containsKey("frameWidth")) continue;
                        RTCStats codec = report.getStatsMap().get(members.get("codecId"));
                        selectedVideoCodec = codec == null ? java.util.Collections.emptyMap() : codec.getMembers();
                        String summary = videoStatsSampler.sample(stat.getId(), stat.getTimestampUs(), members,
                                codec == null ? java.util.Collections.emptyMap() : codec.getMembers());
                        RTCStats transport = report.getStatsMap().get(members.get("transportId"));
                        RTCStats pair = transport == null ? null : report.getStatsMap().get(transport.getMembers().get("selectedCandidatePairId"));
                        RTCStats candidate = pair == null ? null : report.getStatsMap().get(pair.getMembers().get("localCandidateId"));
                        video = summary + "\n" + VideoStatsSampler.networkSummary(
                                pair == null ? java.util.Collections.emptyMap() : pair.getMembers(),
                                candidate == null ? java.util.Collections.emptyMap() : candidate.getMembers());
                    }
                    video += "\nModo solicitado: " + new String[]{"Baseline", "Preferir High", "Automático", "Somente High"}[videoProfileMode];
                    video += "\n" + VideoStatsSampler.profileStatus(preferHighProfile, hardwareHighAvailable, highOffered, selectedVideoCodec).replace("Perfil solicitado nesta sessão: Baseline", videoProfileMode == 2 ? "Preferência automática nesta sessão" : "Perfil solicitado nesta sessão: Baseline")
                            + "\nPerfis na oferta: " + offeredProfiles + " | Resposta: " + answeredProfiles;
                    lastStatsSummary = PlaybackHealthStats.rendererAge(android.os.SystemClock.elapsedRealtime(), lastRendererStatsAtMs)
                            + "\n" + lastRendererStats + "\n" + video + "\n" + audio;
                    if (signalingListener != null) signalingListener.onPerformanceStatsReceived(lastStatsSummary + "\n" + playbackStatus());
                }
            });
        } catch (RuntimeException e) {
            synchronized (statsLock) { if (requestId == statsRequestId) statsPending = false; }
            Log.w(TAG, "Video stats unavailable", e);
        }
    }

    public void setMicrophoneEnabled(boolean enabled) {
        synchronized (this) {
            this.micEnabled = enabled;
            if (peerConnection == null) return;

            if (enabled) {
                if (localAudioTrack == null) {
                    Log.i(TAG, "Initializing native microphone track");
                    audioSource = peerConnectionFactory.createAudioSource(new org.webrtc.MediaConstraints());
                    localAudioTrack = peerConnectionFactory.createAudioTrack("ARDAMSa0", audioSource);
                }
                localAudioTrack.setEnabled(true);
                if (localAudioSender != null) {
                    localAudioSender.setTrack(localAudioTrack, true);
                    Log.i(TAG, "Mic track attached to sender");
                }
            } else {
                if (localAudioTrack != null) {
                    localAudioTrack.setEnabled(false);
                }
                // We keep the track attached but muted for seamless toggling
                Log.i(TAG, "Mic track muted");
            }
        }
    }

    public boolean isMicrophoneEnabled() {
        return micEnabled;
    }

    public void release() {
        synchronized (this) {
            if (isReleased) return;
            isReleased = true;
            synchronized (statsLock) {
                showStats = false;
                statsEpoch++;
                if (statsHandler != null) statsHandler.removeCallbacksAndMessages(null);
                if (statsThread != null) statsThread.quitSafely();
            }
            Log.i(TAG, "Releasing WebRtcReceiver resources...");

            signalingListener = null;

            WebRtcLoggingHelper.removeCustomLogger();
            closeSession();

            if (peerConnectionFactory != null) {
                peerConnectionFactory.dispose();
                peerConnectionFactory = null;
            }
            if (audioDeviceModule != null) {
                audioDeviceModule.release();
                audioDeviceModule = null;
            }
            if (localAudioTrack != null) {
                localAudioTrack.dispose();
                localAudioTrack = null;
            }
            if (audioSource != null) {
                audioSource.dispose();
                audioSource = null;
            }
            if (remoteRenderer != null) {
                remoteRenderer.release();
            }
            eglBase.release();
        }
    }
}
