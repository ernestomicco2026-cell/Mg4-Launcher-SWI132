package com.mg4.launcher.svc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * 0.3.2 (punto 6, da SWI68 1.0.3 adattato): tasti al volante.
 * <p>
 * Sulla SWI132 il servizio dei tasti (com.saicmotor.hardkey) avvisa con il broadcast com.saic.keyevent.hardkey.report
 * (lo ascolta anche la home originale, SwcManager): extra android.intent.extra.hardkey.keycode (codici KeyEvent di
 * Android: 87 avanti, 88 indietro, 24/25 volume, 164 muto), .down e .longpress. Si ascolta anche l'avviso della SWI68
 * (com.android.systemui.ACTION_HARD_KEY_EVENT, KEY_CODE 4/5), nel caso un firmware lo usi.
 * <p>
 * Avanti/indietro li gestisce il sistema; solo se suona la USB 2 e il brano non cambia, MediaClient.onWheelSkip li
 * inoltra alla sessione della USB 2. Log media.tastiVolante (mai dati personali).
 */
public final class WheelKeys {
    public static final String ACTION = "com.saic.keyevent.hardkey.report";
    public static final String ACTION_SWI68 = "com.android.systemui.ACTION_HARD_KEY_EVENT";
    /** Codici normalizzati usati dal Controllo funzioni. */
    public static final int PREV = 4, NEXT = 5;

    public interface Listener {
        void onKey(int code, boolean down, boolean longPress);
    }

    private static boolean registered;
    private static volatile Listener probe;
    private static volatile MediaClient media;
    private static int count;
    private static final java.util.TreeMap<Integer, Integer> perCode = new java.util.TreeMap<>();

    private WheelKeys() {}

    public static synchronized void start(Context c, MediaClient m) {
        if (m != null) media = m;
        if (registered) return;
        try {
            IntentFilter f = new IntentFilter(ACTION);
            f.addAction(ACTION_SWI68);
            c.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    int code;
                    boolean down, lp;
                    if (ACTION.equals(i.getAction())) {
                        int k = i.getIntExtra("android.intent.extra.hardkey.keycode", -1);
                        down = i.getBooleanExtra("android.intent.extra.hardkey.down", false);
                        lp = i.getBooleanExtra("android.intent.extra.hardkey.longpress", false);
                        code = k == 87 ? NEXT : k == 88 ? PREV : k == 24 ? 1 : k == 25 ? 2 : k == 164 ? 3 : 1000 + k;
                    } else {
                        code = i.getIntExtra("KEY_CODE", -1);
                        down = i.getBooleanExtra("DOWN", false);
                        lp = i.getBooleanExtra("LONG_PRESS", false);
                    }
                    note(code, down, lp);
                    MediaClient mc = media;
                    if (down && !lp && mc != null && (code == NEXT || code == PREV)) mc.onWheelSkip(code == NEXT);
                    Listener l = probe;
                    if (l != null) l.onKey(code, down, lp);
                }
            }, f);
            registered = true;
            Diag.put("media.tastiVolante", "in ascolto (" + ACTION + ") · nessun tasto visto finora");
        } catch (Throwable e) {
            Diag.error("media.tastiVolante", e.toString());
        }
    }

    /** Prova del Controllo funzioni: riceve i tasti finché non si rimette null. */
    public static void setProbe(Listener l) {
        probe = l;
    }

    public static String name(int code) {
        switch (code) {
            case 1: return "volume +";
            case 2: return "volume −";
            case 3: return "muto";
            case PREV: return "indietro";
            case NEXT: return "avanti";
            default: return code >= 1000 ? "tasto " + (code - 1000) : "codice " + code;
        }
    }

    private static synchronized void note(int code, boolean down, boolean lp) {
        count++;
        Integer n = perCode.get(code);
        perCode.put(code, n == null ? 1 : n + 1);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Integer, Integer> e : perCode.entrySet()) {
            sb.append(sb.length() == 0 ? "" : ", ").append(name(e.getKey())).append(" ×").append(e.getValue());
        }
        Diag.put("media.tastiVolante", "ultimo " + name(code) + " DOWN " + down + " LONG_PRESS " + lp
                + " · sorgente " + MediaClient.currentSourceName() + " · visti " + count + " eventi: " + sb
                + " · instradamento " + Diag.value("media.tastiVolante.instradamento"));
    }
}
