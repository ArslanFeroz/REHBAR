package com.rahbar.widget;

public class VoiceWakeWordListener {

    public static final String WAKE_PHRASE = "RAHBAR guide me"; // keep in sync with Python STT training data

    /** Activates widget to LISTENING and fires the click listeners. */
    public static void onWakeWordDetected() {
        ArcReactorWidget widget = ArcReactorWidget.getInstance();

        if (widget.getState() == WidgetState.IDLE) { // ignore if already active
            widget.setState(WidgetState.LISTENING);

            // reuses manual-click pathway — backend treats them identically
            ArcReactorWidget.WidgetClickListener listener = getRegisteredListener(widget);
            if (listener != null) {
                listener.onWidgetClicked();
            }
        }
    }

    /** Sets widget to ERROR state on mic/internet failure. */
    public static void onWakeWordError() {
        ArcReactorWidget.getInstance().setState(WidgetState.ERROR);
    }


    static ArcReactorWidget.WidgetClickListener getRegisteredListener(ArcReactorWidget widget) {
        return widget.clickListener;
    }
}
