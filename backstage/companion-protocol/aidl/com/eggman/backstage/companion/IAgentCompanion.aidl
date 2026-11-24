package com.eggman.backstage.companion;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.accessibility.AccessibilityWindowInfo;
import com.eggman.backstage.companion.IAgentCallback;

interface IAgentCompanion {
    int protocolVersion();
    int status();
    void subscribe(IAgentCallback callback);
    void unsubscribe(IAgentCallback callback);
    String[] capabilityGaps();
    int[] trackedDisplays();
    List<AccessibilityWindowInfo> windowsOn(int displayId);
    // Only file descriptors cross Binder. Trees and JPEG screenshots are bounded byte streams written on IO workers.
    @nullable ParcelFileDescriptor capture(int displayId, int width, int height);
    @nullable ParcelFileDescriptor screenshot(int displayId);
    boolean nodeAction(long handle, int action, int displayId, @nullable String text);
    boolean imeEnter(int displayId);
    boolean globalAction(int action);
    void gesture(long requestId, int displayId, in float[] points, long durationMs);
    // Keep transaction indices stable for the methods above.
    Bundle metadata();
    Bundle privilegedRequest(in Bundle request);
    boolean updateAccessibilitySettings(in Bundle settings);
    Bundle ompRequest(in Bundle request);
}
