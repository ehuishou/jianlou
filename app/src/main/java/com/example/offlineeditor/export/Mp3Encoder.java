/* Module wiring adapted from Jump3r LameEncoder (LGPL-2.1-or-later).
 * Portable encoder only: no javax.sound. See bundled THIRD_PARTY_NOTICES.txt. */
package com.example.offlineeditor.export;
import de.sciss.jump3r.mp3.*;
import de.sciss.jump3r.mpg.*;
import java.io.IOException;

public final class Mp3Encoder implements AutoCloseable {
    private final Lame lame=new Lame();
    private final LameGlobalFlags flags;
    private final int[] left=new int[4096],right=new int[4096];
    public Mp3Encoder(int bitrate) throws IOException {
        GainAnalysis ga=new GainAnalysis(); BitStream bs=new BitStream(); Presets p=new Presets();
        QuantizePVT qp=new QuantizePVT(); Quantize q=new Quantize(); VBRTag vbr=new VBRTag();
        Version ver=new Version(); ID3Tag id3=new ID3Tag(); Reservoir rv=new Reservoir(); Takehiro tak=new Takehiro();
        MPGLib mpg=new MPGLib(); Interface intf=new Interface(); Common common=new Common();
        lame.setModules(ga,bs,p,qp,q,vbr,ver,id3,mpg); bs.setModules(ga,mpg,ver,vbr); id3.setModules(bs,ver);
        p.setModules(lame); q.setModules(bs,rv,qp,tak); qp.setModules(tak,rv,lame.enc.psy);
        rv.setModules(bs); tak.setModules(qp); vbr.setModules(lame,bs,ver); mpg.setModules(intf,common); intf.setModules(vbr,common);
        flags=lame.lame_init(); flags.num_channels=2; flags.in_samplerate=44100; flags.out_samplerate=44100;
        flags.mode=MPEGMode.JOINT_STEREO; flags.brate=bitrate; flags.quality=5; flags.VBR=VbrMode.vbr_off;
        flags.bWriteVbrTag=false; flags.write_id3tag_automatic=false; flags.findReplayGain=false;
        id3.id3tag_init(flags);
        if(lame.lame_init_params(flags)<0) { lame.lame_close(flags); throw new IOException("MP3 初始化失败"); }
    }
    public int encode(byte[] pcm,int frames,byte[] output) throws IOException {
        if(frames<0 || frames>left.length || pcm.length<frames*4) throw new IllegalArgumentException("PCM block");
        for(int i=0;i<frames;i++) {
            int at=i*4;
            left[i]=((pcm[at]&255)|(pcm[at+1]<<8))<<16;
            right[i]=((pcm[at+2]&255)|(pcm[at+3]<<8))<<16;
        }
        int n=lame.lame_encode_buffer_int(flags,left,right,frames,output,0,output.length);
        if(n<0) throw new IOException("MP3 编码失败："+n); return n;
    }
    public int finish(byte[] output) throws IOException {
        int n=lame.lame_encode_flush(flags,output,0,output.length);
        if(n<0) throw new IOException("MP3 收尾失败："+n); return n;
    }
    public void close() { lame.lame_close(flags); }
}