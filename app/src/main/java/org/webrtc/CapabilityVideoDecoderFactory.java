package org.webrtc;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Exposes H264 High from Android's reported capabilities, not a vendor-name whitelist.
 * Lives alongside the pinned WebRTC classes to use its existing MediaCodec adapter.
 */
public final class CapabilityVideoDecoderFactory implements VideoDecoderFactory {
    private final VideoDecoderFactory fallback;
    private final VideoDecoderFactory verifiedHigh;

    public CapabilityVideoDecoderFactory(EglBase.Context context) {
        this(new DefaultVideoDecoderFactory(context), findHardwareHigh(context));
    }

    // Package-private seam for tests without a codec device or JNI.
    CapabilityVideoDecoderFactory(VideoDecoderFactory fallback, VideoDecoderFactory verifiedHigh) {
        this.fallback = fallback;
        this.verifiedHigh = verifiedHigh;
    }

    public boolean hasHardwareHigh() { return verifiedHigh != null; }

    @Override public VideoCodecInfo[] getSupportedCodecs() {
        List<VideoCodecInfo> codecs = new ArrayList<>();
        for (VideoCodecInfo codec : fallback.getSupportedCodecs()) {
            // Do not carry over an unverified, vendor-whitelisted High capability.
            if (!isHigh(codec)) codecs.add(codec);
        }
        if (verifiedHigh != null) codecs.addAll(Arrays.asList(verifiedHigh.getSupportedCodecs()));
        return codecs.toArray(new VideoCodecInfo[0]);
    }

    @Override public VideoDecoder createDecoder(VideoCodecInfo codec) {
        if (isHigh(codec)) return verifiedHigh == null ? null : verifiedHigh.createDecoder(codec);
        return fallback.createDecoder(codec);
    }

    static boolean isHigh(VideoCodecInfo codec) {
        String profile = codec.params.get("profile-level-id");
        return "H264".equalsIgnoreCase(codec.name) && profile != null
                && profile.toLowerCase(Locale.ROOT).matches("64[0-9a-f]{4}");
    }

    static boolean supportsHighLevel31(int profile, int level) {
        return (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                || profile == MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh)
                && level >= MediaCodecInfo.CodecProfileLevel.AVCLevel31;
    }

    private static VideoDecoderFactory findHardwareHigh(EglBase.Context context) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                try {
                    if (info.isEncoder() || !info.isHardwareAccelerated() || info.isSoftwareOnly()) continue;
                    if (!MediaCodecUtils.codecSupportsType(info, VideoCodecMimeType.H264)) continue;
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType("video/avc");
                    if (caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                            || caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) continue;
                    Integer color = MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, caps);
                    if (color == null || caps.profileLevels == null) continue;
                    for (MediaCodecInfo.CodecProfileLevel profile : caps.profileLevels) {
                        if (!supportsHighLevel31(profile.profile, profile.level)) continue;
                        Log.i("RTC-Codec", "Android declares hardware H264 High: " + info.getName());
                        return new PinnedHighDecoderFactory(info.getName(), color, context);
                    }
                } catch (RuntimeException e) {
                    Log.w("RTC-Codec", "Cannot inspect a decoder; skipping it");
                }
            }
        } catch (RuntimeException e) {
            Log.w("RTC-Codec", "Hardware High capabilities unavailable");
        }
        return null;
    }

    private static final class PinnedHighDecoderFactory implements VideoDecoderFactory {
        private final String name;
        private final int color;
        private final EglBase.Context context;
        PinnedHighDecoderFactory(String name, int color, EglBase.Context context) {
            this.name = name;
            this.color = color;
            this.context = context;
        }
        @Override public VideoCodecInfo[] getSupportedCodecs() {
            // Stock WebRTC Constrained High parameters (640c1f, level asymmetry allowed).
            // Only advertised after validating at least this level in Android's capabilities.
            return new VideoCodecInfo[]{new VideoCodecInfo("H264",
                    MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H264, true), new ArrayList<>())};
        }
        @Override public VideoDecoder createDecoder(VideoCodecInfo codec) {
            if (!isHigh(codec)) return null;
            // Pin to the same decoder whose capabilities we inspected, not the first AVC codec.
            return new AndroidVideoDecoder(new MediaCodecWrapperFactoryImpl(), name,
                    VideoCodecMimeType.H264, color, context);
        }
    }
}
