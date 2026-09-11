package io.aster.desktop;

import java.lang.reflect.*;
import java.net.*;
import java.util.concurrent.*;

/** Exercise the shipped Java HLS reader and deterministic clock/length edge cases. */
public final class HlsDecoderTests {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Method method(Class<?> type,String name,Class<?>...parameters)throws Exception{Method m=type.getDeclaredMethod(name,parameters);m.setAccessible(true);return m;}
    public static void main(String[] args)throws Exception{
        try(StreamSmoke.Fixture source=new StreamSmoke.Fixture();MediaRelay relay=new MediaRelay(source.uri(),source.uri().resolve("master.m3u8"))){
            Class<?> type=Class.forName("com.sun.media.jfxmedia.locator.HLSConnectionHolder");
            Constructor<?> constructor=type.getDeclaredConstructor(URI.class);constructor.setAccessible(true);Object holder=constructor.newInstance(relay.uri());
            try{
                check(((CountDownLatch)field(type,"readySignal").get(holder)).await(10,TimeUnit.SECONDS),"HLS manifest reader did not initialize");
                Field connection=field(type,"urlConnection"),playlist=field(type,"currentPlaylist");
                Object original=playlist.get(holder);check(original!=null,"HLS variant was not selected");
                int[] size={Integer.MAX_VALUE};
                connection.set(holder,new URLConnection(new URL("http://127.0.0.1/measurement")){
                    public void connect(){}public int getContentLength(){return size[0];}
                });
                Method adjust=method(type,"adjustBitrate",long.class);
                // Real loopback/cache reads can take zero wall-clock milliseconds.
                adjust.invoke(holder,0L);adjust.invoke(holder,-1L);
                check(playlist.get(holder)==original,"Invalid clock sample changed variants");
                size[0]=-1;adjust.invoke(holder,1L);check(playlist.get(holder)==original,"Unknown length changed variants");
                size[0]=Integer.MAX_VALUE;adjust.invoke(holder,1L);
                Object variants=field(type,"variantPlaylist").get(holder);
                Object fastest=method(variants.getClass(),"getPlaylistBasedOnBitrate",int.class).invoke(variants,Integer.MAX_VALUE);
                check(playlist.get(holder)==fastest,"Very fast reads overflowed bitrate selection");
                connection.set(holder,null);
                Method load=method(type,"property",int.class,int.class),read=method(type,"readNextBlock");
                int bytes=0,segments=0;
                while(segments<3){int length=(Integer)load.invoke(holder,4,0);if(length==-1)break;segments++;int block;while((block=(Integer)read.invoke(holder))!=-1)bytes+=block;}
                check(segments==2&&bytes>2000&&source.requested.size()==2,"Shipped HLS reader did not consume both actual segments");
            }finally{method(type,"closeConnection").invoke(holder);}
        }
        System.out.println("HLS decoder passed: zero/negative timing, unknown length, saturated bitrate selection and two real MPEG-TS segments through the shipped reader.");
    }
}
