package io.aster.desktop;

import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/** Native unencrypted playback and page controls. JavaFX Media, without javafx.web. */
final class MediaPanel extends JPanel implements AutoCloseable {
    interface Source { MediaResource load() throws Exception; }
    private static volatile boolean initialized;
    private volatile int surfaceGeneration,paintedGeneration=-1;
    private volatile boolean surfaceShowing;
    private final JFXPanel video=new JFXPanel(){
        protected void paintComponent(Graphics graphics){
            int generation=surfaceGeneration;super.paintComponent(graphics);
            surfaceShowing=isShowing()&&getWidth()>1&&getHeight()>1;
            if(surfaceShowing&&generation==surfaceGeneration)paintedGeneration=generation;
        }
    };
    private final JLabel status=new JLabel("Loading media…");
    private final JButton pause=new JButton("Pause"),restart=new JButton("Restart");
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"aster-media-fetch");t.setDaemon(false);return t;});
    private volatile boolean closed;
    private volatile MediaResource resource; private MediaPlayer player; private MediaView view;
    private final java.util.function.BiConsumer<String,java.util.Map<String,Object>> listener;
    private boolean ended,wantPlay=true,desiredMuted,hls,ready;private int readyPulses;
    private double desiredVolume=.5,pendingSeek=Double.NaN;private long playDeadline,lastPlayAttempt;
    private volatile Path evidence; private volatile Runnable passed; private int firstColor,lastColor,samples; private double firstTime,lastTime; private boolean gotFirst,verified;
    private AnimationTimer monitor;
    private boolean capturePending;private int endingCaptures;private long playbackStarted;
    private final javax.swing.Timer startupTimeout=new javax.swing.Timer(30000,e->error("The media decoder did not become ready within 30 seconds. Check this stream and the audio output device."));
    private volatile java.util.function.Consumer<String> failed;
    MediaPanel(Source source,Runnable back) {this(source,back,null);}
    MediaPanel(Source source,Runnable back,java.util.function.BiConsumer<String,java.util.Map<String,Object>> listener) {
        this.listener=listener;
        startupTimeout.setRepeats(false);startupTimeout.start();
        initialized=true;Platform.setImplicitExit(false);setLayout(new BorderLayout(8,8));setBackground(new Color(0xf7faf8));
        setBorder(BorderFactory.createEmptyBorder(18,24,24,24));setPreferredSize(new Dimension(800,530));
        status.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,14));add(status,BorderLayout.NORTH);video.setPreferredSize(new Dimension(640,360));add(video,BorderLayout.CENTER);
        JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT,8,4));controls.setOpaque(false);
        pause.setEnabled(false);restart.setEnabled(false);controls.add(pause);controls.add(restart);
        JSlider volume=new JSlider(0,100,50);volume.setPreferredSize(new Dimension(120,28));volume.setToolTipText("Volume");volume.getAccessibleContext().setAccessibleName("Media volume");controls.add(new JLabel("Volume"));controls.add(volume);
        JButton done=new JButton("Close player");done.addActionListener(e->{close();back.run();});controls.add(done);add(controls,BorderLayout.SOUTH);
        pause.addActionListener(e->Platform.runLater(()->{if(player!=null)control(player.getStatus()==MediaPlayer.Status.PLAYING&&!ended?"pause":"play",null);}));
        restart.addActionListener(e->control("restart",null));
        volume.addChangeListener(e->control("volume",volume.getValue()/100.0));
        video.addHierarchyListener(e->{surfaceShowing=video.isShowing();if(surfaceShowing)video.repaint();});
        worker.submit(()->{try{MediaResource loaded=source.load();resource=loaded;if(closed){loaded.close();return;}Platform.runLater(()->{if(closed){loaded.close();return;}prepare();});}catch(Exception e){error(e.toString());}finally{worker.shutdown();}});
    }
    private void prepare() {
        try {
            hls=resource.uri.getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8");ready=false;readyPulses=0;playDeadline=0;playbackStarted=0;capturePending=false;endingCaptures=0;
            Media media=new Media(resource.uri.toString());player=new MediaPlayer(media);final MediaPlayer created=player;view=new MediaView(player);view.setPreserveRatio(true);
            StackPane root=new StackPane(view);root.setStyle("-fx-background-color: #112622;");view.fitWidthProperty().bind(root.widthProperty());view.fitHeightProperty().bind(root.heightProperty());video.setScene(new Scene(root,640,360));
            surfaceGeneration++;SwingUtilities.invokeLater(video::repaint);
            monitor=new AnimationTimer(){private long last;public void handle(long now){
                if(closed||player!=created)return;
                // JFXPanel must have presented its scene before a short clip starts playing.
                if(ready&&readyPulses<2&&surfaceShowing&&paintedGeneration==surfaceGeneration&&root.getWidth()>1&&root.getHeight()>1&&++readyPulses==2&&wantPlay){playDeadline=System.nanoTime()+3_000_000_000L;player.play();}
                // Native seeking is asynchronous. Reconcile a still-requested play after its pause transition.
                if(wantPlay&&!ended&&System.nanoTime()<playDeadline&&now-lastPlayAttempt>150_000_000L&&(player.getStatus()==MediaPlayer.Status.READY||player.getStatus()==MediaPlayer.Status.PAUSED)){lastPlayAttempt=now;player.play();}
                if(wantPlay&&!ended&&playDeadline>0&&System.nanoTime()>playDeadline&&(player.getStatus()==MediaPlayer.Status.READY||player.getStatus()==MediaPlayer.Status.PAUSED)){playDeadline=0;error("The decoder did not resume playback after seeking");}
                if(evidence!=null&&!verified&&!capturePending&&readyPulses>=2&&now-last>100_000_000L&&media.getWidth()>0&&media.getHeight()>0){last=now;captureFrame(created,media);}
            }};
            monitor.start();
            media.setOnError(()->{if(player==created)error(String.valueOf(media.getError()));});player.setOnError(()->{if(player==created)error(String.valueOf(player.getError()));});player.setOnHalted(()->{if(player==created)error("Media decoder halted");});player.setVolume(desiredVolume);player.setMute(desiredMuted);
            player.setOnReady(()->{if(closed||player!=created)return;ready=true;SwingUtilities.invokeLater(()->{startupTimeout.stop();pause.setEnabled(true);restart.setEnabled(true);});if(!Double.isNaN(pendingSeek)&&!hls){player.seek(Duration.seconds(pendingSeek));pendingSeek=Double.NaN;}report("loadedmetadata");report("canplay");});
            player.setOnPlaying(()->{if(closed||player!=created)return;ended=false;playDeadline=0;if(playbackStarted==0)playbackStarted=System.nanoTime();report("play");report("playing");});
            player.setOnPaused(()->{if(player==created&&!wantPlay)report("pause");});player.setOnStalled(()->{if(player==created)report("waiting");});
            player.volumeProperty().addListener((o,a,b)->{if(player==created)report("volumechange");});player.muteProperty().addListener((o,a,b)->{if(player==created)report("volumechange");});
            player.statusProperty().addListener((o,a,b)->{if(player==created)SwingUtilities.invokeLater(()->pause.setText(b==MediaPlayer.Status.PLAYING?"Pause":"Play"));});
            player.currentTimeProperty().addListener((o,a,b)->{
                if(closed||player!=created)return;
                double seconds=b.toSeconds(), duration=player.getTotalDuration().toSeconds();
                SwingUtilities.invokeLater(()->status.setText(String.format(java.util.Locale.ROOT,"%.1f / %.1f seconds · Unencrypted media preview",seconds,duration)));
                report("timeupdate");
            });
            // End-of-media is a decoder event, not a guarantee that its last frame
            // has reached the scene. The pulse sampler checks presented frames.
            player.setOnEndOfMedia(()->{if(closed||player!=created)return;ended=true;report("ended");});
        }catch(Exception | LinkageError e){error(e.toString());}
    }
    private void report(String event){
        if(listener==null||closed||player==null)return;java.util.Map<String,Object> state=new java.util.LinkedHashMap<>();
        state.put("currentTime",Double.isNaN(pendingSeek)?player.getCurrentTime().toSeconds():pendingSeek);state.put("duration",player.getTotalDuration().toSeconds());state.put("paused",!wantPlay||ended);state.put("ended",ended);state.put("readyState",player.getStatus()==null||player.getStatus()==MediaPlayer.Status.UNKNOWN?0:3);
        state.put("seekSupported",!hls);state.put("volume",player.getVolume());state.put("muted",player.isMute());state.put("videoWidth",player.getMedia().getWidth());state.put("videoHeight",player.getMedia().getHeight());listener.accept(event,state);
    }
    void control(String command,Object value){Platform.runLater(()->{
        if(closed)return;
        switch(command){
            case "play":wantPlay=true;if(player!=null){if(ended&&hls){restartStream();break;}if(ended){ended=false;player.seek(Duration.ZERO);}if(ready&&readyPulses>=2){playDeadline=System.nanoTime()+3_000_000_000L;player.play();if(player.getStatus()==MediaPlayer.Status.PLAYING)report("playing");}}break;
            case "restart":if(player!=null&&hls){restartStream();break;}control("seek",0);control("play",null);break;
            case "pause":wantPlay=false;if(player!=null)player.pause();else if(listener!=null)listener.accept("pause",java.util.Map.of("paused",true));break;
            case "volume":desiredVolume=Math.max(0,Math.min(1,((Number)value).doubleValue()));if(player!=null)player.setVolume(desiredVolume);break;
            case "muted":desiredMuted=Boolean.TRUE.equals(value);if(player!=null)player.setMute(desiredMuted);break;
            case "seek":{
                double target=Math.max(0,((Number)value).doubleValue());
                if(target==0&&(player==null||player.getCurrentTime().toMillis()==0)&&!ended)break;
                if(hls){error("Seeking HLS is not supported in this preview. Restart opens the stream from the beginning.");break;}
                ended=false;if(wantPlay)playDeadline=System.nanoTime()+3_000_000_000L;
                if(player!=null&&ready)player.seek(Duration.seconds(target));else pendingSeek=target;
                break;
            }
        }
    });}
    private void restartStream(){
        // Reopen the decoder; native MPEG-TS seeking can deadlock its audio/video pipeline.
        if(monitor!=null)monitor.stop();player.dispose();player=null;ended=false;wantPlay=true;pendingSeek=Double.NaN;
        SwingUtilities.invokeLater(startupTimeout::restart);prepare();
    }
    private void captureFrame(MediaPlayer created,Media media){
        capturePending=true;boolean afterEnd=ended;
        try{view.snapshot(result->{
            if(closed||player!=created)return null;
            capturePending=false;verifyFrame(created.getCurrentTime().toSeconds(),media,result.getImage());
            // Three completed post-end render cycles allow queued final frames
            // to be presented. Still fail unless both colors and time were seen.
            if(afterEnd&&!verified&&++endingCaptures>=3)error("Clip ended without two presented video frames: dimensions="+media.getWidth()+"x"+media.getHeight()+", samples="+samples+", blue="+gotFirst+", lastColor="+Integer.toHexString(lastColor)+", firstTime="+firstTime+", time="+lastTime+", wallSeconds="+(playbackStarted==0?0:(System.nanoTime()-playbackStarted)/1e9)+", surface="+surfaceShowing);
            return null;
        },null,null);}catch(Throwable e){capturePending=false;error(e.toString());}
    }
    private void verifyFrame(double seconds,Media media,WritableImage image) {
        try {
            int rgb=image.getPixelReader().getArgb((int)image.getWidth()/2,(int)image.getHeight()/2);
            lastColor=rgb;lastTime=seconds;samples++;
            if(!gotFirst && (rgb&255)>100 && ((rgb>>16)&255)<80) { firstColor=rgb;firstTime=seconds;gotFirst=true; }
            if(gotFirst && ((rgb>>16)&255)>100 && (rgb&255)<80 && seconds>firstTime+.1) {
                if(media.getWidth()!=160 || media.getHeight()!=90 || firstColor==rgb)throw new AssertionError("Decoded video dimensions/colors are wrong");
                verified=true;ImageIO.write(SwingFXUtils.fromFXImage(image,null),"png",evidence.toFile());
                System.out.println("Native media passed: H.264, 160 x 90, advancing playback time and two actual blue/red decoded frames. No DRM or physical speaker test.");
                SwingUtilities.invokeLater(passed);
            }
        }catch(Throwable e){error(e.toString());}
    }
    void evidence(Path image,Runnable done,java.util.function.Consumer<String> failure) { Platform.runLater(()->{if(closed)return;passed=done;failed=failure;evidence=image;gotFirst=false;verified=false;samples=0;endingCaptures=0;firstTime=0;if(monitor!=null)monitor.start();}); }
    String diagnostic()throws Exception{CompletableFuture<String> result=new CompletableFuture<>();Platform.runLater(()->result.complete("player="+(player==null?"null":player.getStatus()+", time="+player.getCurrentTime()+", duration="+player.getTotalDuration()+", dimensions="+player.getMedia().getWidth()+"x"+player.getMedia().getHeight())+", frames="+samples+", blue="+gotFirst+", color="+Integer.toHexString(lastColor)+", verified="+verified));return result.get(2,TimeUnit.SECONDS);}
    private void error(String message) { SwingUtilities.invokeLater(()->{startupTimeout.stop();if(closed)return;status.setText("Could not play this media: "+message);pause.setEnabled(false);restart.setEnabled(false);if(listener!=null)listener.accept("error",java.util.Map.of("message",message));if(failed!=null)failed.accept(message);close();}); }
    boolean usable(){return !closed;}
    static MediaResource sample() throws Exception { Path path=Files.createTempFile("aster-sample-",".mp4");try{Files.write(path,Base64.getMimeDecoder().decode(PreviewMain.resourceText("/sample.mp4.b64")));return MediaResource.file(path);}catch(Exception e){delete(path);throw e;} }
    private static void delete(Path path) { if(path!=null)try{Files.deleteIfExists(path);}catch(Exception e){path.toFile().deleteOnExit();} }
    public void close() { if(closed)return;closed=true;startupTimeout.stop();worker.shutdownNow();MediaResource loaded=resource;if(loaded!=null)loaded.close();Platform.runLater(()->{if(monitor!=null)monitor.stop();if(player!=null){player.dispose();player=null;}video.setScene(null);if(loaded!=null)loaded.close();resource=null;}); }
    static void shutdown() { if(initialized)Platform.runLater(Platform::exit); }
}
