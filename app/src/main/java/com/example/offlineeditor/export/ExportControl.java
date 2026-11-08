package com.example.offlineeditor.export;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExportControl {
    public interface Listener { void update(String stage,int percent); }
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final Listener listener;
    private long lastUpdate;
    public ExportControl(Listener listener) { this.listener=listener; }
    public void cancel() { cancelled.set(true); }
    public void check() { if(cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("导出已取消"); }
    public void progress(String stage,int percent) {
        check(); long now=System.nanoTime();
        if(now-lastUpdate>150000000L || percent==100) { lastUpdate=now; listener.update(stage,percent); }
    }
    public void space(File dir) throws IOException {
        check(); if(dir.getUsableSpace()<64L*1024*1024) throw new IOException("存储空间不足，请至少保留 64 MB 空闲空间");
    }
}