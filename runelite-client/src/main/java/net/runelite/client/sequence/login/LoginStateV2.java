package net.runelite.client.sequence.login;

/** V2 login states. {@code SWITCH_WORLD} inserted before NUDGE_INTRO. */
public enum LoginStateV2
{
    PRECHECK,
    WAIT_FOR_LOGIN_SCREEN,
    AWAIT_JAGEX_LOGIN,
    SWITCH_WORLD,
    NUDGE_INTRO,
    RESOLVE_USERNAME,
    CLEAR_USERNAME,
    TYPE_USERNAME,
    FOCUS_PASSWORD,
    CLEAR_PASSWORD,
    PASTE_PASSWORD,
    CLICK_LOGIN,
    AWAIT_LOGGED_IN,
    AWAIT_WELCOME,
    DISMISS_WELCOME,
    DONE
}
