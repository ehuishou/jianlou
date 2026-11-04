package com.example.offlineeditor.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Sequential visual track plus independent audio overlays. Immutable snapshots. */
public final class Project {
    public static final class Audio {
        public final Clip clip; public final long startUs;
        public Audio(Clip clip,long startUs) {
            if (clip == null || clip.kind != Clip.Kind.AUDIO || startUs < 0 || startUs > 86_400_000_000L)
                throw new IllegalArgumentException("Invalid audio placement");
            this.clip=clip; this.startUs=startUs;
        }
    }
    public static final class Position {
        public final int index; public final Clip clip; public final long localUs, sourceUs;
        Position(int index,Clip clip,long localUs) { this.index=index; this.clip=clip; this.localUs=localUs; this.sourceUs=clip.sourceUs(localUs); }
    }
    public final String id,title;
    public final List<Clip> visuals;
    public final List<Audio> audio;
    public Project(String id,String title,List<Clip> visuals,List<Audio> audio) {
        if (id==null || id.isEmpty() || title==null || visuals==null || audio==null) throw new IllegalArgumentException("Invalid project");
        Set<String> ids=new HashSet<>();
        for (Clip c:visuals) if(c==null || c.kind==Clip.Kind.AUDIO || !ids.add(c.id)) throw new IllegalArgumentException("Invalid visual track");
        for(Audio a:audio) if(a==null || !ids.add(a.clip.id)) throw new IllegalArgumentException("Duplicate clip");
        this.id=id; this.title=title;
        this.visuals=Collections.unmodifiableList(new ArrayList<>(visuals)); this.audio=Collections.unmodifiableList(new ArrayList<>(audio));
        if(durationUs()>86_400_000_000L) throw new IllegalArgumentException("Project exceeds 24 hours");
    }
    public static Project empty(String title) { return new Project(UUID.randomUUID().toString(),title,Collections.emptyList(),Collections.emptyList()); }
    public long durationUs() {
        long n=0; for(Clip c:visuals) n=Math.addExact(n,c.durationUs());
        for(Audio a:audio) n=Math.max(n,Math.addExact(a.startUs,a.clip.durationUs())); return n;
    }
    public Project append(Clip c) {
        List<Clip> v=new ArrayList<>(visuals); List<Audio> a=new ArrayList<>(audio);
        if(c.kind==Clip.Kind.AUDIO) a.add(new Audio(c,0)); else v.add(c);
        return new Project(id,title,v,a);
    }
    public Project replace(int index,Clip c) { List<Clip> v=new ArrayList<>(visuals); v.set(index,c); return new Project(id,title,v,audio); }
    public Project move(int from,int to) { List<Clip> v=new ArrayList<>(visuals); Clip c=v.remove(from); v.add(to,c); return new Project(id,title,v,audio); }
    public Position locate(long timeUs) {
        if(timeUs<0 || timeUs>durationUs()) throw new IllegalArgumentException("Seek outside project");
        long start=0;
        for(int i=0;i<visuals.size();i++) { Clip c=visuals.get(i); long end=start+c.durationUs();
            if(timeUs<end) return new Position(i,c,timeUs-start); start=end;
        }
        return null; // project end or audio-only region: no visual frame
    }
}
