package org.webrtc;

import android.media.MediaCodecInfo.CodecProfileLevel;
import org.junit.Test;
import java.util.ArrayList;
import java.util.HashMap;
import static org.junit.Assert.*;

public class CapabilityVideoDecoderFactoryTest {
    private static VideoCodecInfo codec(String profile) {
        HashMap<String, String> params = new HashMap<>();
        params.put("profile-level-id", profile);
        params.put("packetization-mode", "1");
        params.put("level-asymmetry-allowed", "1");
        return new VideoCodecInfo("H264", params, new ArrayList<>());
    }

    private static class FakeFactory implements VideoDecoderFactory {
        private final VideoCodecInfo[] codecs;
        final VideoDecoder decoder;
        FakeFactory(String name, VideoCodecInfo... codecs) {
            this.codecs = codecs;
            decoder = new VideoDecoder() {
                public VideoCodecStatus initDecode(Settings settings, Callback callback) { return VideoCodecStatus.OK; }
                public VideoCodecStatus decode(EncodedImage image, DecodeInfo info) { return VideoCodecStatus.OK; }
                public VideoCodecStatus release() { return VideoCodecStatus.OK; }
                public String getImplementationName() { return name; }
            };
        }
        public VideoCodecInfo[] getSupportedCodecs() { return codecs; }
        public VideoDecoder createDecoder(VideoCodecInfo codec) { return decoder; }
    }

    @Test public void advertisesVerifiedAmlogicHighMissingFromStockFactory() {
        VideoCodecInfo baseline = codec("42e01f"), high = codec("640c1f");
        FakeFactory stock = new FakeFactory("stock", baseline);
        FakeFactory verified = new FakeFactory("OMX.amlogic.avc.decoder.awesome2", high);
        CapabilityVideoDecoderFactory factory = new CapabilityVideoDecoderFactory(stock, verified);
        assertTrue(factory.hasHardwareHigh());
        assertArrayEquals(new VideoCodecInfo[]{baseline, high}, factory.getSupportedCodecs());
        assertSame(verified.decoder, factory.createDecoder(high));
        assertSame(stock.decoder, factory.createDecoder(baseline));
    }

    @Test public void doesNotAdvertiseOrDecodeUnverifiedHighEvenWhenStockWhitelistsIt() {
        VideoCodecInfo baseline = codec("42e01f"), high = codec("640c1f");
        FakeFactory stock = new FakeFactory("stock", baseline, high);
        CapabilityVideoDecoderFactory factory = new CapabilityVideoDecoderFactory(stock, null);
        assertFalse(factory.hasHardwareHigh());
        assertArrayEquals(new VideoCodecInfo[]{baseline}, factory.getSupportedCodecs());
        assertNull(factory.createDecoder(high));
        assertSame(stock.decoder, factory.createDecoder(baseline));
    }

    @Test public void highUsesVerifiedDecoderAcrossNegotiatedLevelVariants() {
        FakeFactory stock = new FakeFactory("wrong-first-avc", codec("42e01f"));
        FakeFactory verified = new FakeFactory("verified-avc", codec("640c1f"));
        CapabilityVideoDecoderFactory factory = new CapabilityVideoDecoderFactory(stock, verified);
        assertSame(verified.decoder, factory.createDecoder(codec("640C2A")));
        assertSame(verified.decoder, factory.createDecoder(codec("640029")));
    }

    @Test public void requiresAnExplicitEightBitHighProfileAndSufficientLevel() {
        assertTrue(CapabilityVideoDecoderFactory.supportsHighLevel31(CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCLevel42));
        assertTrue(CapabilityVideoDecoderFactory.supportsHighLevel31(CodecProfileLevel.AVCProfileConstrainedHigh, CodecProfileLevel.AVCLevel31));
        assertFalse(CapabilityVideoDecoderFactory.supportsHighLevel31(CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCLevel52));
        assertFalse(CapabilityVideoDecoderFactory.supportsHighLevel31(CodecProfileLevel.AVCProfileHigh10, CodecProfileLevel.AVCLevel52));
        assertFalse(CapabilityVideoDecoderFactory.supportsHighLevel31(CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCLevel3));
    }
}
