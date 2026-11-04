package com.example.offlineeditor.core;

import java.util.UUID;

public final class Clip {
    public enum Kind { VIDEO, IMAGE, AUDIO }
    public final String id, path, name;
    public final Kind kind;
    public final long sourceDurationUs, inUs, outUs;
    public final SpeedMap speed;
    public final boolean reverse;
    public final Transform transform;
    public Clip(String id, String path, String name, Kind kind, long sourceDurationUs, long inUs, long outUs,
                SpeedMap speed, boolean reverse, Transform transform) {
        if (id == null || id.isEmpty() || path == null || path.isEmpty() || name == null || kind == null ||
            speed == null || transform == null || inUs < 0 || outUs <= inUs || outUs > sourceDurationUs ||
            sourceDurationUs > 86_400_000_000L) throw new IllegalArgumentException("Invalid clip");
        this.id=id; this.path=path; this.name=name; this.kind=kind; this.sourceDurationUs=sourceDurationUs;
        this.inUs=inUs; this.outUs=outUs; this.speed=speed; this.reverse=reverse; this.transform=transform;
    }
    public static Clip create(String path, String name, Kind kind, long durationUs) {
        return new Clip(UUID.randomUUID().toString(),path,name,kind,durationUs,0,durationUs,SpeedMap.constant(1),false,Transform.identity());
    }
    public Clip edit(long inUs, long outUs, SpeedMap speed, boolean reverse, Transform transform) {
        return new Clip(id,path,name,kind,sourceDurationUs,inUs,outUs,speed,reverse,transform);
    }
    public long durationUs() { return speed.durationUs(outUs-inUs); }
    public long sourceUs(long outputUs) {
        long mapped = inUs + Math.round(speed.sourceFraction(outputUs,outUs-inUs,reverse)*(outUs-inUs));
        // Source intervals are half-open. Never seek to the exclusive end.
        return Math.max(inUs, Math.min(outUs-1,mapped));
    }
}
