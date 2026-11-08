package com.example.offlineeditor.export;
import android.media.*;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;

/** One encoded track per intermediate MP4; separate tracks are combined after encoding. */
final class EncodedTrack implements AutoCloseable {
    final MediaCodec codec;
    Surface surface;
    private MediaMuxer muxer;
    private boolean started,ended,codecStarted;
    private int track=-1;
    private final ExportControl control;
    private long lastPts=-1;
    EncodedTrack(File file,MediaFormat format,boolean video,ExportControl control) throws Exception {
        this.control=control;
        String encoder=new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
        if(encoder==null) throw new IOException("本机没有支持所选参数的编码器，请降低清晰度或帧率");
        codec=MediaCodec.createByCodecName(encoder);
        try {
            muxer=new MediaMuxer(file.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            if(video) surface=codec.createInputSurface();
            codec.start(); codecStarted=true;
        } catch(Exception e) { close(); throw e; }
    }
    void drain(boolean finish) throws Exception {
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo(); long activity=System.nanoTime();
        do {
            control.check(); int index=codec.dequeueOutputBuffer(info,finish?10000:0);
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if(started) throw new IOException("编码器输出格式发生变化");
                track=muxer.addTrack(codec.getOutputFormat()); muxer.start(); started=true; activity=System.nanoTime();
            } else if(index>=0) {
                try {
                    if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0 && info.size>0) {
                        if(!started) throw new IOException("编码器未提供输出格式");
                        if(info.presentationTimeUs<=lastPts) info.presentationTimeUs=lastPts+1;
                        ByteBuffer data=codec.getOutputBuffer(index); data.position(info.offset); data.limit(info.offset+info.size);
                        muxer.writeSampleData(track,data,info); lastPts=info.presentationTimeUs;
                    }
                    ended=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                } finally { codec.releaseOutputBuffer(index,false); }
                activity=System.nanoTime();
            } else if(!finish) return;
            if(System.nanoTime()-activity>30000000000L) throw new IOException("编码器等待超时");
        } while(!ended);
    }
    void finish() throws Exception {
        drain(true);
        if(!started || lastPts<0) throw new IOException("编码器没有生成有效数据");
        muxer.stop(); started=false;
    }
    public void close() {
        if(codecStarted) try { codec.stop(); } catch(Exception ignored) {}
        try { codec.release(); } catch(Exception ignored) {}
        if(surface!=null) surface.release();
        if(muxer!=null) { if(started) try { muxer.stop(); } catch(Exception ignored) {} try { muxer.release(); } catch(Exception ignored) {} }
    }
}