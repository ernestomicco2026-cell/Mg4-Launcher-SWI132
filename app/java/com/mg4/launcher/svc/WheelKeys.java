package com.mg4.launcher.svc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.KeyEvent;

/**
 * Tasti al volante (0.3.2, da SWI68 1.0.3 adattato; 0.3.3 rifatto per la SWI132).
 * <p>
 * Sulla SWI132 il servizio dei tasti (com.saicmotor.hardkey) avvisa con il broadcast com.saic.keyevent.hardkey.report
 * solo alcuni tasti (volume, muto, telefono, voce e i codici SAIC 288–304: lo si vede nella home originale, SwcManager).
 * Avanti/indietro della levetta invece arrivano come tasti multimediali di Android (KEYCODE_MEDIA_NEXT 87 /
 * KEYCODE_MEDIA_PREVIOUS 88), che il sistema manda alla sessione media "dei tasti": per questo il Controllo funzioni
 * della 0.3.2 vedeva tasti ma mai avanti/indietro.
 * <p>
 * 0.3.3: il launcher si mette in ascolto dei tasti multimediali con MediaSessionManager.setOnMediaKeyListener
 * (permesso di sistema SET_MEDIA_KEY_LISTENER, firma platform). Il listener vede ogni tasto multimediale prima della
 * consegna: lo lascia passare (false), tranne avanti/indietro quando suona la USB 2, che manda subito alla sessione
 * della USB 2 (sul sistema finirebbe alla USB 1). Restano il broadcast (log dei codici grezzi) e l'avviso della SWI68.
 * Log media.tastiVolante con i codici grezzi visti (mai dati personali).
 * <p>
 * 0.3.5 (punto 15): sulla SWI69 dall'avviso SAIC arrivano i codici 297 e 298 (probabilmente avanti/indietro). Il Controllo
 * funzioni ha un passo guidato ("premi AVANTI", poi "premi INDIETRO") che registra i codici grezzi visti e li associa:
 * da lì quei codici valgono avanti/indietro (anche per la USB 2). Log tasti.volante con i codici di ogni auto.
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

    /** 0.3.5: ascolto dei codici grezzi (passo guidato del Controllo funzioni). */
    public interface RawListener {
        void onRaw(String via, int raw, boolean down, boolean longPress);
    }

    private static volatile RawListener rawProbe;
    /** Codici grezzi associati dal passo guidato (Integer.MIN_VALUE = nessuno). */
    private static volatile int learnedNext = Integer.MIN_VALUE, learnedPrev = Integer.MIN_VALUE;
    private static volatile String learnedNextVia = "", learnedPrevVia = "";
    private static android.content.SharedPreferences sp;
    private static volatile MediaClient media;
    private static volatile boolean keyListenerOn;
    private static int count;
    private static final java.util.TreeMap<String, Integer> perCode = new java.util.TreeMap<>();

    private WheelKeys() {}

    public static synchronized void start(Context c, MediaClient m) {
        if (m != null) media = m;
        if (registered) return;
        registered = true;
        Context app = c.getApplicationContext();
        sp = app.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        learnedNext = sp.getInt("tastoAvanti", Integer.MIN_VALUE);
        learnedPrev = sp.getInt("tastoIndietro", Integer.MIN_VALUE);
        learnedNextVia = sp.getString("tastoAvantiVia", "");
        learnedPrevVia = sp.getString("tastoIndietroVia", "");
        if (learnedNext != Integer.MIN_VALUE || learnedPrev != Integer.MIN_VALUE) Diag.put("tasti.volante", learnedText());
        try {
            IntentFilter f = new IntentFilter(ACTION);
            f.addAction(ACTION_SWI68);
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    int code, raw;
                    boolean down, lp;
                    String via;
                    if (ACTION.equals(i.getAction())) {
                        raw = i.getIntExtra("android.intent.extra.hardkey.keycode", -1);
                        down = i.getBooleanExtra("android.intent.extra.hardkey.down", false);
                        lp = i.getBooleanExtra("android.intent.extra.hardkey.longpress", false);
                        code = learned(normalize(raw), raw, "avviso SAIC");
                        via = "avviso SAIC";
                    } else {
                        raw = i.getIntExtra("KEY_CODE", -1);
                        code = raw;
                        down = i.getBooleanExtra("DOWN", false);
                        lp = i.getBooleanExtra("LONG_PRESS", false);
                        via = "avviso SWI68";
                    }
                    note(via, raw, code, down, lp);
                    RawListener rl = rawProbe;
                    if (rl != null) rl.onRaw(via, raw, down, lp);
                    // avanti/indietro dall'avviso: se il listener dei tasti multimediali non c'è, si prova come nella 0.3.2
                    MediaClient mc = media;
                    boolean learnedKey = raw != KeyEvent.KEYCODE_MEDIA_NEXT && raw != KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                    if (down && !lp && mc != null && (code == NEXT || code == PREV)) {
                        // 0.3.5: codici associati dal passo guidato (es. 297/298): li gestisce già il sistema, il launcher
                        // interviene solo con la USB 2 che suona (sul sistema finirebbero alla USB 1). Così niente doppio salto.
                        if (learnedKey) {
                            if (mc.usb2Playing()) mc.skipUsb2(code == NEXT);
                        } else if (!keyListenerOn) mc.onWheelSkip(code == NEXT);
                    }
                    Listener l = probe;
                    if (l != null) l.onKey(code, down, lp);
                }
            }, f);
        } catch (Throwable e) {
            Diag.error("media.tastiVolante", e.toString());
        }
        startMediaKeyListener(app);
        Diag.put("media.tastiVolante", "in ascolto (avviso " + ACTION + (keyListenerOn ? " e tasti multimediali" : "") + ") · nessun tasto visto finora");
    }

    private static int normalize(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT: return NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return PREV;
            case KeyEvent.KEYCODE_VOLUME_UP: return 1;
            case KeyEvent.KEYCODE_VOLUME_DOWN: return 2;
            case KeyEvent.KEYCODE_VOLUME_MUTE: return 3;
            default: return 1000 + keyCode;
        }
    }

    /** Listener di sistema dei tasti multimediali (API nascosta, via reflection). */
    private static void startMediaKeyListener(Context app) {
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
            Class<?> lc = Class.forName("android.media.session.MediaSessionManager$OnMediaKeyListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(lc.getClassLoader(), new Class<?>[]{lc}, (o, method, args) -> {
                String n = method.getName();
                if ("onMediaKey".equals(n) && args != null && args.length == 1 && args[0] instanceof KeyEvent) return onMediaKey((KeyEvent) args[0]);
                if ("hashCode".equals(n)) return System.identityHashCode(o);
                if ("equals".equals(n)) return args != null && args.length == 1 && o == args[0];
                if ("toString".equals(n)) return "MG4Launcher.WheelKeys";
                return null;
            });
            msm.getClass().getMethod("setOnMediaKeyListener", lc, android.os.Handler.class)
                    .invoke(msm, proxy, new android.os.Handler(android.os.Looper.getMainLooper()));
            keyListenerOn = true;
            Diag.put("media.tastiVolante.listener", "attivo (setOnMediaKeyListener)");
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            keyListenerOn = false;
            Diag.put("media.tastiVolante.listener", "non disponibile: " + c.getClass().getSimpleName()
                    + (c.getMessage() == null ? "" : " (" + c.getMessage() + ")"));
        }
    }

    /** Un tasto multimediale prima della consegna: true = lo gestisce il launcher (non va alla sessione del sistema). */
    private static boolean onMediaKey(KeyEvent ev) {
        try {
            int raw = ev.getKeyCode();
            boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
            boolean lp = ev.isLongPress() || ev.getRepeatCount() > 0;
            int code = learned(normalize(raw), raw, "tasto multimediale");
            note("tasto multimediale", raw, code, down, lp);
            RawListener rl = rawProbe;
            if (rl != null) rl.onRaw("tasto multimediale", raw, down, lp);
            Listener l = probe;
            if (l != null) l.onKey(code, down, lp);
            MediaClient mc = media;
            if ((code == NEXT || code == PREV) && mc != null && mc.usb2Playing()) {
                // suona la USB 2: avanti/indietro alla sua sessione (sia la pressione sia il rilascio restano al launcher)
                if (down && ev.getRepeatCount() == 0) mc.skipUsb2(code == NEXT);
                return true;
            }
        } catch (Throwable e) {
            Diag.error("media.tastiVolante", e.toString());
        }
        return false;
    }

    /** Codice normalizzato tenendo conto dei codici associati dal passo guidato (solo sulla stessa via). */
    private static int learned(int code, int raw, String via) {
        if (raw == learnedNext && via.equals(learnedNextVia)) return NEXT;
        if (raw == learnedPrev && via.equals(learnedPrevVia)) return PREV;
        return code;
    }

    public static void setRawProbe(RawListener l) {
        rawProbe = l;
    }

    /** 0.3.5 (punto 15): associa i codici grezzi visti nel passo guidato; next/prev = MIN_VALUE se non visti. */
    public static void learn(int next, String nextVia, int prev, String prevVia) {
        // solo una coppia valida: tutti e due visti e diversi (stesso tasto premuto due volte = non si associa niente)
        if (next == Integer.MIN_VALUE || prev == Integer.MIN_VALUE || next == prev && nextVia.equals(prevVia)) {
            Diag.put("tasti.volante", "passo guidato non valido, associazione non cambiata · " + learnedText());
            return;
        }
        if (next != Integer.MIN_VALUE) {
            learnedNext = next;
            learnedNextVia = nextVia;
        }
        if (prev != Integer.MIN_VALUE) {
            learnedPrev = prev;
            learnedPrevVia = prevVia;
        }
        if (sp != null) sp.edit().putInt("tastoAvanti", learnedNext).putInt("tastoIndietro", learnedPrev)
                .putString("tastoAvantiVia", learnedNextVia).putString("tastoIndietroVia", learnedPrevVia).apply();
        Diag.put("tasti.volante", learnedText());
    }

    private static String learnedText() {
        return "avanti = " + (learnedNext == Integer.MIN_VALUE ? "non visto" : "codice " + learnedNext + " (" + learnedNextVia + ")")
                + " · indietro = " + (learnedPrev == Integer.MIN_VALUE ? "non visto" : "codice " + learnedPrev + " (" + learnedPrevVia + ")");
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

    private static synchronized void note(String via, int raw, int code, boolean down, boolean lp) {
        count++;
        String k = name(code) + " [" + via + " " + raw + "]";
        Integer n = perCode.get(k);
        perCode.put(k, n == null ? 1 : n + 1);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : perCode.entrySet()) {
            if (sb.length() > 600) break;
            sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey()).append(" ×").append(e.getValue());
        }
        Diag.put("media.tastiVolante", "ultimo " + name(code) + " (codice grezzo " + raw + ", " + via + ") DOWN " + down + " LONG_PRESS " + lp
                + " · sorgente " + MediaClient.currentSourceName() + " · visti " + count + " eventi: " + sb
                + " · instradamento " + Diag.value("media.tastiVolante.instradamento"));
    }
}
