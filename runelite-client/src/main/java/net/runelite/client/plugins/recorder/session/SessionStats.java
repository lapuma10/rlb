package net.runelite.client.plugins.recorder.session;

import java.util.Map;

public record SessionStats(
    long totalLoginMs,
    long totalScriptActiveMs,
    long idleMs,
    Map<String, ScriptStats> scripts
)
{
}
