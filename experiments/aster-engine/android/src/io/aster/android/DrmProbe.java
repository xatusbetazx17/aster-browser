package io.aster.android;

import android.media.MediaDrm;
import java.util.UUID;

/** Read-only device capability query, not an EME implementation or a playback claim. */
public final class DrmProbe {
    private static final UUID WIDEVINE = new UUID(0xedef8ba979d64aceL, 0xa3c827dcd51d21edL);
    public static String report() {
        String device;
        try {
            if (!MediaDrm.isCryptoSchemeSupported(WIDEVINE, "video/mp4")) device = "Device Widevine: unavailable.";
            else {
                MediaDrm drm = new MediaDrm(WIDEVINE);
                try { device = "Device Widevine: present (" + drm.getPropertyString("securityLevel") + ")."; }
                finally { drm.release(); }
            }
        } catch (Exception e) { device = "Device Widevine: could not query (" + e.getClass().getSimpleName() + ")."; }
        return device + "\n\nAster's original engine has no JavaScript, EME, video player or WebRTC yet. "
            + "Device DRM availability does not make Prime Video or cloud gaming work in Aster. "
            + "This check requests no keys or licenses and sends no device information to a server.";
    }
    private DrmProbe() { }
}
