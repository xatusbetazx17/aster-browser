package io.aster.desktop;

import io.aster.engine.PageAssets;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Small GET-only adapter for the shared asset loader. Unlike the JDK's older
 * URLConnection it can send Origin, without enabling restricted headers globally.
 * The client has no cookie handler, authenticator or automatic redirects. */
final class AssetConnection extends HttpURLConnection {
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String,String> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private CompletableFuture<HttpResponse<byte[]>> pending;
    private HttpResponse<byte[]> response;
    private final List<Map.Entry<String,String>> fields=new ArrayList<>();
    AssetConnection(URI uri)throws IOException{super(uri.toURL());}
    public void setRequestProperty(String name,String value){if(connected)throw new IllegalStateException("Already connected");headers.put(name,value);}
    public String getRequestProperty(String name){return headers.get(name);}
    public void connect()throws IOException{
        if(response!=null)return;
        if(connected)throw new IOException("Asset connection is closed.");
        connected=true;
        try{
            long timeout=Math.max(1,(long)getConnectTimeout()+getReadTimeout());
            HttpRequest.Builder request=HttpRequest.newBuilder(url.toURI()).GET().timeout(Duration.ofMillis(timeout));headers.forEach(request::header);
            pending=CLIENT.sendAsync(request.build(),info->new LimitedBody());
            response=pending.get(timeout,TimeUnit.MILLISECONDS);
            response.headers().map().forEach((name,values)->values.forEach(value->fields.add(new AbstractMap.SimpleImmutableEntry<>(name,value))));
        }catch(InterruptedException e){Thread.currentThread().interrupt();disconnect();throw new IOException("Asset cancelled.",e);}
        catch(ExecutionException|TimeoutException|URISyntaxException|IllegalArgumentException e){disconnect();throw new IOException("Asset request failed.",e);}
    }
    public int getResponseCode()throws IOException{connect();return response.statusCode();}
    public Map<String,List<String>> getHeaderFields(){return response==null?Collections.emptyMap():response.headers().map();}
    public String getHeaderField(String name){
        if(response==null||name==null)return null;List<String> values=response.headers().allValues(name);
        return values.isEmpty()?null:String.join(", ",values);
    }
    public String getHeaderFieldKey(int index){return index>0&&index<=fields.size()?fields.get(index-1).getKey():null;}
    public String getHeaderField(int index){return index==0&&response!=null?"HTTP/1.1 "+response.statusCode():index>0&&index<=fields.size()?fields.get(index-1).getValue():null;}
    public InputStream getInputStream()throws IOException{connect();return new ByteArrayInputStream(response.body());}
    public void disconnect(){if(pending!=null)pending.cancel(true);}
    public boolean usingProxy(){return false;}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer buffer:buffers){if(buffer.remaining()>PageAssets.IMAGE_LIMIT-bytes.size()){
                    subscription.cancel();result.completeExceptionally(new IOException("Asset exceeds size limit."));return;}
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.write(chunk,0,chunk.length);}
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
