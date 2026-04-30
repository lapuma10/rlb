package net.runelite.client.sequence.login;

/** Outcome of running a single V2 state. Mirrors {@link StateResult} for V2's enum. */
public sealed interface StateResultV2
    permits StateResultV2.Continue, StateResultV2.Done, StateResultV2.Failure
{
    record Continue(LoginStateV2 next) implements StateResultV2 {}
    record Done() implements StateResultV2 {}
    record Failure(LoginError error) implements StateResultV2 {}

    static StateResultV2 cont(LoginStateV2 next) { return new Continue(next); }
    static StateResultV2 done() { return new Done(); }
    static StateResultV2 fail(LoginError err) { return new Failure(err); }
}
