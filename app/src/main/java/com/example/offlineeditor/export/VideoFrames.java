package com.example.offlineeditor.export;
import android.graphics.*;
import android.media.*;
import android.os.Build;
import com.example.offlineeditor.core.*;
import java.io.*;

/** Reference renderer: source-time sampling, fit-center, then the project's centered transform. */
final class VideoFrames implements AutoCloseable {
    private final int width,height;
    private final Bitmap frame;
    private final Canvas canvas;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private Clip current; private Bitmap still; private MediaMetadataRetriever retriever;
    VideoFrames(int width,int height) { this.width=width; this.height=height; frame=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); canvas=new Canvas(frame); }
    static int[] dimensions(Project project,ExportSettings settings) throws Exception {
        if(project.visuals.isEmpty()) throw new IOException("MP4 需要至少一段视频或图片；纯音频请选择 MP3");
        Clip c=project.visuals.get(0); int w,h;
        if(c.kind==Clip.Kind.IMAGE) {
            BitmapFactory.Options o=new BitmapFactory.Options(); o.inJustDecodeBounds=true; BitmapFactory.decodeFile(c.path,o); w=o.outWidth; h=o.outHeight;
            int orientation=new ExifInterface(c.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
            if(orientation>=5 && orientation<=8) { int t=w; w=h; h=t; }
        } else {
            MediaMetadataRetriever r=new MediaMetadataRetriever();
            try { r.setDataSource(c.path); w=Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)); h=Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                String rotation=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION); int angle=rotation==null?0:Integer.parseInt(rotation);
                if(Math.abs(angle)%180==90) { int t=w; w=h; h=t; }
            } finally { r.release(); }
        }
        return settings.dimensions(w,h);
    }
    Bitmap at(Project.Position position) throws Exception {
        canvas.drawColor(Color.BLACK);
        if(position==null) return frame;
        Clip c=position.clip;
        if(c!=current) {
            clearSource(); current=c;
            if(c.kind==Clip.Kind.IMAGE) {
                BitmapFactory.Options o=new BitmapFactory.Options(); o.inJustDecodeBounds=true; BitmapFactory.decodeFile(c.path,o); o.inSampleSize=1;
                while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>Math.max(width,height)*2) o.inSampleSize*=2;
                o.inJustDecodeBounds=false; o.inPreferredConfig=Bitmap.Config.ARGB_8888; still=BitmapFactory.decodeFile(c.path,o);
                if(still==null) throw new IOException("图片解码失败："+c.name);
                int orientation=new ExifInterface(c.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
                Matrix m=new Matrix();
                switch(orientation) {
                    case 2:m.setScale(-1,1);break; case 3:m.setRotate(180);break; case 4:m.setScale(1,-1);break;
                    case 5:m.setRotate(90);m.postScale(-1,1);break; case 6:m.setRotate(90);break;
                    case 7:m.setRotate(-90);m.postScale(-1,1);break; case 8:m.setRotate(-90);break;
                }
                if(!m.isIdentity()) { Bitmap rotated=Bitmap.createBitmap(still,0,0,still.getWidth(),still.getHeight(),m,true); if(rotated!=still) still.recycle(); still=rotated; }
            } else { retriever=new MediaMetadataRetriever(); retriever.setDataSource(c.path); }
        }
        Bitmap bitmap=still;
        if(retriever!=null) {
            bitmap=Build.VERSION.SDK_INT>=27?retriever.getScaledFrameAtTime(position.sourceUs,MediaMetadataRetriever.OPTION_CLOSEST,width,height):retriever.getFrameAtTime(position.sourceUs,MediaMetadataRetriever.OPTION_CLOSEST);
            if(bitmap==null) throw new IOException("视频帧解码失败："+c.name);
        }
        try {
            float fit=Math.min((float)width/bitmap.getWidth(),(float)height/bitmap.getHeight()); Transform t=c.transform;
            canvas.save(); canvas.translate((float)(width/2.0+t.x*width),(float)(height/2.0+t.y*height)); canvas.rotate((float)t.rotationDegrees);
            canvas.scale((float)(fit*t.scale),(float)(fit*t.scale)); canvas.drawBitmap(bitmap,-bitmap.getWidth()/2f,-bitmap.getHeight()/2f,paint); canvas.restore();
        } finally { if(bitmap!=still) bitmap.recycle(); }
        return frame;
    }
    static void encode(Project project,ExportSettings settings,File out,ExportControl control) throws Exception {
        int[] dims=dimensions(project,settings); int w=dims[0],h=dims[1];
        MediaFormat format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,w,h);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE,settings.videoBitrate(w,h)); format.setInteger(MediaFormat.KEY_FRAME_RATE,settings.fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1); format.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        try(EncodedTrack track=new EncodedTrack(out,format,true,control); FrameSurface surface=new FrameSurface(track.surface,w,h); VideoFrames frames=new VideoFrames(w,h)) {
            long count=settings.frameCount(project.durationUs());
            for(long i=0;i<count;i++) {
                control.space(out.getParentFile()); track.drain(false); long us=settings.frameTimeUs(i);
                surface.frame(frames.at(project.locate(us)),us); track.drain(false);
                control.progress("渲染 MP4 · "+w+"×"+h+" / "+settings.fps+" fps",40+(int)(i*50/count));
            }
            track.codec.signalEndOfInputStream(); track.finish();
        }
    }
    private void clearSource() throws IOException {
        if(still!=null) { still.recycle(); still=null; } if(retriever!=null) { retriever.release(); retriever=null; }
    }
    public void close() throws IOException { try { clearSource(); } finally { frame.recycle(); } }
}