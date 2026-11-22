package com.eggman.backstage.companion;
import com.eggman.backstage.companion.CompanionEvent;

oneway interface IAgentCallback {
    void onStateChanged(int status);
    void onEvent(in CompanionEvent event);
    void onGestureResult(long requestId, boolean completed);
}
