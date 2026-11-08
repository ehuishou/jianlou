package com.example.offlineeditor.export;
import android.media.*;
import com.example.offlineeditor.core.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Builds a frozen project into a private staging file. Caller publishes only on success. */
public final class Exporter {
    private Exporter() {}
    public static File run(Project project,ExportSettings settings,File job,ExportControl control) throws Exception {
        if(project.durationUs()<=0) throw new IOException("项目为空，请先导入素材");
        if(settings.format==ExportSettings.Format.MP4 && project.visuals.isEmpty()) throw new IOException("纯音频项目请选择 MP3");
        if(!job.isDirectory() && !job.mkdirs()) throw new IOException("无法创建导出缓存");
        control.space(job); Map<String,PcmSource> sources=new HashMap<>(); AudioMix mix=new AudioMix();
        File result=new File(job,"result."+settings.extension());
        try {
            List<Clip> clips=new ArrayList<>(project.visuals); for(Project.Audio a:project.audio) clips.add(a.clip);
            int index=0;
            for(Clip c:clips) {
                control.progress("准备音轨 · "+c.name,Math.min(20,index*20/Math.max(1,clips.size()))); index++;
                if(c.kind!=Clip.Kind.IMAGE && !sources.containsKey(c.path)) {
                    PcmSource s=PcmSource.decode(c.path,new File(job,"audio-"+index+".pcm"),control); sources.put(c.path,s);
                    if(c.kind==Clip.Kind.AUDIO && s==null) throw new IOException("音频素材没有音轨："+c.name);
                    long size=0; File[] files=job.listFiles(); if(files!=null) for(File f:files) size+=f.length();
                    if(size>1024L*1024*1024) throw new IOException("音频缓存超过 1 GB，请缩短素材或减少音轨");
                }
            }
            long start=0; for(Clip c:project.visuals) { mix.add(c,start,sources.get(c.path)); start+=c.durationUs(); }
            for(Project.Audio a:project.audio) mix.add(a.clip,a.startUs,sources.get(a.clip.path));
            if(settings.format==ExportSettings.Format.MP3) {
                if(mix.isEmpty()) throw new IOException("项目没有音轨；图片或无声视频无法导出 MP3");
                mp3(mix,project.durationUs(),settings.audioKbps,result,control);
            } else {
                File audio=null;
                if(!mix.isEmpty()) { audio=new File(job,"audio.m4a"); aac(mix,project.durationUs(),audio,control); }
                File video=new File(job,"video.mp4"); VideoFrames.encode(project,settings,video,control);
                mux(video,audio,result,control);
            }
            control.check(); validate(result,settings); control.progress("编码完成，准备保存",96); return result;
        } finally { for(PcmSource s:sources.values()) if(s!=null) try { s.close(); } catch(IOException ignored) {} }
    }
    private static void mp3(AudioMix mix,long durationUs,int kbps,File out,ExportControl control) throws Exception {
        long total=AudioMix.sampleCount(durationUs); byte[] pcm=new byte[4096*4],encoded=new byte[16384];
        try(Mp3Encoder encoder=new Mp3Encoder(kbps); OutputStream stream=new BufferedOutputStream(new FileOutputStream(out))) {
            for(long frame=0;frame<total;frame+=4096) {
                control.space(out.getParentFile()); int n=mix.read(frame,4096,total,pcm); int bytes=encoder.encode(pcm,n,encoded); stream.write(encoded,0,bytes);
                control.progress("编码 MP3 · "+kbps+" kbps",20+(int)(frame*75/total));
            }
            int bytes=encoder.finish(encoded); stream.write(encoded,0,bytes);
        }
    }
    private static void aac(AudioMix mix,long durationUs,File out,ExportControl control) throws Exception {
        MediaFormat format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,AudioMix.RATE,2);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC); format.setInteger(MediaFormat.KEY_BIT_RATE,192000);
        try(EncodedTrack track=new EncodedTrack(out,format,false,control)) {
            long total=AudioMix.sampleCount(durationUs),frame=0,activity=System.nanoTime(); byte[] pcm=new byte[4096*4]; boolean end=false;
            while(!end) {
                control.space(out.getParentFile()); track.drain(false); int index=track.codec.dequeueInputBuffer(10000);
                if(index>=0) {
                    ByteBuffer input=track.codec.getInputBuffer(index); input.clear();
                    if(frame==total) { track.codec.queueInputBuffer(index,0,0,frame*1000000/AudioMix.RATE,MediaCodec.BUFFER_FLAG_END_OF_STREAM); end=true; }
                    else {
                        int capacity=Math.min(4096,input.remaining()/4); if(capacity==0) throw new IOException("AAC 输入缓冲区太小");
                        int n=mix.read(frame,capacity,total,pcm); input.put(pcm,0,n*4); track.codec.queueInputBuffer(index,0,n*4,frame*1000000/AudioMix.RATE,0); frame+=n;
                    }
                    activity=System.nanoTime(); control.progress("编码 MP4 音轨",20+(int)(frame*19/total));
                }
                if(System.nanoTime()-activity>30000000000L) throw new IOException("AAC 输入超时");
            }
            track.finish();
        }
    }
    private static void mux(File video,File audio,File result,ExportControl control) throws Exception {
        List<MediaExtractor> extractors=new ArrayList<>(); MediaMuxer muxer=null; boolean started=false;
        try {
            muxer=new MediaMuxer(result.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); List<Integer> tracks=new ArrayList<>();
            for(File f:new File[]{video,audio}) if(f!=null) {
                MediaExtractor e=new MediaExtractor(); extractors.add(e); e.setDataSource(f.getPath());
                if(e.getTrackCount()!=1) throw new IOException("中间轨道数量异常");
                tracks.add(muxer.addTrack(e.getTrackFormat(0))); e.selectTrack(0);
            }
            muxer.start(); started=true; ByteBuffer buffer=ByteBuffer.allocateDirect(8*1024*1024); MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            // Interleave by presentation timestamp so large files remain streamable.
            while(true) {
                control.space(result.getParentFile()); int next=-1; long time=Long.MAX_VALUE;
                for(int i=0;i<extractors.size();i++) { long t=extractors.get(i).getSampleTime(); if(t>=0 && t<time) { next=i; time=t; } }
                if(next<0) break;
                MediaExtractor e=extractors.get(next); buffer.clear(); int n=e.readSampleData(buffer,0);
                if(n<0) throw new IOException("中间文件读取失败");
                info.set(0,n,time,(e.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);
                buffer.position(0); buffer.limit(n); muxer.writeSampleData(tracks.get(next),buffer,info); e.advance(); control.progress("封装 MP4",93);
            }
            muxer.stop(); started=false;
        } finally {
            for(MediaExtractor e:extractors) e.release();
            if(muxer!=null) { if(started) try { muxer.stop(); } catch(Exception ignored) {} muxer.release(); }
        }
    }
    private static void validate(File file,ExportSettings settings) throws Exception {
        if(file.length()<128) throw new IOException("导出文件为空");
        MediaExtractor e=new MediaExtractor();
        try {
            e.setDataSource(file.getPath()); boolean found=false;
            for(int i=0;i<e.getTrackCount();i++) {
                String mime=e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if(settings.format==ExportSettings.Format.MP3?"audio/mpeg".equals(mime):"video/avc".equals(mime)) { e.selectTrack(i); found=e.getSampleTime()>=0; break; }
            }
            if(!found) throw new IOException("输出文件未通过音视频格式检查");
        } finally { e.release(); }
    }
}