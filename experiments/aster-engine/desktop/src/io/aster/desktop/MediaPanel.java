package io.aster.desktop;

import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.*;
import javafx.application.Platform;
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

/** Native, unencrypted file playback only. JavaFX Media is used without javafx.web. */
final class MediaPanel extends JPanel implements AutoCloseable {
    interface Source { Path load() throws Exception; }
    private static volatile boolean initialized;
    private final JFXPanel video=new JFXPanel();
    private final JLabel status=new JLabel("Loading media…");
    private final JButton pause=new JButton("Pause"),restart=new JButton("Restart");
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"aster-media-fetch");t.setDaemon(false);return t;});
    private volatile boolean closed;
    private volatile Path file; private MediaPlayer player; private MediaView view;
    private volatile Path evidence; private volatile Runnable passed; private int firstColor; private boolean gotFirst,verified;
    private volatile java.util.function.Consumer<String> failed;
    MediaPanel(Source source,Runnable back) {
        initialized=true;Platform.setImplicitExit(false);setLayout(new BorderLayout(8,8));setBackground(new Color(0xf7faf8));
        setBorder(BorderFactory.createEmptyBorder(18,24,24,24));setPreferredSize(new Dimension(800,530));
        status.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,14));add(status,BorderLayout.NORTH);video.setPreferredSize(new Dimension(640,360));add(video,BorderLayout.CENTER);
        JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT,8,4));controls.setOpaque(false);
        pause.setEnabled(false);restart.setEnabled(false);controls.add(pause);controls.add(restart);
        JSlider volume=new JSlider(0,100,50);volume.setPreferredSize(new Dimension(120,28));volume.setToolTipText("Volume");volume.getAccessibleContext().setAccessibleName("Media volume");controls.add(new JLabel("Volume"));controls.add(volume);
        JButton done=new JButton("Close player");done.addActionListener(e->{close();back.run();});controls.add(done);add(controls,BorderLayout.SOUTH);
        pause.addActionListener(e->Platform.runLater(()->{if(player==null)return;if(player.getStatus()==MediaPlayer.Status.PLAYING)player.pause();else player.play();}));
        restart.addActionListener(e->Platform.runLater(()->{if(player!=null){player.seek(Duration.ZERO);player.play();}}));
        volume.addChangeListener(e->{double value=volume.getValue()/100.0;Platform.runLater(()->{if(player!=null)player.setVolume(value);});});
        worker.submit(()->{try{Path downloaded=source.load();file=downloaded;if(closed){delete(downloaded);return;}Platform.runLater(()->{if(closed){delete(downloaded);return;}prepare();});}catch(Exception e){error(e.toString());}finally{worker.shutdown();}});
    }
    private void prepare() {
        try {
            Media media=new Media(file.toUri().toString());player=new MediaPlayer(media);view=new MediaView(player);view.setPreserveRatio(true);
            StackPane root=new StackPane(view);root.setStyle("-fx-background-color: #112622;");view.fitWidthProperty().bind(root.widthProperty());view.fitHeightProperty().bind(root.heightProperty());video.setScene(new Scene(root,640,360));
            media.setOnError(()->error(String.valueOf(media.getError())));player.setOnError(()->error(String.valueOf(player.getError())));player.setOnHalted(()->error("Media decoder halted"));player.setVolume(.5);
            player.setOnReady(()->{SwingUtilities.invokeLater(()->{pause.setEnabled(true);restart.setEnabled(true);});player.play();});
            player.statusProperty().addListener((o,a,b)->SwingUtilities.invokeLater(()->pause.setText(b==MediaPlayer.Status.PLAYING?"Pause":"Play")));
            player.currentTimeProperty().addListener((o,a,b)->{
                double seconds=b.toSeconds(), duration=player.getTotalDuration().toSeconds();
                SwingUtilities.invokeLater(()->status.setText(String.format(java.util.Locale.ROOT,"%.1f / %.1f seconds · Unencrypted media preview",seconds,duration)));
                if(evidence!=null && !verified && seconds>.25 && media.getWidth()>0 && media.getHeight()>0) verifyFrame(seconds,media);
            });
            player.setOnEndOfMedia(()->{if(evidence!=null&&!verified)error("Clip ended without two decoded video frames");});
        }catch(Exception e){error(e.toString());}
    }
    private void verifyFrame(double seconds,Media media) {
        try {
            WritableImage image=view.snapshot(null,null);int rgb=image.getPixelReader().getArgb((int)image.getWidth()/2,(int)image.getHeight()/2);
            if(!gotFirst && seconds<.7) { firstColor=rgb;gotFirst=true; }
            if(gotFirst && seconds>1.1) {
                if(media.getWidth()!=160 || media.getHeight()!=90 || (firstColor&255)<100 || ((rgb>>16)&255)<100 || firstColor==rgb)throw new AssertionError("Decoded video did not change from blue to red");
                verified=true;ImageIO.write(SwingFXUtils.fromFXImage(image,null),"png",evidence.toFile());
                System.out.println("Native media passed: H.264 MP4, 160 x 90, advancing playback time and two actual blue/red decoded frames. No DRM or physical speaker test.");
                SwingUtilities.invokeLater(passed);
            }
        }catch(Throwable e){error(e.toString());}
    }
    void evidence(Path image,Runnable done,java.util.function.Consumer<String> failure) { evidence=image;passed=done;failed=failure; }
    private void error(String message) { SwingUtilities.invokeLater(()->{if(closed)return;status.setText("Could not play this media: "+message);pause.setEnabled(false);if(failed!=null)failed.accept(message);}); }
    static Path sample() throws Exception { Path path=Files.createTempFile("aster-sample-",".mp4");try{Files.write(path,Base64.getMimeDecoder().decode(PreviewMain.resourceText("/sample.mp4.b64")));return path;}catch(Exception e){delete(path);throw e;} }
    private static void delete(Path path) { if(path!=null)try{Files.deleteIfExists(path);}catch(Exception e){path.toFile().deleteOnExit();} }
    public void close() { if(closed)return;closed=true;worker.shutdownNow();Platform.runLater(()->{if(player!=null){player.dispose();player=null;}video.setScene(null);delete(file);file=null;}); }
    static void shutdown() { if(initialized)Platform.runLater(Platform::exit); }
}
