package io.aster.desktop;

import java.net.URI;
import io.aster.engine.SiteData;
import java.nio.file.*;

/** An explicitly granted media source with a lifetime tied to its player. */
final class MediaResource implements AutoCloseable {
    final URI uri;private final AutoCloseable cleanup;
    private MediaResource(URI uri,AutoCloseable cleanup){this.uri=uri;this.cleanup=cleanup;}
    static MediaResource file(Path file){return new MediaResource(file.toUri(),()->{try{Files.deleteIfExists(file);}catch(Exception e){file.toFile().deleteOnExit();}});}
    static MediaResource remote(URI page,URI uri)throws Exception{return remote(page,uri,new SiteData());}
    static MediaResource remote(URI page,URI uri,SiteData data)throws Exception{MediaRelay relay=new MediaRelay(page,uri,data);return new MediaResource(relay.uri(),relay);}
    public void close(){try{cleanup.close();}catch(Exception ignored){}}
}
