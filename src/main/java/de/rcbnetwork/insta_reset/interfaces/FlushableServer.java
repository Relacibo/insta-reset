package de.rcbnetwork.insta_reset.interfaces;

public interface FlushableServer {
    boolean shouldFlush();
    Object getFlushLock();
    void setShouldFlush(boolean shouldFlush);
}
