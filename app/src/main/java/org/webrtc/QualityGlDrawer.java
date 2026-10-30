package org.webrtc;

import android.opengl.GLES20;
import android.util.Log;

/** Optional one-pass unsharp filter; no CPU pixels, textures or framebuffer readback. */
public final class QualityGlDrawer implements RendererCommon.GlDrawer {
    private RendererCommon.GlDrawer drawer;
    private volatile boolean filtered;
    public boolean isFiltering() { return filtered; }
    public QualityGlDrawer(float amount) {
        final float strength = Math.max(0f, Math.min(.30f, amount));
        filtered = strength > 0;
        drawer = filtered ? new GlGenericDrawer(
                "uniform vec2 pixelStep;\nuniform float strength;\nvoid main() {\n"
                + "vec4 c=sample(tc);\nvec3 n=(sample(tc+vec2(pixelStep.x,0.0)).rgb"
                + "+sample(tc-vec2(pixelStep.x,0.0)).rgb+sample(tc+vec2(0.0,pixelStep.y)).rgb"
                + "+sample(tc-vec2(0.0,pixelStep.y)).rgb)*0.25;\n"
                + "gl_FragColor=vec4(clamp(c.rgb+strength*(c.rgb-n),0.0,1.0),c.a);\n}",
                new GlGenericDrawer.ShaderCallbacks() {
                    public void onNewShader(GlShader shader) {}
                    public void onPrepareShader(GlShader shader, float[] matrix, int w, int h, int vw, int vh) {
                        GLES20.glUniform2f(shader.getUniformLocation("pixelStep"), 1f / Math.max(1,w), 1f / Math.max(1,h));
                        GLES20.glUniform1f(shader.getUniformLocation("strength"), strength);
                    }
                }) : new GlRectDrawer();
    }
    private void fallback(RuntimeException error) {
        if (!filtered) throw error;
        Log.w("CloudX-filter", "Filter unavailable; using standard drawing", error);
        try { drawer.release(); } catch (RuntimeException ignored) {}
        filtered = false;
        drawer = new GlRectDrawer();
    }
    public void drawOes(int t,float[] m,int w,int h,int x,int y,int vw,int vh) {
        try { drawer.drawOes(t,m,w,h,x,y,vw,vh); }
        catch (RuntimeException e) { fallback(e); drawer.drawOes(t,m,w,h,x,y,vw,vh); }
    }
    public void drawRgb(int t,float[] m,int w,int h,int x,int y,int vw,int vh) {
        try { drawer.drawRgb(t,m,w,h,x,y,vw,vh); }
        catch (RuntimeException e) { fallback(e); drawer.drawRgb(t,m,w,h,x,y,vw,vh); }
    }
    public void drawYuv(int[] t,float[] m,int w,int h,int x,int y,int vw,int vh) {
        try { drawer.drawYuv(t,m,w,h,x,y,vw,vh); }
        catch (RuntimeException e) { fallback(e); drawer.drawYuv(t,m,w,h,x,y,vw,vh); }
    }
    public void release() { drawer.release(); }
}
