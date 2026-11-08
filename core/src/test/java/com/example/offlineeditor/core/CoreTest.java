package com.example.offlineeditor.core;

import java.nio.file.*;
import java.util.*;

public final class CoreTest {
    static int assertions;
    static void near(double actual,double expected,double tolerance) { assertions++; if(Math.abs(actual-expected)>tolerance) throw new AssertionError(actual+" != "+expected); }
    static void yes(boolean value) { assertions++; if(!value) throw new AssertionError(); }
    static void rejects(Runnable action) { assertions++; try { action.run(); } catch(IllegalArgumentException e) { return; } throw new AssertionError("Expected rejection"); }
    static void audioPreserved(Project before,Project after) {
        yes(after.audio.size()==before.audio.size());
        for(int i=0;i<before.audio.size();i++) {
            yes(after.audio.get(i).clip==before.audio.get(i).clip);
            near(after.audio.get(i).startUs,before.audio.get(i).startUs,0);
        }
    }
    static void editOperations(Clip source,Clip image,Clip audio) {
        List<Project.Audio> overlays=Collections.singletonList(new Project.Audio(audio,1_500_000));
        for(double rate:new double[]{.5,1,2,4}) for(boolean reverse:new boolean[]{false,true}) {
            Clip clip=source.edit(2_000_000,8_000_000,SpeedMap.constant(rate),reverse,Transform.identity());
            Project original=new Project("operations","编辑操作",Arrays.asList(clip,image),overlays);
            long cut=clip.durationUs()/3;
            Project split=EditOperations.split(original,0,cut);
            Clip first=split.visuals.get(0),second=split.visuals.get(1);
            yes(split.visuals.size()==3); yes(first.id.equals(clip.id)); yes(!first.id.equals(second.id));
            near(first.durationUs(),cut,0); near(first.durationUs()+second.durationUs(),clip.durationUs(),0);
            near(EditOperations.startUs(split,3),EditOperations.startUs(original,2),0);
            if(reverse) near(first.inUs,second.outUs,0); else near(first.outUs,second.inUs,0);
            // Compare both sides of the edit against the unsplit clip, including the join.
            for(long at:new long[]{0,cut/2,cut-1,cut,cut+1,clip.durationUs()-1}) {
                Project.Position mapped=split.locate(at);
                near(mapped.sourceUs,clip.sourceUs(at),1);
            }
            audioPreserved(original,split);
            yes(original.visuals.size()==2); near(original.visuals.get(0).inUs,2_000_000,0);
            rejects(()->EditOperations.split(original,0,0));
            rejects(()->EditOperations.split(original,0,clip.durationUs()));

            Project left=EditOperations.trim(original,0,true,500_000);
            Project right=EditOperations.trim(original,0,false,-500_000);
            near(left.visuals.get(0).durationUs(),clip.durationUs()-500_000,0);
            near(right.visuals.get(0).durationUs(),clip.durationUs()-500_000,0);
            near(left.visuals.get(0).sourceUs(0),clip.sourceUs(500_000),1);
            near(right.visuals.get(0).sourceUs(0),clip.sourceUs(0),0);
            audioPreserved(original,left); audioPreserved(original,right);
            // Large drags clamp to a nonempty source interval and never exceed the file.
            for(Project trimmed:new Project[]{EditOperations.trim(original,0,true,100_000_000),EditOperations.trim(original,0,false,-100_000_000)}) {
                near(trimmed.visuals.get(0).outUs-trimmed.visuals.get(0).inUs,100_000,0);
            }
            for(Project extended:new Project[]{EditOperations.trim(original,0,true,-100_000_000),EditOperations.trim(original,0,false,100_000_000)}) {
                Clip edge=extended.visuals.get(0);
                yes(edge.inUs==0 || edge.outUs==source.sourceDurationUs);
                yes(edge.inUs>=0 && edge.outUs<=source.sourceDurationUs);
            }
            Project removed=EditOperations.remove(original,0);
            yes(removed.visuals.size()==1 && removed.visuals.get(0)==image);
            audioPreserved(original,removed);
            near(EditOperations.startUs(original,2),clip.durationUs()+image.durationUs(),0);
            yes(original.durationUs()>EditOperations.startUs(original,2));
        }
        Clip curved=source.edit(0,source.sourceDurationUs,new SpeedMap(new double[]{0,1},new double[]{1,2}),false,Transform.identity());
        Project curvedProject=Project.empty("曲线").append(curved);
        rejects(()->EditOperations.split(curvedProject,0,1_000_000));
        rejects(()->EditOperations.trim(curvedProject,0,true,1_000_000));
        Clip tiny=source.edit(0,50_000,SpeedMap.constant(1),false,Transform.identity());
        near(EditOperations.trim(Project.empty("短片段").append(tiny),0,true,1_000_000).visuals.get(0).durationUs(),50_000,0);
    }
    static void exportSettings() {
        ExportSettings settings=new ExportSettings(ExportSettings.Format.MP4,720,30,192);
        int[] dimensions=settings.dimensions(1920,1080);
        yes(dimensions[0]==1280 && dimensions[1]==720);
        yes(settings.frameCount(1_000_000)==30);
        yes(settings.frameTimeUs(15)==500_000);
        yes(settings.videoBitrate(1280,720)>0);
        yes("mp4".equals(settings.extension()));
        yes("video/mp4".equals(settings.mime()));
        ExportSettings mp3=new ExportSettings(ExportSettings.Format.MP3,480,24,320);
        yes("mp3".equals(mp3.extension()));
        yes("audio/mpeg".equals(mp3.mime()));
        rejects(()->new ExportSettings(ExportSettings.Format.MP4,500,30,192));
        rejects(()->new ExportSettings(ExportSettings.Format.MP4,720,29,192));
        rejects(()->new ExportSettings(ExportSettings.Format.MP4,720,30,256));
    }
    static int pcm16(byte[] pcm,int offset) { return (short)((pcm[offset]&255)|(pcm[offset+1]<<8)); }
    static void audioMix() throws Exception {
        Clip audio=Clip.create("tone.wav","tone",Clip.Kind.AUDIO,2_000_000);
        byte[] pcm=new byte[16];
        AudioMix mix=new AudioMix(); yes(mix.isEmpty());
        mix.add(audio,1_000_000,(us,ch)->ch==0?.75:-.75); yes(!mix.isEmpty());
        yes(mix.read(0,1,44100*4,pcm)==1); near(pcm16(pcm,0),0,0);
        mix.read(44100,1,44100*4,pcm); near(pcm16(pcm,0),24575,0); near(pcm16(pcm,2),-24575,0);
        mix.add(audio,1_000_000,(us,ch)->ch==0?.75:-.75);
        mix.read(44100,1,44100*4,pcm); near(pcm16(pcm,0),32767,0); near(pcm16(pcm,2),-32767,0);
        mix.read(44100*3,1,44100*4,pcm); near(pcm16(pcm,0),0,0);
        yes(mix.read(9,4,10,pcm)==1); yes(mix.read(10,4,10,pcm)==0);
        near(AudioMix.sampleCount(1_000_001),44101,0);
        for(boolean reverse:new boolean[]{false,true}) {
            AudioMix mapped=new AudioMix();
            mapped.add(audio.edit(500_000,1_500_000,SpeedMap.constant(2),reverse,Transform.identity()),0,(us,ch)->us/2_000_000.0);
            mapped.read(4410,1,22050,pcm);
            near(pcm16(pcm,0),Math.round((reverse?.65:.35)*32767),1);
            mapped.read(22050,1,44100,pcm); near(pcm16(pcm,0),0,0);
        }
        int[] portrait=new ExportSettings(ExportSettings.Format.MP4,1080,60,192).dimensions(1080,1920);
        yes(portrait[0]==1080 && portrait[1]==1920);
        for(int fps:new int[]{24,25,30,60}) {
            ExportSettings s=new ExportSettings(ExportSettings.Format.MP4,480,fps,128);
            near(s.frameCount(1_000_001),fps+1,0);
            near(s.frameTimeUs(fps),1_000_000,0);
        }
    }
    public static void main(String[] args) throws Exception {
        audioMix();
        exportSettings();
        SpeedMap normal=SpeedMap.constant(2);
        near(normal.durationUs(10_000_000),5_000_000,0);
        near(normal.sourceFraction(1_000_000,10_000_000,false),0.2,1e-9);
        near(normal.sourceFraction(1_000_000,10_000_000,true),0.8,1e-9);
        SpeedMap ramp=new SpeedMap(new double[]{0,1},new double[]{1,2});
        near(ramp.durationUs(10_000_000),10_000_000*Math.log(2),1);
        near(ramp.sourceFraction(2_000_000,10_000_000,false),Math.expm1(.2),1e-9);
        near(ramp.sourceFraction(2_000_000,10_000_000,true),2*Math.exp(-.2)-1,1e-9);
        SpeedMap down=new SpeedMap(new double[]{0,1},new double[]{2,1});
        near(down.durationUs(10_000_000),ramp.durationUs(10_000_000),0);
        SpeedMap curve=new SpeedMap(new double[]{0,.25,.6,1},new double[]{.1,4,.5,16});
        // Compare analytic integral against independent midpoint quadrature.
        double integrated=0; int steps=100000;
        for(int i=0;i<steps;i++) integrated+=1/curve.rateAt((i+.5)/steps)/steps;
        near(curve.durationUs(10_000_000),integrated*10_000_000,2);
        double previous=-1,back=2;
        for(int i=0;i<=1000;i++) {
            long time=Math.round(curve.durationUs(10_000_000)*i/1000.0);
            double f=curve.sourceFraction(time,10_000_000,false),r=curve.sourceFraction(time,10_000_000,true);
            yes(f>=previous && r<=back); previous=f; back=r;
        }
        near(previous,1,0); near(back,0,0);
        rejects(()->SpeedMap.constant(0)); rejects(()->SpeedMap.constant(Double.NaN)); rejects(()->SpeedMap.constant(Double.POSITIVE_INFINITY));
        rejects(()->new SpeedMap(new double[]{0,.5,.5,1},new double[]{1,1,1,1}));
        rejects(()->new SpeedMap(new double[]{.1,1},new double[]{1,1}));
        double[] p={0,1},r={1,1}; SpeedMap immutable=new SpeedMap(p,r); p[0]=1; r[0]=0; immutable.rates()[0]=0;
        near(immutable.durationUs(1000000),1000000,0);
        Clip c=Clip.create("/local/video.mp4","video",Clip.Kind.VIDEO,10000000);
        Clip edited=c.edit(2000000,8000000,normal,true,new Transform(2,90,.1,-.1));
        near(edited.durationUs(),3000000,0); near(edited.sourceUs(0),7999999,0); near(edited.sourceUs(3000000),2000000,0);
        rejects(()->c.edit(5,5,normal,false,Transform.identity()));
        Clip image=Clip.create("/local/image.png","image",Clip.Kind.IMAGE,5000000);
        Clip audio=Clip.create("/local/sound.wav","sound",Clip.Kind.AUDIO,20000000);
        editOperations(c,image,audio);
        Project project=Project.empty("测试草稿").append(edited).append(image).append(audio);
        near(project.durationUs(),20000000,0); yes(project.locate(2999999).clip.id.equals(c.id));
        yes(project.locate(3000000).clip.id.equals(image.id)); yes(project.locate(8000000)==null);
        yes(project.locate(20000000)==null); rejects(()->project.locate(-1));
        Project moved=project.move(0,1); yes(moved.visuals.get(0).id.equals(image.id)); yes(project.visuals.get(0).id.equals(c.id));
        rejects(()->project.append(c));
        double[] mapped=edited.transform.map(1,0,100,100); near(mapped[0],10,1e-9); near(mapped[1],-8,1e-9);
        rejects(()->new Transform(Double.NaN,0,0,0));
        Path folder=Paths.get(args[0]); Files.createDirectories(folder); Path file=folder.resolve("roundtrip.oep");
        ProjectStore store=new ProjectStore(); store.save(file,project); Project loaded=store.load(file);
        yes(loaded.id.equals(project.id)); yes(loaded.title.equals("测试草稿")); yes(loaded.visuals.get(0).reverse);
        near(loaded.visuals.get(0).transform.rotationDegrees,90,0); near(loaded.visuals.get(0).speed.rates()[0],2,0);
        near(loaded.durationUs(),project.durationUs(),0); yes(loaded.audio.get(0).clip.path.equals(audio.path));
        store.save(file,moved); yes(store.load(file).visuals.get(0).id.equals(image.id));
        byte[] good=Files.readAllBytes(file); Path corrupt=folder.resolve("corrupt.oep");
        Files.write(corrupt,Arrays.copyOf(good,good.length/2));
        try { store.load(corrupt); throw new AssertionError("Accepted truncated draft"); } catch(java.io.IOException expected) { assertions++; }
        good[7]=99; Files.write(corrupt,good);
        try { store.load(corrupt); throw new AssertionError("Accepted future version"); } catch(java.io.IOException expected) { assertions++; }
        System.out.println("PASS: "+assertions+" assertions; speed, curve integration, reverse mapping, trims, timeline, transforms, draft IO.");
    }
}
