package net.runelite.client.plugins.recorder.session;

import javax.annotation.Nullable;

public interface ScriptLifecycleListener {
    void onScriptStarted(String scriptId, String displayName);

    void onScriptStopped(String scriptId, @Nullable Integer count, @Nullable String countLabel);
}
