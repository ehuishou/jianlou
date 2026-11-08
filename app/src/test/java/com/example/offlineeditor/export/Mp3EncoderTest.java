package com.example.offlineeditor.export;
import java.io.*;
import java.nio.file.*;

/** Exercise the actual portable encoder and independently parse every MPEG frame. */
public final class Mp3EncoderTest {
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get(args[0]));
        int[] bitrates={0,32,40,48,56,64,80,96,112,128,160,192,224,256,320};
        for(int kbps:new int[]{128,192,320}) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] pcm=new byte[4096*4],encoded=new byte[16384];
            try(Mp3Encoder encoder=new Mp3Encoder(kbps)) {
                for(int start=0;start<88200;start+=4096) {
                    int n=Math.min(4096,88200-start);
                    for(int i=0;i<n;i++) for(int ch=0;ch<2;ch++) {
                        int value=(int)Math.round(Math.sin(2*Math.PI*(ch==0?440:880)*(start+i)/44100)*12000);
                        int offset=i*4+ch*2; pcm[offset]=(byte)value; pcm[offset+1]=(byte)(value>>8);
                    }
                    out.write(encoded,0,encoder.encode(pcm,n,encoded));
                }
                out.write(encoded,0,encoder.finish(encoded));
            }
            byte[] data=out.toByteArray(); int offset=0,frames=0;
            while(offset<data.length) {
                check(offset+4<=data.length,"truncated header");
                int h=((data[offset]&255)<<24)|((data[offset+1]&255)<<16)|((data[offset+2]&255)<<8)|(data[offset+3]&255);
                check((h&0xffe00000)==0xffe00000,"missing sync");
                check(((h>>>19)&3)==3 && ((h>>>17)&3)==1,"expected MPEG1 Layer III");
                int index=(h>>>12)&15; check(index>0 && index<15 && bitrates[index]==kbps,"wrong bitrate");
                check(((h>>>10)&3)==0,"expected 44.1 kHz");
                check(((h>>>6)&3)!=3,"expected stereo");
                offset+=144000*kbps/44100+((h>>>9)&1); frames++;
                check(offset<=data.length,"truncated MP3 frame");
            }
            double duration=frames*1152.0/44100;
            check(duration>=2 && duration<2.1,"incorrect duration: "+duration);
            Files.write(Paths.get(args[0],"tone-"+kbps+".mp3"),data);
            System.out.println("PASS MP3 "+kbps+" kbps: "+frames+" frames, "+data.length+" bytes, "+duration+" seconds");
        }
    }
}
