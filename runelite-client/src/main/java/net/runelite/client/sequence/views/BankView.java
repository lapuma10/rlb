package net.runelite.client.sequence.views;

public interface BankView {
    boolean open();
    boolean ready();
    boolean pinUp();
    BankItemAvailability availability(int itemId);

    static BankView empty() {
        return new BankView() {
            @Override public boolean open()                              { return false; }
            @Override public boolean ready()                             { return false; }
            @Override public boolean pinUp()                             { return false; }
            @Override public BankItemAvailability availability(int id)   { return BankItemAvailability.unknown(); }
        };
    }
}
