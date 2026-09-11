package io.aster.desktop;

import java.util.concurrent.*;

/** Delayed decoder callbacks must never restore a retired timeline. */
public final class MediaEventsTests {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        MediaEvents page=new MediaEvents();MediaEvents.Source first=page.source();
        first.post("timeupdate","old-time");first.post("ended","old-end");page.reply("denied-promise");
        CountDownLatch ready=new CountDownLatch(1),deliver=new CountDownLatch(1);
        Thread decoder=new Thread(()->{ready.countDown();try{deliver.await();first.post("timeupdate","late-time");first.post("ended","late-end");}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        decoder.setDaemon(true);decoder.start();check(ready.await(2,TimeUnit.SECONDS),"Decoder fixture did not start");
        MediaEvents.Source second=page.source();second.post("loadedmetadata","new-metadata");second.post("timeupdate","new-time");
        deliver.countDown();decoder.join(2000);check(!decoder.isAlive(),"Decoder fixture did not finish");
        check("denied-promise".equals(page.poll())&&"new-metadata".equals(page.poll())&&page.poll()==null,"Retired native events survived source replacement or a control reply was lost");
        check("new-time".equals(page.takeTime())&&page.takeTime()==null,"Late player replaced the current timeline");
        page.invalidateSource();second.post("error","late-error");check(page.poll()==null,"Unloaded player delivered an error");
        page.close();second.post("timeupdate","closed-time");page.reply("closed-reply");check(page.poll()==null&&page.takeTime()==null,"Closed session accepted events");
        try(ScriptSession old=new ScriptSession();ScriptSession next=new ScriptSession()){
            MediaEvents.Source retired=old.mediaEvents.source();old.close();retired.post("ended","previous-page");next.mediaEvents.reply("current-page");
            check(old.mediaEvents.poll()==null&&"current-page".equals(next.mediaEvents.poll()),"Page sessions shared a media mailbox");
        }
        MediaEvents bounded=new MediaEvents();MediaEvents.Source source=bounded.source();for(int i=0;i<200;i++)source.post("playing","event");int count=0;while(bounded.poll()!=null)count++;check(count==64,"Native event queue lost its bound");
        for(int i=0;i<1000;i++)source.post("timeupdate",Integer.toString(i));check("999".equals(bounded.takeTime())&&bounded.takeTime()==null,"Time updates did not coalesce");bounded.close();
        System.out.println("Media event isolation passed: deliberately delayed callbacks, source replacement/unload, separate real script sessions, teardown, bounded replies and coalesced time updates.");
    }
}
