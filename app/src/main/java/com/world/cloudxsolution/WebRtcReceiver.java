package com.world.cloudxsolution;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.BuiltinAudioDecoderFactoryFactory;
import org.webrtc.BuiltinAudioEncoderFactoryFactory;
import org.webrtc.DataChannel;
import org.webrtc.CapabilityVideoDecoderFactory;
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
    private AudioDeviceModule audioDeviceModule;
    private final java.util.concurrent.ConcurrentHashMap<String, DataChannel> dataChannels = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, DataChannel.State> channelStates = new java.util.concurrent.ConcurrentHashMap<>();
    private long lastSendWarningMs;
    private volatile long acceptedInputPackets, rejectedInputPackets;

    private SurfaceViewRenderer remoteRenderer;
    private SignalingListener signalingListener;
    private boolean isReleased = false;

    private boolean codecLogged = false;
    private volatile boolean preferHighProfile;
    private volatile boolean hardwareHighAvailable;
    private volatile boolean highOffered;
    private volatile String offeredProfiles = "n/d", answeredProfiles = "n/d";
    private final VideoStatsSampler videoStatsSampler = new VideoStatsSampler();
    private final android.os.Handler statsHandler = new android.os.Handler(android.os.Looper.getMainLooper());
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
            videoStatsSampler.reset();
        }
    }

    public interface SignalingListener {
        void onLocalIceCandidate(IceCandidate candidate);
        void onIceConnectionChange(PeerConnection.IceConnectionState state);
        void onIceGatheringChange(PeerConnection.IceGatheringState state);
        void onTrackReceived(String kind, String id);
        void onPerformanceStatsReceived(String stats);
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


    public void initPeerConnectionFactory() {
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
                .setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .createAudioDeviceModule();

        DefaultVideoEncoderFactory encoderFactory =
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        CapabilityVideoDecoderFactory decoderFactory =
                new CapabilityVideoDecoderFactory(eglBase.getEglBaseContext());
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
        createPeerConnection(parseIceServers(iceServersJson));
    }

    public synchronized void updatePeerConfiguration(String iceServersJson) {
        if (peerConnection == null) return;
        if (!peerConnection.setConfiguration(rtcConfiguration(parseIceServers(iceServersJson)))) {
            Log.w(TAG, "Peer configuration update rejected; keeping the current session");
        }
        requestInputChannelSnapshot();
    }

    private List<PeerConnection.IceServer> parseIceServers(String iceServersJson) {
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
        return iceServers;
    }

    private PeerConnection.RTCConfiguration rtcConfiguration(List<PeerConnection.IceServer> iceServers) {
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        rtcConfig.candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.LOW_COST;
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
        rtcConfig.audioJitterBufferFastAccelerate= true;
        return rtcConfig;
    }

    public void createPeerConnection(List<PeerConnection.IceServer> iceServers) {
        synchronized (this) {
            if (peerConnection != null) closeSession();
            acceptedInputPackets = rejectedInputPackets = 0;
            PeerConnection.RTCConfiguration rtcConfig = rtcConfiguration(iceServers);
            peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
                @Override
                public void onIceCandidate(IceCandidate candidate) {
                    if (signalingListener != null) signalingListener.onLocalIceCandidate(candidate);
                }

                @Override
                public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                    if (signalingListener != null) signalingListener.onIceConnectionChange(state);
                    if (state == PeerConnection.IceConnectionState.CONNECTED && !codecLogged) {
                        codecLogged = true;
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> logCodecStats(), 5000);
                    }
                }

                @Override
                public void onTrack(RtpTransceiver transceiver) {
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
                    Log.i(TAG, "Remote peer opened data channel: " + dataChannel.label());
                }

                @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
                @Override public void onIceConnectionReceivingChange(boolean receiving) {}
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
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

    private void setVideoCodecPreferences(RtpTransceiver transceiver) {
        if (peerConnectionFactory == null) return;
        RtpCapabilities capabilities = peerConnectionFactory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO);
        List<RtpCapabilities.CodecCapability> codecs = new ArrayList<>(capabilities.codecs);

        List<RtpCapabilities.CodecCapability> h264Codecs = new ArrayList<>();
        List<RtpCapabilities.CodecCapability> otherCodecs = new ArrayList<>();

        for (RtpCapabilities.CodecCapability codec : codecs) {
            if (codec.name.toUpperCase(Locale.ROOT).contains("H264")) {
                h264Codecs.add(codec);
            } else {
                otherCodecs.add(codec);
            }
        }

        h264Codecs.sort((c1, c2) -> {
            String p1 = c1.parameters.get("profile-level-id");
            String p2 = c2.parameters.get("profile-level-id");
            return Integer.compare(profileRank(p2), profileRank(p1));
        });

        List<RtpCapabilities.CodecCapability> finalSelection = new ArrayList<>(h264Codecs);
        finalSelection.addAll(otherCodecs);

        transceiver.setCodecPreferences(finalSelection);
        Log.i(TAG, "Strictly applied video codec preferences (H264 First): " + finalSelection.stream()
                .map(c -> c.name + (c.parameters.get("profile-level-id") != null ? "[" + c.parameters.get("profile-level-id") + "]" : ""))
                .collect(Collectors.joining(", ")));
    }

    private int profileRank(String profile) {
        return VideoQualityPolicy.h264Rank(profile, preferHighProfile, hardwareHighAvailable);
    }

    public void handleRemoteOffer(SessionDescription offerSdp, AnswerReadyCallback callback) {
        final PeerConnection connection = peerConnection;
        if (connection == null) return;
        connection.setRemoteDescription(new SdpObserverAdapter() {
            @Override
            public void onSetSuccess() {
                if (peerConnection != connection) return;
                connection.createAnswer(new SdpObserverAdapter() {
                    @Override
                    public void onCreateSuccess(SessionDescription answerSdp) {
                        if (peerConnection != connection) return;
                        connection.setLocalDescription(new SdpObserverAdapter() {
                            @Override
                            public void onSetSuccess() {
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
        if (connection == null) return;
        connection.setRemoteDescription(new SdpObserverAdapter() {
            @Override public void onSetSuccess() {
                if (peerConnection != connection) return;
                answeredProfiles = VideoQualityPolicy.h264Profiles(answerSdp.description).toString();
                Log.i(TAG, "Remote answer set successfully");
            }
        }, answerSdp);
    }

    public void createOffer(int bitrateKbps, boolean preferHigh, OfferReadyCallback callback) {
        final PeerConnection connection = peerConnection;
        if (connection == null) return;
        preferHighProfile = preferHigh;
        for (RtpTransceiver transceiver : connection.getTransceivers()) {
            if (!transceiver.isStopped() && transceiver.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                setVideoCodecPreferences(transceiver);
            }
        }
        Log.i(TAG, "Video request: " + VideoQualityPolicy.maxKbps(bitrateKbps)
                + " kbps, prefer hardware High=" + preferHigh);
        connection.createOffer(new SdpObserverAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                if (peerConnection != connection) return;
                final SessionDescription configuredOffer = new SessionDescription(sessionDescription.type,
                        SdpPolicy.videoBandwidth(sessionDescription.description, bitrateKbps));
                highOffered = VideoQualityPolicy.offersH264High(configuredOffer.description);
                offeredProfiles = VideoQualityPolicy.h264Profiles(configuredOffer.description).toString();
                connection.setLocalDescription(new SdpObserverAdapter() {
                    @Override public void onSetSuccess() { if (peerConnection == connection) callback.onOfferReady(configuredOffer); }
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
    private boolean channelOpen(String label) {
        return channelStates.get(label) == DataChannel.State.OPEN;
    }

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
                    boolean anyInputOpen = channelOpen("input") || channelOpen("unreliableinput");
                    if (anyInputOpen) host.setStreamingState(true);
                    host.setNativeGamepadEnabled(anyInputOpen);
                }
                host.onDataChannelStateChanged(label, channel.state().toString());
                requestInputChannelSnapshot();
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                //Log.i(TAG, "received data from (" + label + "): " + (buffer.binary?"binary":"data"));

                if (dataChannels.get(label) != channel) return;
                if ("unreliableinput".equalsIgnoreCase(label)) return;
                if ("input".equalsIgnoreCase(label) || "reliableinput".equalsIgnoreCase(label)) {
                    byte[] payload = new byte[buffer.data.remaining()];
                    buffer.data.get(payload);
                    host.onDataChannelMessageReceived(label, payload, null, buffer.binary);
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
        host.onDataChannelStateChanged(label, channel.state().toString());
        requestInputChannelSnapshot();
    }

    public synchronized void sendDataChannelMessage(String label, byte[] data, boolean isBinary) {
        DataChannel channel = dataChannels.get(label);
        boolean input = "input".equals(label) || "unreliableinput".equals(label);
        boolean accepted = channel != null && channel.state() == DataChannel.State.OPEN
                && channel.send(new DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), isBinary));
        if (input) {
            if (accepted) acceptedInputPackets++;
            else rejectedInputPackets++;
        }
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
            preferHighProfile = false;
            highOffered = false;
            offeredProfiles = answeredProfiles = "n/d";
            PeerConnection closingConnection = peerConnection;
            peerConnection = null;
            synchronized (statsLock) {
                statsHandler.removeCallbacksAndMessages(null);
                statsPending = false;
                videoStatsSampler.reset();
            }

            List<DataChannel> closingChannels = new ArrayList<>(dataChannels.values());
            dataChannels.clear();
            channelStates.clear();
            for (DataChannel channel : closingChannels) channel.dispose();
            host.setNativeGamepadEnabled(false);
            host.setStreamingState(false);

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
            String stats = message.substring("stream-rendererDuration:".length()).trim();
            if (signalingListener != null) signalingListener.onPerformanceStatsReceived(stats);
            synchronized (statsLock) {
                if (!statsPending) {
                    statsPending = true;
                    statsHandler.post(() -> reportVideoStats(stats));
                }
            }
        }
    }

    // Sample only when the existing renderer telemetry arrives and the overlay is visible.
    // No timer, frame processing, readback or extra rendering pass is added.
    private synchronized void reportVideoStats(String rendererStats) {
        PeerConnection connection = peerConnection;
        if (!showStats || connection == null) {
            synchronized (statsLock) { statsPending = false; }
            return;
        }
        try {
            connection.getStats(report -> {
                // Do not take the lifecycle lock: dispose may wait for this native callback.
                synchronized (statsLock) {
                    if (peerConnection != connection) return;
                    statsPending = false;
                    if (!showStats) return;
                    for (RTCStats stat : report.getStatsMap().values()) {
                        java.util.Map<String, Object> members = stat.getMembers();
                        if (!"inbound-rtp".equals(stat.getType()) ||
                                !("video".equals(members.get("kind")) || "video".equals(members.get("mediaType")))) continue;
                        if (!members.containsKey("framesDecoded") && !members.containsKey("frameWidth")) continue;
                        RTCStats codec = report.getStatsMap().get(members.get("codecId"));
                        String summary = videoStatsSampler.sample(stat.getId(), stat.getTimestampUs(), members,
                                codec == null ? java.util.Collections.emptyMap() : codec.getMembers());
                        summary += "\n" + VideoStatsSampler.profileStatus(preferHighProfile, hardwareHighAvailable, highOffered,
                                codec == null ? java.util.Collections.emptyMap() : codec.getMembers());
                        summary += "\nControle enviado: " + acceptedInputPackets + " | Recusado localmente: " + rejectedInputPackets;
                        summary += "\nPerfis na oferta: " + offeredProfiles + "\nPerfis na resposta: " + answeredProfiles;
                        if (signalingListener != null) {
                            signalingListener.onPerformanceStatsReceived(rendererStats + "\n" + summary);
                        }
                        break;
                    }
                }
            });
        } catch (RuntimeException e) {
            synchronized (statsLock) { statsPending = false; }
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
