package com.example.offlineeditor.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Non-destructive commands; output time is local to the selected clip. */
public final class EditOperations {
    private EditOperations() {}
    public static Project remove(Project project,int index) {
        List<Clip> clips=new ArrayList<>(project.visuals);
        clips.remove(index);
        return new Project(project.id,project.title,clips,project.audio);
    }
    public static Project split(Project project,int index,long localUs) {
        Clip c=project.visuals.get(index);
        if(!c.speed.isConstant()) throw new IllegalArgumentException("曲线片段请先切换普通变速后分割");
        if(localUs<=0 || localUs>=c.durationUs()) throw new IllegalArgumentException("请将播放头移到片段内部");
        long offset=Math.round(localUs*c.speed.rateAt(0));
        long cut=c.reverse?c.outUs-offset:c.inUs+offset;
        if(cut<=c.inUs || cut>=c.outUs) throw new IllegalArgumentException("分割点太靠近边界");
        Clip first=c.edit(c.reverse?cut:c.inUs,c.reverse?c.outUs:cut,c.speed,c.reverse,c.transform);
        Clip second=new Clip(UUID.randomUUID().toString(),c.path,c.name,c.kind,c.sourceDurationUs,
                c.reverse?c.inUs:cut,c.reverse?cut:c.outUs,c.speed,c.reverse,c.transform);
        List<Clip> clips=new ArrayList<>(project.visuals);
        clips.set(index,first); clips.add(index+1,second);
        return new Project(project.id,project.title,clips,project.audio);
    }
    public static Project trim(Project project,int index,boolean left,long deltaOutputUs) {
        Clip c=project.visuals.get(index);
        if(!c.speed.isConstant()) throw new IllegalArgumentException("曲线片段请使用参数面板裁剪");
        long delta=Math.round(deltaOutputUs*c.speed.rateAt(0));
        long min=Math.min(100_000L,c.outUs-c.inUs);
        long in=c.inUs,out=c.outUs;
        if(left != c.reverse) in=Math.max(0,Math.min(out-min,in+(c.reverse?-delta:delta)));
        else out=Math.max(in+min,Math.min(c.sourceDurationUs,out+(c.reverse?-delta:delta)));
        return project.replace(index,c.edit(in,out,c.speed,c.reverse,c.transform));
    }
    public static long startUs(Project project,int index) {
        long start=0; for(int i=0;i<index;i++) start+=project.visuals.get(i).durationUs(); return start;
    }
}