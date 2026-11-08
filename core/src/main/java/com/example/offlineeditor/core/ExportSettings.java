package com.example.offlineeditor.core;

/** Immutable request; first visual determines aspect ratio, resolution is the short edge. */
public final class ExportSettings {
    public enum Format { MP4, MP3 }
    public final Format format;
    public final int resolution, fps, audioKbps;
    public ExportSettings(Format format,int resolution,int fps,int audioKbps) {
        if(format==null || (resolution!=480 && resolution!=720 && resolution!=1080)
            || (fps!=24 && fps!=25 && fps!=30 && fps!=60)
            || (audioKbps!=128 && audioKbps!=192 && audioKbps!=320))
            throw new IllegalArgumentException("Invalid export settings");
        this.format=format; this.resolution=resolution; this.fps=fps; this.audioKbps=audioKbps;
    }
    public String extension() { return format==Format.MP4?"mp4":"mp3"; }
    public String mime() { return format==Format.MP4?"video/mp4":"audio/mpeg"; }
    public int[] dimensions(int sourceWidth,int sourceHeight) {
        if(sourceWidth<=0 || sourceHeight<=0) throw new IllegalArgumentException("Invalid dimensions");
        double factor=(double)resolution/Math.min(sourceWidth,sourceHeight);
        int w=(int)(Math.round(sourceWidth*factor/2)*2),h=(int)(Math.round(sourceHeight*factor/2)*2);
        if(w>4096 || h>4096 || w<2 || h<2) throw new IllegalArgumentException("画幅过宽或过高，请降低清晰度");
        return new int[]{w,h};
    }
    public long frameCount(long durationUs) { return (durationUs*fps+999999)/1000000; }
    public long frameTimeUs(long index) { return index*1000000/fps; }
    public int videoBitrate(int w,int h) { return (int)Math.min(24000000L,Math.max(1500000L,(long)w*h*fps/6)); }
}