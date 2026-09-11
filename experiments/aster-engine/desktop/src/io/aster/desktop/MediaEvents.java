package io.aster.desktop;

import java.util.*;

/** One script session's bounded media mailbox; retired players cannot refill it. */
final class MediaEvents implements AutoCloseable {
    private static final class Event {
        final String code;final boolean nativeEvent;
        Event(String code,boolean nativeEvent){this.code=code;this.nativeEvent=nativeEvent;}
    }
    final class Source {
        private final long generation;
        private Source(long generation){this.generation=generation;}
        boolean active(){synchronized(MediaEvents.this){return !closed&&generation==current;}}
        void post(String event,String code){
            synchronized(MediaEvents.this){
                if(closed||generation!=current)return;
                if(event.equals("timeupdate"))time=code;
                else {time=null;if(events.size()<64)events.addLast(new Event(code,true));}
            }
        }
    }
    private final Deque<Event> events=new ArrayDeque<>();
    private long current;private String time;private boolean closed;
    synchronized Source source(){invalidateSource();return new Source(current);}
    synchronized void invalidateSource(){current++;time=null;events.removeIf(event->event.nativeEvent);}
    synchronized void reply(String code){if(!closed&&events.size()<64)events.addLast(new Event(code,false));}
    synchronized String poll(){Event event=events.pollFirst();return event==null?null:event.code;}
    synchronized String takeTime(){String result=time;time=null;return result;}
    public synchronized void close(){closed=true;current++;events.clear();time=null;}
}
