package com.example.offlineeditor.core;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Shared AAC/MP3 sample clock. Speed changes pitch; overlaps are summed and limited. */
public final class AudioMix {
    public static final int RATE=44100;
    public interface Source { double sample(long sourceUs,int channel) throws IOException; }
    private static final class Layer {
        final Clip clip; final long startUs,endUs; final Source source;
        Layer(Clip clip,long startUs,Source source) {
            this.clip=clip; this.startUs=startUs; this.endUs=startUs+clip.durationUs(); this.source=source;
        }
    }
    private final List<Layer> layers=new ArrayList<>();
    public void add(Clip clip,long startUs,Source source) { if(source!=null) layers.add(new Layer(clip,startUs,source)); }
    public boolean isEmpty() { return layers.isEmpty(); }
    public static long sampleCount(long durationUs) { return (durationUs*RATE+999999)/1000000; }
    public int read(long firstFrame,int frames,long totalFrames,byte[] pcm) throws IOException {
        int count=(int)Math.min(frames,Math.max(0,totalFrames-firstFrame));
        if(pcm.length<count*4) throw new IllegalArgumentException("PCM buffer too small");
        for(int i=0;i<count;i++) {
            long time=(firstFrame+i)*1000000/RATE; double left=0,right=0;
            for(Layer layer:layers) if(time>=layer.startUs && time<layer.endUs) {
                long sourceUs=layer.clip.sourceUs(time-layer.startUs);
                left+=layer.source.sample(sourceUs,0); right+=layer.source.sample(sourceUs,1);
            }
            put(pcm,i*4,left); put(pcm,i*4+2,right);
        }
        return count;
    }
    private static void put(byte[] bytes,int at,double value) {
        int v=(int)Math.round(Math.max(-1,Math.min(1,value))*32767);
        bytes[at]=(byte)v; bytes[at+1]=(byte)(v>>8);
    }
}