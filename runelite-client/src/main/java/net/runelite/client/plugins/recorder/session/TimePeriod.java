package net.runelite.client.plugins.recorder.session;

public enum TimePeriod {
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    ALL_TIME("All-Time");

    private final String label;

    TimePeriod(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
