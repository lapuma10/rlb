package net.runelite.client.sequence.views;

public interface EventFacts {
    int lastInventoryChangeTick();
    int lastBankContainerChangeTick();
    int lastBlockingInterfaceChangeTick();
    int lastPlayerAnimationChangeTick();

    static EventFacts none() {
        return new EventFacts() {
            @Override public int lastInventoryChangeTick()          { return -1; }
            @Override public int lastBankContainerChangeTick()      { return -1; }
            @Override public int lastBlockingInterfaceChangeTick()  { return -1; }
            @Override public int lastPlayerAnimationChangeTick()    { return -1; }
        };
    }
}
