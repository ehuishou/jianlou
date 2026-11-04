package com.example.offlineeditor;

import android.content.Context;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.LruCache;
import android.view.*;
import com.example.offlineeditor.core.*;
import java.util.*;
import java.util.concurrent.*;

/** A project-time viewport: the fixed playhead is the origin of every gesture. */
public final class TimelineView extends View {
    public interface Listener {
        void seek(long us);
        void trim(int index,boolean left,long deltaUs);
        void move(int from,int to);
        void gestureStarted();
    }
    private final Paint paint=new Paint(3);
    private final float density;
    private final ScaleGestureDetector scale;
    private final ExecutorService thumbnails=Executors.newSingleThreadExecutor();
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(8*1024*1024) {
        protected int sizeOf(String key,Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final Set<String> pending=new HashSet<>();
    private Project project=Project.empty("");
    private int selected=-1,hit=-1,drop=-1,mode;
    private long positionUs,downTimeUs;
    private float pixelsPerSecond,downX,lastX,trimDelta;
    private boolean moved,multi,closed;
    private Listener listener;
    private final Runnable longPress=()-> {
        if(hit>=0 && mode==0 && !moved && !multi) {
            mode=3; drop=hit; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); invalidate();
        }
    };
    public TimelineView(Context context) {
        super(context); density=getResources().getDisplayMetrics().density; pixelsPerSecond=dp(48);
        setContentDescription("剪辑时间线。滑动定位，双指缩放，拖动选中片段两端裁剪，长按片段排序");
        setFocusable(true);
        scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            public boolean onScaleBegin(ScaleGestureDetector detector) { multi=true; mode=0; removeCallbacks(longPress); return true; }
            public boolean onScale(ScaleGestureDetector detector) { zoom(detector.getScaleFactor()); return true; }
        });
    }
    private float dp(float v) { return v*density; }
    public void setListener(Listener value) { listener=value; }
    public void setProject(Project value,int selection) { project=value; selected=selection; positionUs=Math.min(positionUs,project.durationUs()); invalidate(); }
    public void setPosition(long us) { positionUs=Math.max(0,Math.min(us,project.durationUs())); invalidate(); }
    public long getPosition() { return positionUs; }
    public void zoom(float factor) { pixelsPerSecond=Math.max(dp(8),Math.min(dp(240),pixelsPerSecond*factor)); invalidate(); }
    private float x(long us) { return getWidth()/2f+(us-positionUs)/1_000_000f*pixelsPerSecond; }
    private long time(float x) { return Math.max(0,Math.min(project.durationUs(),positionUs+Math.round((x-getWidth()/2f)/pixelsPerSecond*1_000_000))); }
    private void rect(Canvas canvas,float l,float t,float r,float b,int color,float radius) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); canvas.drawRoundRect(l,t,r,b,dp(radius),dp(radius),paint);
    }
    private void text(Canvas canvas,String value,float x,float y,int color,float size) {
        paint.setColor(color); paint.setTextSize(dp(size)); paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL)); canvas.drawText(value,x,y,paint);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.drawColor(0xff17191e);
        double step=pixelsPerSecond/density>=120?.5:pixelsPerSecond/density>=36?1:pixelsPerSecond/density>=16?5:10;
        long first=(long)Math.floor(Math.max(0,positionUs/1e6-getWidth()/2f/pixelsPerSecond)/step);
        for(long k=first;k<first+getWidth()/pixelsPerSecond/step+3;k++) {
            long us=Math.round(k*step*1e6); float xx=x(us);
            paint.setColor(0xff4d515b); paint.setStrokeWidth(dp(1)); canvas.drawLine(xx,dp(26),xx,dp(33),paint);
            text(canvas,format(us),xx+dp(4),dp(19),0xff979da9,10);
        }
        long start=0;
        for(int i=0;i<project.visuals.size();i++) {
            Clip clip=project.visuals.get(i); float left=x(start),right=x(start+clip.durationUs()); start+=clip.durationUs();
            if(right<0 || left>getWidth()) continue;
            float top=dp(44),bottom=dp(106);
            if(i==hit && mode==3) { top-=dp(5); bottom-=dp(5); }
            rect(canvas,left+dp(1),top,right-dp(1),bottom,i==selected?0xff306857:0xff33444c,5);
            canvas.save(); canvas.clipRect(left+dp(2),top+dp(2),right-dp(2),bottom-dp(2));
            Bitmap thumb=thumbnail(clip);
            if(thumb!=null) {
                float cell=dp(58),begin=left+(float)Math.floor(Math.max(0,-left)/cell)*cell;
                for(float xx=begin;xx<Math.min(getWidth(),right);xx+=cell) canvas.drawBitmap(thumb,null,new RectF(xx,top,xx+cell,bottom),paint);
            }
            rect(canvas,left,top,right,top+dp(20),0xc011161a,0);
            text(canvas,(i+1)+"  "+clip.name,Math.max(left+dp(8),dp(4)),top+dp(14),0xfff4f5f7,10);
            text(canvas,String.format(Locale.US,"%.1fs%s",clip.durationUs()/1e6,clip.speed.isConstant()?" · "+clip.speed.rateAt(0)+"×":" · 曲线"),Math.max(left+dp(8),dp(4)),bottom-dp(7),0xffffffff,10);
            canvas.restore();
            if(i==selected) {
                paint.setColor(0xff80e8c6); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
                canvas.drawRoundRect(left+dp(1),top,right-dp(1),bottom,dp(5),dp(5),paint); paint.setStyle(Paint.Style.FILL);
                float l=left+(mode==1?trimDelta:0),r=right+(mode==2?trimDelta:0);
                rect(canvas,l-dp(5),top,l+dp(6),bottom,0xff80e8c6,3);
                rect(canvas,r-dp(6),top,r+dp(5),bottom,0xff80e8c6,3);
                rect(canvas,l-dp(1),top+dp(23),l+dp(1),bottom-dp(23),0xff12352c,1);
                rect(canvas,r-dp(1),top+dp(23),r+dp(1),bottom-dp(23),0xff12352c,1);
            }
        }
        if(project.visuals.isEmpty()) text(canvas,"导入视频或照片，开始剪辑",dp(22),dp(79),0xff969eab,13);
        int count=Math.min(2,project.audio.size());
        for(int i=0;i<count;i++) {
            Project.Audio a=project.audio.get(i); float top=dp(124+i*34);
            rect(canvas,x(a.startUs),top,x(a.startUs+a.clip.durationUs()),top+dp(27),0xff33304d,5);
            canvas.save(); canvas.clipRect(Math.max(0,x(a.startUs)),top,Math.min(getWidth(),x(a.startUs+a.clip.durationUs())),top+dp(27));
            text(canvas,"♫  "+a.clip.name+" · 未混音",Math.max(dp(8),x(a.startUs)+dp(8)),top+dp(18),0xffc7bdf5,11); canvas.restore();
        }
        if(count==0) {
            rect(canvas,dp(14),dp(123),getWidth()-dp(14),dp(156),0xff22252d,6);
            text(canvas,"＋ 音频轨道    ·    从「素材」导入",dp(26),dp(144),0xff838b9a,11);
        }
        if(project.audio.size()>2) text(canvas,"另有 "+(project.audio.size()-2)+" 条音轨 · 素材面板查看",dp(16),dp(201),0xffb8acdf,10);
        if(mode==3 && drop>=0) {
            float target=x(EditOperations.startUs(project,drop)); paint.setColor(0xffffc67b); paint.setStrokeWidth(dp(3)); canvas.drawLine(target,dp(38),target,dp(114),paint);
            text(canvas,"松开移至第 "+(drop+1)+" 位",dp(16),getHeight()-dp(8),0xffffc67b,12);
        }
        float center=getWidth()/2f;
        paint.setColor(0xfff4f5f7); paint.setStrokeWidth(dp(1.5f)); canvas.drawLine(center,dp(28),center,getHeight()-dp(13),paint);
        rect(canvas,center-dp(4),dp(25),center+dp(4),dp(32),0xfff4f5f7,2);
    }
    private Bitmap thumbnail(Clip clip) {
        String key=clip.path+":"+clip.inUs; Bitmap value=cache.get(key);
        if(value!=null || pending.contains(key) || closed) return value;
        pending.add(key);
        thumbnails.execute(()-> {
            Bitmap decoded=null;
            try {
                if(clip.kind==Clip.Kind.IMAGE) {
                    BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true; BitmapFactory.decodeFile(clip.path,options);
                    options.inSampleSize=1; while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>256) options.inSampleSize*=2;
                    options.inJustDecodeBounds=false; decoded=BitmapFactory.decodeFile(clip.path,options);
                } else {
                    MediaMetadataRetriever retriever=new MediaMetadataRetriever();
                    try { retriever.setDataSource(clip.path); decoded=Build.VERSION.SDK_INT>=27?retriever.getScaledFrameAtTime(clip.inUs,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,128,128):retriever.getFrameAtTime(clip.inUs,MediaMetadataRetriever.OPTION_CLOSEST_SYNC); }
                    finally { retriever.release(); }
                }
                if(decoded!=null) { Bitmap small=Bitmap.createScaledBitmap(decoded,112,112,true); if(small!=decoded) decoded.recycle(); decoded=small; }
            } catch(Exception ignored) { /* The solid clip remains usable when a decoder is unavailable. */ }
            final Bitmap result=decoded;
            post(()-> { if(closed) { if(result!=null) result.recycle(); return; } if(result!=null) { pending.remove(key); cache.put(key,result); } invalidate(); });
        });
        return null;
    }
    private int hit(float xx) {
        long us=time(xx),start=0;
        for(int i=0;i<project.visuals.size();i++) { long end=start+project.visuals.get(i).durationUs(); if(us>=start && us<end) return i; start=end; }
        return -1;
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        scale.onTouchEvent(event);
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                if(listener!=null) listener.gestureStarted();
                downX=lastX=event.getX(); downTimeUs=positionUs; moved=false; multi=false; mode=0; trimDelta=0;
                hit=event.getY()>=dp(40) && event.getY()<=dp(112)?hit(downX):-1;
                if(selected>=0 && event.getY()>=dp(40) && event.getY()<=dp(112)) {
                    long begin=EditOperations.startUs(project,selected); float left=x(begin),right=x(begin+project.visuals.get(selected).durationUs());
                    if(Math.abs(downX-left)<dp(14) || Math.abs(downX-right)<dp(14)) {
                        mode=Math.abs(downX-left)<Math.abs(downX-right)?1:2; hit=selected;
                    }
                }
                if(mode==0 && hit>=0) postDelayed(longPress,450);
                return true;
            case MotionEvent.ACTION_MOVE:
                lastX=event.getX(); if(Math.abs(lastX-downX)>dp(7)) { moved=true; removeCallbacks(longPress); }
                if(multi || scale.isInProgress()) return true;
                if(mode==1 || mode==2) { trimDelta=lastX-downX; invalidate(); }
                else if(mode==3) { drop=hit(lastX); if(drop<0) drop=project.visuals.size()-1; invalidate(); }
                else if(moved) {
                    setPosition(downTimeUs-Math.round((lastX-downX)/pixelsPerSecond*1_000_000));
                    if(listener!=null) listener.seek(positionUs);
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if(!multi && listener!=null) {
                    if((mode==1 || mode==2) && moved) listener.trim(hit,mode==1,Math.round(trimDelta/pixelsPerSecond*1_000_000));
                    else if(mode==3 && drop>=0 && hit!=drop) listener.move(hit,drop);
                    else if(!moved && mode==0) { performClick(); listener.seek(time(event.getX())); }
                }
                mode=0; trimDelta=0; getParent().requestDisallowInterceptTouchEvent(false); invalidate(); return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress); mode=0; trimDelta=0; getParent().requestDisallowInterceptTouchEvent(false); invalidate(); return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    public void close() { closed=true; removeCallbacks(longPress); thumbnails.shutdownNow(); cache.evictAll(); }
    public static String format(long us) { long seconds=Math.max(0,us)/1_000_000; return String.format(Locale.US,"%02d:%02d",seconds/60,seconds%60); }
}
