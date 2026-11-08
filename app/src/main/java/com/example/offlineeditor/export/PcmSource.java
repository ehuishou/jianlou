package com.example.offlineeditor.export;
import android.media.*;
import com.example.offlineeditor.core.AudioMix;
import java.io.*;
import java.nio.ByteBuffer;

/** Disk-backed native-rate PCM; timestamp gaps are silence, no whole-song RAM allocation. */
final class PcmSource implements AudioMix.Source,AutoCloseable {
    private final RandomAccessFile file;
    private final int rate,channels,bytes;
    private final long frames;
    private final byte[] page=new byte[131072];
    private long pageStart=-1; private int pageLength;
    private PcmSource(File path,int rate,int channels,int bytes) throws IOException {
        file=new RandomAccessFile(path,"r"); this.rate=rate; this.channels=channels; this.bytes=bytes;
        frames=file.length()/(channels*bytes);
    }
    public double sample(long us,int channel) throws IOException {
        double pos=us*(double)rate/1000000; long frame=(long)pos; double fraction=pos-frame;
        if(frame<0 || frame>=frames) return 0;
        int ch=channels==1?0:channel;
        double a=value(frame,ch),b=value(Math.min(frames-1,frame+1),ch);
        return a+(b-a)*fraction;
    }
    private double value(long frame,int channel) throws IOException {
        long offset=(frame*channels+channel)*bytes;
        if(offset<pageStart || offset+bytes>pageStart+pageLength) {
            pageStart=(offset/page.length)*page.length; file.seek(pageStart);
            pageLength=(int)Math.min(page.length,file.length()-pageStart); file.readFully(page,0,pageLength);
        }
        int at=(int)(offset-pageStart);
        if(bytes==2) return (short)((page[at]&255)|(page[at+1]<<8))/32768.0;
        float v=Float.intBitsToFloat((page[at]&255)|((page[at+1]&255)<<8)|((page[at+2]&255)<<16)|(page[at+3]<<24));
        return Float.isFinite(v)?Math.max(-1,Math.min(1,v)):0;
    }
    public void close() throws IOException { file.close(); }
    static PcmSource decode(String path,File target,ExportControl control) throws Exception {
        MediaExtractor extractor=new MediaExtractor(); MediaCodec codec=null;
        try {
            extractor.setDataSource(path); int track=-1; MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++) {
                MediaFormat f=extractor.getTrackFormat(i);
                if(f.getString(MediaFormat.KEY_MIME).startsWith("audio/")) { track=i; format=f; break; }
            }
            if(track<0) return null;
            extractor.selectTrack(track); format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)); codec.configure(format,null,null,0); codec.start();
            int rate=0,channels=0,bytes=2; boolean inputEnd=false,outputEnd=false;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo(); long activity=System.nanoTime();
            try(RandomAccessFile out=new RandomAccessFile(target,"rw")) {
                while(!outputEnd) {
                    control.space(target.getParentFile());
                    if(!inputEnd) {
                        int index=codec.dequeueInputBuffer(10000);
                        if(index>=0) {
                            ByteBuffer input=codec.getInputBuffer(index); input.clear(); int n=extractor.readSampleData(input,0);
                            if(n<0) { codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnd=true; }
                            else { codec.queueInputBuffer(index,0,n,extractor.getSampleTime(),0); extractor.advance(); }
                            activity=System.nanoTime();
                        }
                    }
                    int index=codec.dequeueOutputBuffer(info,10000);
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f=codec.getOutputFormat(); int r=f.getInteger(MediaFormat.KEY_SAMPLE_RATE),c=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        int encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                        int b=encoding==AudioFormat.ENCODING_PCM_16BIT?2:encoding==AudioFormat.ENCODING_PCM_FLOAT?4:0;
                        if(r<=0 || r>192000 || c<1 || c>2 || b==0) throw new IOException("当前仅支持单声道 / 双声道 PCM 音轨");
                        if(rate!=0 && (r!=rate || c!=channels || b!=bytes)) throw new IOException("素材中途改变音频格式，暂不支持");
                        rate=r; channels=c; bytes=b; activity=System.nanoTime();
                    } else if(index>=0) {
                        if(info.size>0) {
                            if(rate==0) throw new IOException("解码器没有给出 PCM 格式");
                            int stride=channels*bytes; long frame=Math.round(info.presentationTimeUs*(double)rate/1000000);
                            int skip=(int)Math.min(info.size,Math.max(0,-frame)*stride); int size=(info.size-skip)/stride*stride;
                            long offset=Math.max(0,frame)*stride;
                            if(offset+size>512L*1024*1024) throw new IOException("单段音频解码缓存超过 512 MB，请先缩短源素材");
                            ByteBuffer data=codec.getOutputBuffer(index); data.position(info.offset+skip); data.limit(info.offset+skip+size);
                            byte[] buffer=new byte[Math.min(size,65536)]; out.seek(offset);
                            while(data.hasRemaining()) { int n=Math.min(buffer.length,data.remaining()); data.get(buffer,0,n); out.write(buffer,0,n); }
                        }
                        outputEnd=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0; codec.releaseOutputBuffer(index,false); activity=System.nanoTime();
                    }
                    if(System.nanoTime()-activity>30000000000L) throw new IOException("音频解码超时");
                }
                if(rate==0 || out.length()==0) throw new IOException("音轨为空或无法解码");
            }
            return new PcmSource(target,rate,channels,bytes);
        } finally { if(codec!=null) { try { codec.stop(); } catch(Exception ignored) {} codec.release(); } extractor.release(); }
    }
}