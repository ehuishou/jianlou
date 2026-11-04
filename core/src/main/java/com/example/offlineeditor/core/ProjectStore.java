package com.example.offlineeditor.core;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Versioned, bounded format. Save into a temporary file, then replace atomically. */
public final class ProjectStore {
    private static final int MAGIC=0x4f454450, VERSION=1, MAX_ITEMS=10000;
    public void save(Path path,Project project) throws IOException {
        Path parent=path.toAbsolutePath().getParent(); Files.createDirectories(parent);
        Path temp=Files.createTempFile(parent,"draft-",".tmp");
        try {
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(project.id); out.writeUTF(project.title);
                out.writeInt(project.visuals.size()); for(Clip c:project.visuals) writeClip(out,c);
                out.writeInt(project.audio.size()); for(Project.Audio a:project.audio) { out.writeLong(a.startUs); writeClip(out,a.clip); }
            }
            if(Files.size(temp)>8*1024*1024) throw new IOException("Draft exceeds 8 MiB");
            try(FileChannel f=FileChannel.open(temp,StandardOpenOption.WRITE)) { f.force(true); }
            try { Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    public Project load(Path path) throws IOException {
        if(Files.size(path)>8*1024*1024) throw new IOException("Draft too large");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION) throw new IOException("Unknown draft format/version");
            String id=in.readUTF(), title=in.readUTF();
            List<Clip> v=new ArrayList<>(); int count=count(in,MAX_ITEMS);
            for(int i=0;i<count;i++) v.add(readClip(in));
            List<Project.Audio> a=new ArrayList<>(); count=count(in,MAX_ITEMS);
            for(int i=0;i<count;i++) { long start=in.readLong(); a.add(new Project.Audio(readClip(in),start)); }
            if(in.read()!=-1) throw new IOException("Trailing draft data");
            return new Project(id,title,v,a);
        } catch(IllegalArgumentException | ArithmeticException e) { throw new IOException("Invalid draft data",e); }
    }
    private static int count(DataInputStream in,int max) throws IOException { int n=in.readInt(); if(n<0 || n>max) throw new IOException("Invalid item count"); return n; }
    private static void writeClip(DataOutputStream out,Clip c) throws IOException {
        out.writeUTF(c.id); out.writeUTF(c.path); out.writeUTF(c.name); out.writeUTF(c.kind.name());
        out.writeLong(c.sourceDurationUs); out.writeLong(c.inUs); out.writeLong(c.outUs); out.writeBoolean(c.reverse);
        out.writeDouble(c.transform.scale); out.writeDouble(c.transform.rotationDegrees); out.writeDouble(c.transform.x); out.writeDouble(c.transform.y);
        double[] p=c.speed.positions(),r=c.speed.rates(); out.writeInt(p.length);
        for(int i=0;i<p.length;i++) { out.writeDouble(p[i]); out.writeDouble(r[i]); }
    }
    private static Clip readClip(DataInputStream in) throws IOException {
        String id=in.readUTF(),path=in.readUTF(),name=in.readUTF(); Clip.Kind kind=Clip.Kind.valueOf(in.readUTF());
        long duration=in.readLong(),start=in.readLong(),end=in.readLong(); boolean reverse=in.readBoolean();
        Transform t=new Transform(in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble());
        int n=count(in,1000); double[] p=new double[n],r=new double[n];
        for(int i=0;i<n;i++) { p[i]=in.readDouble(); r[i]=in.readDouble(); }
        return new Clip(id,path,name,kind,duration,start,end,new SpeedMap(p,r),reverse,t);
    }
}
