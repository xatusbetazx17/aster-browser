package io.aster.desktop;

import io.aster.engine.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.swing.SwingUtilities;

/** Offline, reproducible renderer measurements; not a browser/service benchmark. */
final class EngineBenchmark {
    private static final int WARMUP=15,SAMPLES=40;
    private static volatile Object sink;
    private interface Task{void run();}
    private static Map<String,Object> time(Task task){
        for(int i=0;i<WARMUP;i++)task.run();double[] samples=new double[SAMPLES];
        for(int i=0;i<SAMPLES;i++){long began=System.nanoTime();task.run();samples[i]=(System.nanoTime()-began)/1_000_000.0;}
        double[] sorted=samples.clone();Arrays.sort(sorted);Map<String,Object> result=new LinkedHashMap<>();
        result.put("p50_ms",sorted[SAMPLES/2]);result.put("p95_ms",sorted[(int)Math.ceil(SAMPLES*.95)-1]);java.util.List<Double> raw=new ArrayList<>();for(double value:samples)raw.add(value);result.put("samples_ms",raw);return result;
    }
    private static Map<String,Object> stats(double[] samples){
        double[] sorted=samples.clone();Arrays.sort(sorted);Map<String,Object> out=new LinkedHashMap<>();out.put("p50_ms",sorted[SAMPLES/2]);out.put("p95_ms",sorted[(int)Math.ceil(SAMPLES*.95)-1]);java.util.List<Double> raw=new ArrayList<>();for(double v:samples)raw.add(v);out.put("samples_ms",raw);return out;
    }
    private static void pair(Map<String,Object> output,Task baseline,Task cached){
        for(int i=0;i<WARMUP;i++){baseline.run();cached.run();}double[][] values=new double[2][SAMPLES];Task[] tasks={baseline,cached};
        for(int i=0;i<SAMPLES;i++)for(int j=0;j<2;j++){int mode=(i+j)%2;long start=System.nanoTime();tasks[mode].run();values[mode][i]=(System.nanoTime()-start)/1_000_000.0;}
        output.put("layout_uncached",stats(values[0]));output.put("layout_cached",stats(values[1]));
    }
    static void verifySame(Engine.Layout a,Engine.Layout b){
        if(a.height!=b.height||a.items.size()!=b.items.size())throw new AssertionError("Cached layout changed geometry");
        for(int i=0;i<a.items.size();i++){Engine.Draw x=a.items.get(i),y=b.items.get(i);if(!x.text.equals(y.text)||x.x!=y.x||x.y!=y.y||x.width!=y.width||x.height!=y.height||x.action!=y.action||!Objects.equals(x.link,y.link)||!Objects.equals(x.image,y.image))throw new AssertionError("Cached layout changed a draw");}
    }
    static void run(Path output)throws Exception{
        if(!GraphicsEnvironment.isHeadless())throw new IllegalArgumentException("Run renderer measurements with -Djava.awt.headless=true");
        Map<String,String> fixtures=new LinkedHashMap<>();fixtures.put("article","<h1>Read with Aster</h1>"+("<p>Reading the same words across a long article should not require measuring every repeated word again. <a href='/next'>Continue reading</a> with <b>clear type</b> and room to explore.</p>").repeat(100));
        fixtures.put("mixed-script","<h1>Hola 🌎</h1>"+("<p>Español: información y navegación. Ελληνικά 中文 العربية é.</p><pre>one\ttwo\n🚀🚀🚀 narrow text</pre>").repeat(80));
        fixtures.put("many-links","<h1>Useful links</h1>"+("<p><a href='/one'>Read</a> <a href='/two'>Save</a> <a href='/three'>Explore</a></p>").repeat(250));
        Map<String,Object> report=new LinkedHashMap<>();report.put("schema",1);report.put("scope","Offline Aster parser, layout and Java2D viewport paint; no network, JavaScript, DRM, startup, process RSS or competing browser measurements");
        report.put("commit",System.getenv().getOrDefault("ASTER_BUILD_COMMIT","local"));report.put("java",System.getProperty("java.version"));report.put("os",System.getProperty("os.name"));report.put("os_version",System.getProperty("os.version"));report.put("arch",System.getProperty("os.arch"));report.put("processors",Runtime.getRuntime().availableProcessors());report.put("warmup_iterations",WARMUP);report.put("measured_iterations",SAMPLES);
        java.util.List<Object> results=new ArrayList<>();URI uri=URI.create("https://benchmark.example.test/");
        SwingUtilities.invokeAndWait(()->{
            for(Map.Entry<String,String> fixture:fixtures.entrySet())for(int width:new int[]{720,1280}){
                String source=fixture.getValue();Engine.Document document=Engine.parse(uri,source);PreviewMain.PageCanvas canvas=new PreviewMain.PageCanvas();canvas.setSize(width,800);canvas.setDocument(document);canvas.ensureLayout();
                Map<Engine.Style,FontMetrics> fonts=new IdentityHashMap<>();Engine.Measure nativeWidth=(text,style)->fonts.computeIfAbsent(style,s->canvas.getFontMetrics(PreviewMain.PageCanvas.font(s))).stringWidth(text);
                Engine.Layout cached=Engine.layout(document,width,nativeWidth,true),baseline=Engine.layout(document,width,nativeWidth,false);verifySame(cached,baseline);
                int[] calls={0};Engine.Measure counted=(text,style)->{calls[0]++;return nativeWidth.width(text,style);};Engine.layout(document,width,counted,false);int oldCalls=calls[0];calls[0]=0;Engine.layout(document,width,counted,true);int newCalls=calls[0];
                BufferedImage pixels=new BufferedImage(width,800,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=pixels.createGraphics();
                Map<String,Object> result=new LinkedHashMap<>();result.put("fixture",fixture.getKey());result.put("viewport",Arrays.asList(width,800));result.put("source_bytes",source.getBytes(StandardCharsets.UTF_8).length);result.put("source_sha256",sha(source));result.put("draws",cached.items.size());result.put("layout_height",cached.height);result.put("identical_geometry",true);result.put("width_calls_uncached",oldCalls);result.put("width_calls_cached",newCalls);
                result.put("parse",time(()->sink=Engine.parse(uri,source)));pair(result,()->sink=Engine.layout(document,width,nativeWidth,false),()->sink=Engine.layout(document,width,nativeWidth,true));result.put("paint_top_viewport",time(()->canvas.paint(graphics)));graphics.dispose();canvas.release();results.add(result);
            }
        });report.put("results",results);Files.writeString(output,Json.stringify(report)+"\n");System.out.println("Renderer benchmark written: "+output+"; six fixture/viewport pairs, cached and uncached geometry identical. No cross-browser speed claim.");
    }
    private static String sha(String source){try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:hash)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
