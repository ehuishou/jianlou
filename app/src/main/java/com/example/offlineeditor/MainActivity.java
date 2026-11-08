package com.example.offlineeditor;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import com.example.offlineeditor.core.*;
import com.example.offlineeditor.export.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline project editor. Rendering capabilities are stated at their entry points. */
public final class MainActivity extends Activity {
    private static final int BG=0xff101114,SURFACE=0xff17191e,INK=0xfff0f2f5,MUTED=0xff929baa,ACCENT=0xff80e8c6;
    private Project project=Project.empty("我的剪辑");
    private final ProjectStore store=new ProjectStore();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayDeque<Project> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable ticker=this::tick;
    private TextView status,clock,play,selection,empty;
    private LinearLayout tools;
    private FrameLayout preview;
    private VideoView video;
    private ImageView image;
    private TimelineView timeline;
    private MediaPlayer player;
    private int selected=-1;
    private boolean importing,playing,exporting;
    private ExportSettings pendingExport;
    private Project pendingProject;
    private ExportControl exportControl;
    private Dialog exportProgress;
    private File draft;
    private long previewGeneration,positionUs,pendingLocalUs,lastTick;
    private Clip loadedClip;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(SURFACE);
        draft=new File(getFilesDir(),"project.oep");
        if(draft.isFile()) try { project=store.load(draft.toPath()); } catch(IOException e) { toast("草稿读取失败，原文件保留"); }
        if(state!=null && state.containsKey("exportFormat")) {
            pendingExport=new ExportSettings(ExportSettings.Format.valueOf(state.getString("exportFormat")),state.getInt("exportResolution"),state.getInt("exportFps"),state.getInt("exportKbps"));
            pendingProject=project;
        }
        LinearLayout base=column(); base.setBackgroundColor(BG);
        base.setOnApplyWindowInsetsListener((v,insets)-> { v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        LinearLayout top=row(); top.setPadding(dp(12),0,dp(12),0);
        TextView back=control("‹",24,()->drafts()); back.setContentDescription("项目草稿"); top.addView(back,new LinearLayout.LayoutParams(dp(42),dp(52)));
        LinearLayout brand=column(); TextView name=label("剪漏",19,INK); name.setTypeface(null,1); brand.addView(name);
        TextView subtitle=label("本地剪辑工作台",10,MUTED); brand.addView(subtitle); top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(control("保存",13,()-> { if(save()) toast("草稿已保存到本机"); }),new LinearLayout.LayoutParams(dp(54),dp(44)));
        TextView export=control("导出",13,()->showExport()); export.setTextColor(0xff12352c); export.setBackground(round(ACCENT,8));
        top.addView(export,new LinearLayout.LayoutParams(dp(64),dp(34))); base.addView(top);
        status=label("",11,MUTED); status.setPadding(dp(18),0,dp(18),dp(7)); base.addView(status);
        preview=new FrameLayout(this); preview.setBackgroundColor(0xff08090b); preview.setClipChildren(true);
        video=new VideoView(this); image=new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER)); preview.addView(image,new FrameLayout.LayoutParams(-1,-1));
        empty=label("＋\n导入视频 / 照片\n\n从本地素材开始你的剪辑",16,MUTED); empty.setGravity(Gravity.CENTER); empty.setOnClickListener(v->pick("*/*")); preview.addView(empty,new FrameLayout.LayoutParams(-1,-1));
        base.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout playback=row(); playback.setPadding(dp(14),0,dp(14),0);
        clock=label("00:00.0 / 00:00.0",11,INK); clock.setTypeface(Typeface.MONOSPACE); playback.addView(clock,new LinearLayout.LayoutParams(0,dp(48),1)); clock.setGravity(Gravity.CENTER_VERTICAL);
        playback.addView(control("|‹",18,()->seekProject(0)),new LinearLayout.LayoutParams(dp(42),dp(48)));
        play=control("▶",22,()->toggle()); play.setContentDescription("播放或暂停项目"); playback.addView(play,new LinearLayout.LayoutParams(dp(48),dp(48)));
        playback.addView(control("↶",23,()->history(false)),new LinearLayout.LayoutParams(dp(42),dp(48)));
        playback.addView(control("↷",23,()->history(true)),new LinearLayout.LayoutParams(dp(42),dp(48))); base.addView(playback);
        LinearLayout trackHeader=row(); trackHeader.setPadding(dp(16),0,dp(10),0); trackHeader.setBackgroundColor(SURFACE);
        selection=label("主轨道",11,MUTED); selection.setSingleLine(); selection.setEllipsize(android.text.TextUtils.TruncateAt.END); trackHeader.addView(selection,new LinearLayout.LayoutParams(0,dp(30),1)); selection.setGravity(Gravity.CENTER_VERTICAL);
        trackHeader.addView(control("−",22,()->timeline.zoom(.75f)),new LinearLayout.LayoutParams(dp(40),dp(32)));
        trackHeader.addView(control("＋",18,()->timeline.zoom(1.33f)),new LinearLayout.LayoutParams(dp(40),dp(32))); base.addView(trackHeader);
        timeline=new TimelineView(this); float height=getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density;
        base.addView(timeline,new LinearLayout.LayoutParams(-1,dp(height<700?184:216)));
        timeline.setListener(new TimelineView.Listener() {
            public void seek(long us) { seekProject(us); }
            public void gestureStarted() { pause(); }
            public void trim(int index,boolean left,long delta) { try { mutate(EditOperations.trim(project,index,left,delta),index,EditOperations.startUs(project,index)); } catch(IllegalArgumentException e) { toast(e.getMessage()); } }
            public void move(int from,int to) { Project changed=project.move(from,to); mutate(changed,to,EditOperations.startUs(changed,to)); }
        });
        TextView hint=label("滑动定位  ·  双指缩放  ·  拖动两端裁剪  ·  长按排序",10,MUTED); hint.setGravity(Gravity.CENTER); hint.setBackgroundColor(SURFACE); base.addView(hint,new LinearLayout.LayoutParams(-1,dp(28)));
        HorizontalScrollView bar=new HorizontalScrollView(this); bar.setHorizontalScrollBarEnabled(false); bar.setBackgroundColor(0xff202229);
        tools=row(); tools.setPadding(dp(8),dp(4),dp(8),dp(4)); bar.addView(tools); base.addView(bar,new LinearLayout.LayoutParams(-1,dp(78)));
        setContentView(base); render(); if(!project.visuals.isEmpty()) showAt(0,false); lastTick=SystemClock.uptimeMillis(); handler.postDelayed(ticker,40);
    }
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout layout=new LinearLayout(this); layout.setOrientation(1); return layout; }
    private LinearLayout row() { LinearLayout layout=new LinearLayout(this); layout.setOrientation(0); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private GradientDrawable round(int color,int radius) { GradientDrawable shape=new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private TextView label(String text,float size,int color) { TextView view=new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); return view; }
    private TextView control(String text,float size,Runnable action) { TextView view=label(text,size,INK); view.setGravity(Gravity.CENTER); view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x227fffff),null,null)); view.setOnClickListener(v->action.run()); view.setFocusable(true); return view; }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
    private void tool(String glyph,String title,Runnable action,boolean enabled) {
        LinearLayout item=column(); item.setGravity(Gravity.CENTER); item.setAlpha(enabled?1:.35f); item.setContentDescription(title); item.setFocusable(enabled);
        TextView icon=label(glyph,23,enabled?INK:MUTED); icon.setGravity(Gravity.CENTER); item.addView(icon,new LinearLayout.LayoutParams(-1,dp(32)));
        TextView caption=label(title,11,INK); caption.setGravity(Gravity.CENTER); item.addView(caption); item.setOnClickListener(enabled?v->action.run():null); tools.addView(item,new LinearLayout.LayoutParams(dp(68),-1));
    }
    private void render() {
        status.setText(importing?"正在复制素材到本机…":project.title+" · "+project.visuals.size()+" 段画面 / "+project.audio.size()+" 条音频");
        timeline.setProject(project,selected); timeline.setPosition(positionUs);
        boolean has=selected>=0 && selected<project.visuals.size();
        selection.setText(has?(selected+1)+" / "+project.visuals.size()+"   "+project.visuals.get(selected).name:"主轨道 · 选择片段开始编辑");
        empty.setVisibility(has?View.GONE:View.VISIBLE);
        tools.removeAllViews();
        tool("＋","素材",()->pick("*/*"),true);
        tool("✂","分割",()->split(),has);
        tool("⌫","删除",()->remove(),has);
        tool("◷","变速",()->speedPanel(),has);
        tool("↗","画面",()->transformPanel(),has);
        tool("▣","裁剪参数",()->trimPanel(),has);
        tool("≡","素材列表",()->assets(),true);
        tool("✦","动画",()->featurePanel("动画","入场、出场和循环动画入口已预留，关键帧执行仍在开发。",new String[]{"入场动画","出场动画","循环动画"}),has);
        tool("◈","滤镜",()->featurePanel("滤镜","滤镜工作台已预留，当前不会修改预览或草稿。",new String[]{"原片","黑白","鲜明"}),has);
        tool("♫","音效",()->featurePanel("音效","音频素材可以导入并显示在独立轨道；音效处理和混音仍在开发。",new String[]{"音效库","淡入淡出","音量"}),has || !project.audio.isEmpty());
        tool("T","文字",()->featurePanel("文字 / 自动字幕","文字与自动字幕入口已预留；离线识别、时间轴文字和样式渲染仍在开发。",new String[]{"普通文字","自动字幕","文字样式"}),has);
        tool("☻","贴纸",()->featurePanel("贴纸","贴纸工作台已预留，贴纸轨道、锚点和动画仍在开发。",new String[]{"贴纸库","动态贴纸","贴纸动画"}),has);
        tool("◫","画布",()->featurePanel("画布 / 蒙版","当前支持画面缩放、旋转和平移；画布比例、背景和蒙版仍在开发。",new String[]{"画布比例","背景","蒙版"}),has);
        syncTime();
    }
    private static String time(long us) { return TimelineView.format(us)+"."+((us/100_000)%10); }
    private void syncTime() { clock.setText(time(positionUs)+" / "+time(project.durationUs())); play.setText(playing?"Ⅱ":"▶"); timeline.setPosition(positionUs); }
    private void clearPreview() { previewGeneration++; playing=false; video.stopPlayback(); player=null; image.setImageDrawable(null); loadedClip=null; video.setVisibility(View.GONE); image.setVisibility(View.GONE); }
    private void pause() { playing=false; video.pause(); syncTime(); }
    private void seekProject(long us) { showAt(us,false); }
    private void showAt(long us,boolean resume) {
        positionUs=Math.max(0,Math.min(us,project.durationUs())); Project.Position at=project.locate(positionUs);
        if(at==null) { clearPreview(); selected=-1; empty.setText(project.visuals.isEmpty()?"＋\n导入视频 / 照片\n\n从本地素材开始你的剪辑":"当前没有画面\n音频混音尚未接入"); render(); return; }
        Clip c=at.clip; pendingLocalUs=at.localUs;
        if(resume && (c.reverse || !c.speed.isConstant())) { resume=false; toast("曲线 / 倒放仅保存参数，实时处理尚未接入"); }
        if(loadedClip==c) {
            playing=resume; if(c.kind==Clip.Kind.VIDEO && player!=null) { video.pause(); video.seekTo((int)(at.sourceUs/1000)); }
            lastTick=SystemClock.uptimeMillis(); syncTime(); return;
        }
        clearPreview(); selected=at.index; loadedClip=c; playing=resume; long generation=previewGeneration; render();
        View view=c.kind==Clip.Kind.IMAGE?image:video; view.setVisibility(View.VISIBLE); applyTransform(c.transform);
        if(c.kind==Clip.Kind.IMAGE) {
            worker.execute(()-> {
                BitmapFactory.Options opts=new BitmapFactory.Options(); opts.inJustDecodeBounds=true; BitmapFactory.decodeFile(c.path,opts);
                opts.inSampleSize=1; while(opts.outWidth/opts.inSampleSize>1920 || opts.outHeight/opts.inSampleSize>1920) opts.inSampleSize*=2;
                opts.inJustDecodeBounds=false; Bitmap bitmap=BitmapFactory.decodeFile(c.path,opts);
                runOnUiThread(()-> { if(!isDestroyed() && generation==previewGeneration) { image.setImageBitmap(bitmap); lastTick=SystemClock.uptimeMillis(); } else if(bitmap!=null) bitmap.recycle(); });
            });
        } else {
            video.setOnPreparedListener(mp-> {
                if(generation!=previewGeneration) return; player=mp;
                mp.setOnSeekCompleteListener(p-> { if(generation==previewGeneration && playing) startVideo(c); });
                video.seekTo((int)(c.sourceUs(Math.min(pendingLocalUs,c.durationUs()))/1000));
            });
            video.setOnCompletionListener(mp-> { if(generation==previewGeneration && playing) advance(); });
            video.setOnErrorListener((mp,what,extra)-> { if(generation==previewGeneration) { pause(); toast("设备无法播放此素材："+what+"/"+extra); } return true; });
            video.setVideoPath(c.path);
        }
        lastTick=SystemClock.uptimeMillis();
    }
    private void applyTransform(Transform transform) {
        for(View view:new View[]{image,video}) { view.setScaleX((float)transform.scale); view.setScaleY((float)transform.scale); view.setRotation((float)transform.rotationDegrees); view.setTranslationX((float)(transform.x*preview.getWidth())); view.setTranslationY((float)(transform.y*preview.getHeight())); }
    }
    private void startVideo(Clip c) {
        if(player==null) return;
        try { player.setPlaybackParams(new PlaybackParams().setSpeed((float)c.speed.rateAt(0))); video.start(); }
        catch(IllegalArgumentException | IllegalStateException e) { pause(); toast("设备不支持此速度"); }
    }
    private void toggle() {
        if(playing) { pause(); return; }
        if(project.visuals.isEmpty()) { toast("先导入视频或照片"); return; }
        if(selected<0 || positionUs>=EditOperations.startUs(project,project.visuals.size())) showAt(0,false);
        Clip c=project.visuals.get(selected);
        if(c.reverse || !c.speed.isConstant()) { toast("曲线 / 倒放仅保存参数，实时处理尚未接入"); return; }
        playing=true; lastTick=SystemClock.uptimeMillis(); if(c.kind==Clip.Kind.VIDEO) startVideo(c); syncTime();
    }
    private void tick() {
        if(isDestroyed()) return;
        long now=SystemClock.uptimeMillis(),delta=Math.min(250,now-lastTick); lastTick=now;
        if(playing && selected>=0 && selected<project.visuals.size()) {
            Clip c=project.visuals.get(selected); long start=EditOperations.startUs(project,selected);
            if(c.kind==Clip.Kind.IMAGE) { positionUs+=delta*1000; if(positionUs>=start+c.durationUs()) advance(); }
            else if(player!=null && video.isPlaying()) {
                long source=video.getCurrentPosition()*1000L;
                if(source>=c.outUs) advance();
                else positionUs=start+Math.max(0,Math.min(c.durationUs(),Math.round((source-c.inUs)/c.speed.rateAt(0))));
            }
        }
        syncTime(); handler.postDelayed(ticker,40);
    }
    private void advance() {
        long end=EditOperations.startUs(project,selected)+project.visuals.get(selected).durationUs();
        if(selected+1<project.visuals.size()) showAt(end,true);
        else { playing=false; video.pause(); positionUs=end; syncTime(); }
    }
    private void remember() { undo.addLast(project); while(undo.size()>40) undo.removeFirst(); redo.clear(); }
    private void mutate(Project next,int selection,long us) {
        remember(); pause(); clearPreview(); project=next; selected=-1; positionUs=Math.max(0,Math.min(us,project.durationUs())); save();
        if(selection>=0 && selection<project.visuals.size()) showAt(Math.min(positionUs,EditOperations.startUs(project,selection)+project.visuals.get(selection).durationUs()-1),false); else render();
    }
    private void history(boolean forward) {
        ArrayDeque<Project> from=forward?redo:undo,to=forward?undo:redo;
        if(from.isEmpty()) { toast(forward?"没有可重做的操作":"没有可撤销的操作"); return; }
        pause(); to.addLast(project); clearPreview(); project=from.removeLast(); selected=-1; positionUs=Math.min(positionUs,project.durationUs()); save(); if(!project.visuals.isEmpty()) showAt(Math.min(positionUs,EditOperations.startUs(project,project.visuals.size())-1),false); else render();
    }
    private void split() {
        try { long start=EditOperations.startUs(project,selected); mutate(EditOperations.split(project,selected,positionUs-start),selected,positionUs); }
        catch(IllegalArgumentException e) { toast(e.getMessage()); }
    }
    private void remove() {
        long start=EditOperations.startUs(project,selected); Project next=EditOperations.remove(project,selected); int nextIndex=Math.min(selected,next.visuals.size()-1);
        mutate(next,nextIndex,nextIndex<0?0:Math.min(start,EditOperations.startUs(next,nextIndex)));
    }
    private LinearLayout panel(String title,String description) {
        pause(); LinearLayout content=column(); content.setPadding(dp(20),dp(18),dp(20),dp(18)); content.setBackground(round(0xff24272f,18));
        TextView heading=label(title,19,INK); heading.setTypeface(null,1); content.addView(heading);
        TextView note=label(description,12,MUTED); note.setPadding(0,dp(8),0,dp(10)); content.addView(note); return content;
    }
    private Dialog sheet(LinearLayout content) {
        Dialog dialog=new Dialog(this); ScrollView scroll=new ScrollView(this); scroll.setFillViewport(false); scroll.addView(content); dialog.setContentView(scroll);
        Window window=dialog.getWindow(); window.setBackgroundDrawableResource(android.R.color.transparent); window.setDimAmount(.45f); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setGravity(Gravity.BOTTOM); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        int maxHeight=(int)(getResources().getDisplayMetrics().heightPixels*.72f);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().widthPixels,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST));
        window.setLayout(-1,Math.min(maxHeight,Math.max(dp(160),scroll.getMeasuredHeight()))); return dialog;
    }
    private void sheetAction(LinearLayout box,String text,Runnable action) {
        TextView button=control(text,14,action); button.setTextColor(ACCENT); button.setBackground(round(0xff343b44,8)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(44)); lp.topMargin=dp(8); box.addView(button,lp);
    }
    private EditText field(LinearLayout box,String title,double value) {
        box.addView(label(title,12,MUTED)); EditText input=new EditText(this); input.setTextColor(INK); input.setSingleLine(); input.setText(String.format(Locale.US,"%.3f",value)); input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED); box.addView(input); return input;
    }
    private double value(EditText input) { double v=Double.parseDouble(input.getText().toString()); if(!Double.isFinite(v)) throw new IllegalArgumentException("请输入有限数字"); return v; }
    private interface SliderChange { void changed(double value); }
    private SeekBar slider(LinearLayout box,String title,double initial,double min,double max,SliderChange change) {
        TextView caption=label(title+"   "+String.format(Locale.US,"%.2f",initial),12,INK); box.addView(caption);
        SeekBar bar=new SeekBar(this); bar.setMax(1000); bar.setProgress((int)((initial-min)/(max-min)*1000)); box.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
            public void onProgressChanged(SeekBar b,int p,boolean user) { double v=min+(max-min)*p/1000; caption.setText(title+"   "+String.format(Locale.US,"%.2f",v)); if(user) change.changed(v); }
        });
        return bar;
    }
    private void trimPanel() {
        final int index=selected; Clip c=project.visuals.get(index);
        LinearLayout box=panel("裁剪区间","直接拖动轨道两端可裁剪；这里可精确填写源素材时间。");
        EditText in=field(box,"起点（秒）",c.inUs/1e6),out=field(box,"终点（秒）",c.outUs/1e6);
        final Dialog[] dialog={null}; sheetAction(box,"应用裁剪",()-> { try { Clip updated=c.edit(Math.round(value(in)*1e6),Math.round(value(out)*1e6),c.speed,c.reverse,c.transform); mutate(project.replace(index,updated),index,EditOperations.startUs(project,index)); dialog[0].dismiss(); } catch(IllegalArgumentException e) { toast("区间无效，请检查起点与终点"); } }); dialog[0]=sheet(box);
    }
    private void speedPanel() {
        final int index=selected; Clip c=project.visuals.get(index); LinearLayout box=panel("变速","普通变速可预览。曲线和倒放仅保存参数，实时处理尚未接入。");
        final double[] rate={c.speed.rateAt(0)}; SeekBar speedBar=slider(box,"普通速度 ×",rate[0],.1,16,v->rate[0]=v);
        LinearLayout presets=row(); for(double speed:new double[]{.5,1,1.5,2,4}) { final double v=speed; presets.addView(control(speed+"×",13,()-> { rate[0]=v; speedBar.setProgress((int)Math.round((v-.1)/15.9*1000)); toast("已选 "+v+"×"); }),new LinearLayout.LayoutParams(0,dp(40),1)); } box.addView(presets);
        CheckBox curve=new CheckBox(this); curve.setText("曲线变速（仅保存参数）"); curve.setTextColor(INK); curve.setChecked(!c.speed.isConstant()); box.addView(curve);
        EditText middle=field(box,"曲线中点 ×",c.speed.rateAt(.5)),last=field(box,"曲线终点 ×",c.speed.rateAt(1));
        CheckBox reverse=new CheckBox(this); reverse.setText("倒放（仅保存参数）"); reverse.setTextColor(INK); reverse.setChecked(c.reverse); box.addView(reverse);
        final Dialog[] dialog={null}; sheetAction(box,"应用变速",()-> { try { SpeedMap speed=curve.isChecked()?new SpeedMap(new double[]{0,.5,1},new double[]{rate[0],value(middle),value(last)}):SpeedMap.constant(rate[0]); mutate(project.replace(index,c.edit(c.inUs,c.outUs,speed,reverse.isChecked(),c.transform)),index,EditOperations.startUs(project,index)); dialog[0].dismiss(); } catch(IllegalArgumentException e) { toast("速度需在 0.1–16 之间"); } }); dialog[0]=sheet(box);
    }
    private void transformPanel() {
        final int index=selected; Clip c=project.visuals.get(index); Transform t=c.transform;
        LinearLayout box=panel("画面调整","拖动滑杆实时查看效果，应用后写入草稿。");
        final double[] v={t.scale,t.rotationDegrees,t.x,t.y};
        slider(box,"缩放",v[0],.1,4,n-> { v[0]=n; applyTransform(new Transform(v[0],v[1],v[2],v[3])); });
        slider(box,"旋转 °",v[1],-180,180,n-> { v[1]=n; applyTransform(new Transform(v[0],v[1],v[2],v[3])); });
        slider(box,"水平位置",v[2],-1,1,n-> { v[2]=n; applyTransform(new Transform(v[0],v[1],v[2],v[3])); });
        slider(box,"垂直位置",v[3],-1,1,n-> { v[3]=n; applyTransform(new Transform(v[0],v[1],v[2],v[3])); });
        final Dialog[] dialog={null}; final boolean[] applied={false}; sheetAction(box,"应用画面调整",()-> { applied[0]=true; mutate(project.replace(index,c.edit(c.inUs,c.outUs,c.speed,c.reverse,new Transform(v[0],v[1],v[2],v[3]))),index,EditOperations.startUs(project,index)); dialog[0].dismiss(); }); dialog[0]=sheet(box); dialog[0].setOnDismissListener(d-> { if(!applied[0]) applyTransform(c.transform); });
    }
    private void assets() {
        LinearLayout box=panel("项目素材", "点击画面素材跳转到对应片段；音频已入轨，混音预览尚未接入。");
        final Dialog[] dialog={null}; for(int i=0;i<project.visuals.size();i++) { final int index=i; Clip c=project.visuals.get(i); sheetAction(box,(i+1)+"  "+c.name+"  ·  "+String.format(Locale.CHINA,"%.1fs",c.durationUs()/1e6),()-> { showAt(EditOperations.startUs(project,index),false); dialog[0].dismiss(); }); }
        for(Project.Audio audio:project.audio) box.addView(label("♫  "+audio.clip.name+" · "+time(audio.clip.durationUs()),13,0xffc7bdf5));
        sheetAction(box,"＋ 添加视频 / 照片 / 音频",()-> { dialog[0].dismiss(); pick("*/*"); }); dialog[0]=sheet(box);
    }
    private void featurePanel(String title,String description,String[] options) {
        LinearLayout box=panel(title,description);
        TextView state=label("当前状态：入口已准备 · 功能开发中",12,0xffffc67b);
        state.setPadding(0,dp(3),0,dp(6)); box.addView(state);
        for(String option:options) {
            TextView item=label(option+"  ·  开发中",14,MUTED);
            item.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL); item.setPadding(dp(14),0,dp(14),0);
            item.setBackground(round(0xff343b44,8));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(44)); lp.topMargin=dp(7); box.addView(item,lp);
        }
        final Dialog[] dialog={null}; sheetAction(box,"知道了",()->dialog[0].dismiss()); dialog[0]=sheet(box);
    }
    private void showExport() {
        if(exporting) { toast("正在导出，请稍候"); return; }
        if(importing) { toast("请等待素材导入完成"); return; }
        if(project.durationUs()<=0) { toast("请先导入素材"); return; }
        LinearLayout box=panel("导出成片","本地处理 · 无需联网\n按时间线导出裁剪、排序、变速、倒放及画面变换。导出期间请保持此页面打开。");
        Spinner format=exportChoice(box,"文件格式",new String[]{"MP4 · 视频成片","MP3 · 仅音频"},project.visuals.isEmpty()?1:0);
        LinearLayout videoOptions=column(); box.addView(videoOptions);
        Spinner quality=exportChoice(videoOptions,"清晰度（画面短边）",new String[]{"480p · 节省空间","720p · 推荐","1080p · 高清"},1);
        Spinner fps=exportChoice(videoOptions,"帧率",new String[]{"24 fps","25 fps","30 fps · 推荐","60 fps"},2);
        LinearLayout audioOptions=column(); box.addView(audioOptions);
        Spinner bitrate=exportChoice(audioOptions,"MP3 音质",new String[]{"128 kbps","192 kbps · 推荐","320 kbps"},1);
        TextView description=label("",12,MUTED); description.setPadding(0,dp(8),0,0); box.addView(description);
        format.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                videoOptions.setVisibility(position==0?View.VISIBLE:View.GONE); audioOptions.setVisibility(position==0?View.GONE:View.VISIBLE);
                description.setText(position==0?"H.264 + AAC · 比例跟随首段素材 · 空白区域为黑色。逐帧渲染可能较慢；设备不支持的参数会明确报错。":"44.1 kHz 双声道 · 混合视频原声与音频轨道。变速会同时改变音调；重叠音轨超出音量上限时限幅。");
                if(parent.getWindowToken()!=null) parent.requestLayout();
            }
        });
        final Dialog[] dialog={null}; sheetAction(box,"选择保存位置",()-> { try {
            if(!save()) return;
            ExportSettings.Format f=format.getSelectedItemPosition()==0?ExportSettings.Format.MP4:ExportSettings.Format.MP3;
            if(f==ExportSettings.Format.MP4 && project.visuals.isEmpty()) { toast("纯音频项目请选择 MP3"); return; }
            pendingExport=new ExportSettings(f,new int[]{480,720,1080}[quality.getSelectedItemPosition()],new int[]{24,25,30,60}[fps.getSelectedItemPosition()],new int[]{128,192,320}[bitrate.getSelectedItemPosition()]);
            pendingProject=project;
            String filename="剪漏_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+"."+pendingExport.extension();
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(pendingExport.mime()).putExtra(Intent.EXTRA_TITLE,filename).addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(intent,12); dialog[0].dismiss();
        } catch(Exception e) { toast("导出参数无效："+e.getMessage()); } }); dialog[0]=sheet(box);
        sheetAction(box,"MP3 开源组件说明",()-> { try {
            StringBuilder text=new StringBuilder(); try(BufferedReader reader=new BufferedReader(new InputStreamReader(getAssets().open("THIRD_PARTY_NOTICES.txt"),"UTF-8"))) { String line; while((line=reader.readLine())!=null) text.append(line).append('\n'); }
            new AlertDialog.Builder(this).setTitle("Jump3r / LAME · LGPL").setMessage(text.toString()).setPositiveButton("关闭",null).show();
        } catch(IOException e) { toast("无法读取许可证说明"); } });
    }
    private Spinner exportChoice(LinearLayout box,String title,String[] values,int initial) {
        TextView caption=label(title,12,MUTED); caption.setPadding(0,dp(10),0,dp(3)); box.addView(caption);
        Spinner spinner=new Spinner(this); spinner.setBackground(round(0xff343b44,8));
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,values) {
            private View tint(View v) { if(v instanceof TextView) { ((TextView)v).setTextColor(INK); ((TextView)v).setTextSize(14); v.setPadding(dp(12),dp(10),dp(12),dp(10)); } v.setBackgroundColor(0xff343b44); return v; }
            @Override public View getView(int position,View recycled,ViewGroup parent) { return tint(super.getView(position,recycled,parent)); }
            @Override public View getDropDownView(int position,View recycled,ViewGroup parent) { return tint(super.getDropDownView(position,recycled,parent)); }
        };
        spinner.setAdapter(adapter); spinner.setSelection(initial); box.addView(spinner,new LinearLayout.LayoutParams(-1,dp(46))); return spinner;
    }
    private void pick(String type) {
        if(importing) { toast("素材正在复制，请稍候"); return; }
        pause(); Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","image/*","audio/*"}); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); startActivityForResult(intent,11);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==12) {
            if(result==RESULT_OK && data!=null && data.getData()!=null && pendingExport!=null && pendingProject!=null) startExport(data.getData(),pendingExport,pendingProject);
            pendingExport=null; pendingProject=null; return;
        }
        if(request!=11 || result!=RESULT_OK || data==null) return;
        List<Uri> uris=new ArrayList<>(); if(data.getClipData()!=null) { for(int i=0;i<data.getClipData().getItemCount();i++) uris.add(data.getClipData().getItemAt(i).getUri()); } else if(data.getData()!=null) uris.add(data.getData());
        importing=true; render();
        worker.execute(()-> {
            List<Clip> clips=new ArrayList<>(); List<String> errors=new ArrayList<>(); for(Uri uri:uris) try { clips.add(importOne(uri)); } catch(Exception e) { errors.add(e.getMessage()); }
            runOnUiThread(()-> {
                importing=false; if(isDestroyed()) return; Project next=project;
                for(Clip c:clips) try { next=next.append(c); } catch(IllegalArgumentException e) { errors.add(e.getMessage()); }
                if(next!=project) { int index=project.visuals.size(); mutate(next,index<next.visuals.size()?index:selected,index<next.visuals.size()?EditOperations.startUs(next,index):positionUs); } else render();
                if(!errors.isEmpty()) toast("部分导入失败："+errors.get(0));
            });
        });
    }
    private void startExport(Uri destination,ExportSettings settings,Project snapshot) {
        exporting=true; pause();
        LinearLayout box=panel("正在导出 "+settings.extension().toUpperCase(Locale.US),"请保持此页面打开。取消或退出后将清理未完成的文件。");
        TextView progressText=label("准备素材…",14,INK); box.addView(progressText);
        ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(100); box.addView(progress);
        final ExportControl control=new ExportControl((stage,percent)->runOnUiThread(()-> { if(!isDestroyed()) { progressText.setText(stage+"  "+percent+"%"); progress.setProgress(percent); } })); exportControl=control;
        sheetAction(box,"取消导出",()-> { control.cancel(); progressText.setText("正在取消并清理文件…"); });
        exportProgress=sheet(box); exportProgress.setCancelable(false); exportProgress.setCanceledOnTouchOutside(false); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(()-> {
            File staging=new File(getCacheDir(),"export-"+UUID.randomUUID()); boolean success=false; String message;
            try {
                File result=Exporter.run(snapshot,settings,staging,control); control.check();
                try(InputStream in=new FileInputStream(result); OutputStream out=getContentResolver().openOutputStream(destination,"wt")) {
                    if(out==null) throw new IOException("无法打开保存位置"); byte[] buffer=new byte[128*1024]; int n; long copied=0;
                    while((n=in.read(buffer))!=-1) { control.check(); out.write(buffer,0,n); copied+=n; control.progress("保存到所选位置",96+(int)(copied*3/result.length())); } out.flush(); control.check();
                }
                success=true; message="导出成功 · "+settings.extension().toUpperCase(Locale.US)+"\n已保存到你选择的位置。";
            } catch(java.util.concurrent.CancellationException e) { message="导出已取消"; }
              catch(OutOfMemoryError e) { message="内存不足，请降低清晰度或缩短素材后重试"; }
              catch(Exception e) { message="导出失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()); }
            if(!success) { try { android.provider.DocumentsContract.deleteDocument(getContentResolver(),destination); } catch(Exception ignored) {} }
            deleteTree(staging); final String outcome=message; final boolean completed=success;
            runOnUiThread(()-> { exporting=false; exportControl=null; if(isDestroyed()) return; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); if(exportProgress!=null) { exportProgress.dismiss(); exportProgress=null; } render();
                AlertDialog.Builder resultDialog=new AlertDialog.Builder(this).setTitle(completed?"导出完成":"导出结束").setMessage(outcome).setPositiveButton("知道了",null);
                if(completed) resultDialog.setNeutralButton("打开文件",(d,which)-> { try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(destination,settings.mime()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); } catch(ActivityNotFoundException e) { toast("未找到可以打开此文件的应用，请在文件管理器查看"); } }); resultDialog.show(); });
        });
    }

    private static void deleteTree(File file) { if(file==null || !file.exists()) return; File[] children=file.listFiles(); if(children!=null) for(File child:children) deleteTree(child); file.delete(); }
    private Clip importOne(Uri uri) throws Exception {
        String mime=getContentResolver().getType(uri),name="素材"; Clip.Kind kind;
        if(mime!=null && mime.startsWith("image/")) kind=Clip.Kind.IMAGE; else if(mime!=null && mime.startsWith("audio/")) kind=Clip.Kind.AUDIO; else if(mime!=null && mime.startsWith("video/")) kind=Clip.Kind.VIDEO; else throw new IOException("不支持的素材类型");
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) { if(c!=null && c.moveToFirst() && !c.isNull(0)) name=c.getString(0); }
        File dir=new File(getFilesDir(),"media"); if(!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建素材目录"); File dest=new File(dir,UUID.randomUUID().toString()+".media");
        try {
            try(InputStream in=getContentResolver().openInputStream(uri); OutputStream out=new FileOutputStream(dest)) {
                if(in==null) throw new IOException("无法读取素材"); byte[] buf=new byte[128*1024]; int n; long bytes=0;
                while((n=in.read(buf))!=-1) { bytes+=n; if(bytes>2L*1024*1024*1024) throw new IOException("单素材上限 2 GiB"); out.write(buf,0,n); }
            }
            long duration=5_000_000;
            if(kind==Clip.Kind.IMAGE) { BitmapFactory.Options opts=new BitmapFactory.Options(); opts.inJustDecodeBounds=true; BitmapFactory.decodeFile(dest.getPath(),opts); if(opts.outWidth<=0 || opts.outHeight<=0) throw new IOException("无法解码此图片"); }
            else { MediaMetadataRetriever retriever=new MediaMetadataRetriever(); try { retriever.setDataSource(dest.getPath()); duration=Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))*1000L; } finally { retriever.release(); } }
            return Clip.create(dest.getPath(),name,kind,duration);
        } catch(Exception e) { if(!dest.delete()) dest.deleteOnExit(); throw e; }
    }
    private boolean save() { try { store.save(draft.toPath(),project); return true; } catch(IOException e) { toast("保存失败："+e.getMessage()); return false; } }
    private void drafts() {
        if(importing) { toast("请等待导入结束"); return; } pause(); File folder=new File(getFilesDir(),"drafts"); folder.mkdirs();
        File[] files=folder.listFiles((d,n)->n.endsWith(".oep")); if(files==null) files=new File[0]; Arrays.sort(files,Comparator.comparing(File::getName));
        LinearLayout box=panel("本地项目","切换前自动保存当前项目，所有素材保留在本机。"); final Dialog[] dialog={null};
        sheetAction(box,"＋ 新建项目",()-> { switchDraft(Project.empty("项目 "+(System.currentTimeMillis()%10000))); dialog[0].dismiss(); });
        for(File file:files) {
            String title=file.getName(); try { title=store.load(file.toPath()).title; } catch(IOException ignored) { title+="（无法读取）"; }
            sheetAction(box,title,()-> { try { Project next=store.load(file.toPath()); switchDraft(next); dialog[0].dismiss(); } catch(IOException e) { toast("草稿读取失败"); } });
        }
        dialog[0]=sheet(box);
    }
    private void switchDraft(Project next) {
        try { File folder=new File(getFilesDir(),"drafts"); folder.mkdirs(); store.save(new File(folder,project.id+".oep").toPath(),project); clearPreview(); project=next; selected=-1; positionUs=0; undo.clear(); redo.clear(); save(); if(project.visuals.isEmpty()) render(); else showAt(0,false); }
        catch(IOException e) { toast("切换失败："+e.getMessage()); }
    }
    @Override protected void onPause() { super.onPause(); pause(); }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); if(pendingExport!=null) { state.putString("exportFormat",pendingExport.format.name()); state.putInt("exportResolution",pendingExport.resolution); state.putInt("exportFps",pendingExport.fps); state.putInt("exportKbps",pendingExport.audioKbps); } }
    @Override protected void onDestroy() { if(exportControl!=null) exportControl.cancel(); if(exportProgress!=null) exportProgress.dismiss(); handler.removeCallbacks(ticker); clearPreview(); timeline.close(); worker.shutdown(); super.onDestroy(); }
}
