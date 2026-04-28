package net.runelite.client.sequence.login;

import java.util.concurrent.atomic.AtomicReference;

/**
 * One-slot snapshot of the most recent {@code resolveUsername} comparison so the
 * debug overlay can render what the FSM actually saw vs. what the user thought
 * was in the field. Pure data — no client/UI deps so it stays callable from
 * the login package without dragging in recorder/.
 */
public final class LoginDebugBus
{
    public static final class Snapshot
    {
        public final long timestampMs;
        public final String current;        // trimmed value of client.getUsername()
        public final String target;         // trimmed credential username
        public final String decision;       // human-readable next-state reason

        public Snapshot(long timestampMs, String current, String target, String decision)
        {
            this.timestampMs = timestampMs;
            this.current = current;
            this.target = target;
            this.decision = decision;
        }
    }

    private static final AtomicReference<Snapshot> latest = new AtomicReference<>();

    private LoginDebugBus() {}

    public static void publish(String current, String target, String decision)
    {
        latest.set(new Snapshot(System.currentTimeMillis(), current, target, decision));
    }

    public static Snapshot latest() { return latest.get(); }

    /**
     * Per-codepoint hex dump for diagnosing invisible chars in either side of
     * the comparison. Format: {@code [len=N] U+0061(a) U+0062(b) ...} with
     * non-printable codepoints shown as {@code U+00A0(?)}.
     */
    public static String hexDump(String s)
    {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("[len=").append(s.length()).append("]");
        for (int i = 0; i < s.length(); )
        {
            int cp = s.codePointAt(i);
            sb.append(' ');
            char display = (cp >= 0x20 && cp < 0x7F) ? (char) cp : '?';
            sb.append(String.format("U+%04X(%c)", cp, display));
            i += Character.charCount(cp);
        }
        return sb.toString();
    }
}
