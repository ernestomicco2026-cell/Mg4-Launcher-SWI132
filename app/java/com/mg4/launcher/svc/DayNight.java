package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 0.3.1: giorno/notte per il tema automatico. Il sistema dell'auto non cambia uiMode (nei log restava sempre "giorno"),
 * e la home originale della SWI132 (com.saicmotor.launcher, A9_Launcher_V1.0.2.20) non ha un giorno/notte suo: usa
 * solo i temi (SKIN_THEME_CONFIG) e ignora l'avviso giorno/notte del quadro (notifyClusterDayNightModeChange vuoto).
 * Catena delle fonti, la prima valida decide:
 * <ol>
 *   <li>uiMode di sistema, solo se cambia davvero rispetto all'avvio;</li>
 *   <li>segnale della home originale: non esiste su questo firmware (resta nella diagnostica);</li>
 *   <li>fari (anabbaglianti o luci di posizione, come la schermata a riposo della home originale), ma solo intorno ad
 *       alba e tramonto: in Italia gli anabbaglianti sono obbligatori anche di giorno fuori città;</li>
 *   <li>alba e tramonto calcolati senza rete dalla posizione (riserva: fuso orario, poi centro Italia).</li>
 * </ol>
 * 0.3.2: uiMode si ignora anche se resta fermo (giorno o notte) da 12 ore di accensione e contraddice il sole (notte con
 * il sole sopra +6°, giorno con il sole sotto −6°); alla prima accensione senza storico basta "notte" con il sole sopra
 * +10°. Resta ignorato finché uiMode non cambia davvero; allora fari → alba/tramonto.
 * Il cambio si applica solo dopo che il nuovo valore resta uguale per 60 s. La scelta manuale ha sempre la precedenza
 * (la gestisce Theme).
 */
public final class DayNight {
    private static final long STABLE_MS = 60_000;
    /** Intorno ad alba e tramonto (minuti) i fari decidono; fuori da qui decide il sole. */
    private static final int TWILIGHT_MIN = 75;

    private DayNight() {}

    // ---- valori letti dall'auto (VehicleClient) -------------------------------------------------
    static volatile int dipped = Integer.MIN_VALUE, side = Integer.MIN_VALUE, switchPos = Integer.MIN_VALUE;
    static volatile int ipkDayNight = Integer.MIN_VALUE, ipkDim = Integer.MIN_VALUE;
    private static volatile long lampsAt;

    /** Fari letti dall'auto (valori grezzi del servizio luci, come la home originale). */
    static void setLamps(int dippedBeam, int sideLight, int switchPosition) {
        int before = headlights();
        dipped = dippedBeam;
        side = sideLight;
        switchPos = switchPosition;
        lampsAt = SystemClock.uptimeMillis();
        int now = headlights();
        // 0.3.5 (punto 0c): fari prima non leggibili (servizio luci non pronto all'avvio) e ora sì: si ridecide subito
        if (before < 0 && now >= 0 && !lightsSeen) {
            lightsSeen = true;
            redecideNow("fari leggibili (" + (now == 1 ? "accesi" : "spenti") + ")");
        }
    }

    private static volatile boolean lightsSeen;
    private static volatile Context appCtx;
    private static volatile Runnable onChange;

    /** La home si fa avvisare quando il tema automatico cambia subito (senza i 60 s di conferma). */
    public static void setOnChange(Runnable r) {
        onChange = r;
    }

    /**
     * 0.3.5 (punto 0c, da SWI68 1.0.6): i fari sono diventati leggibili o è cambiata la fonte nelle impostazioni: si rilegge
     * e si ridecide SUBITO, senza i 60 s di conferma; se il tema cambia la home si ricostruisce. Log tema.ridecisione.
     */
    public static void redecideNow(String why) {
        MAIN.post(() -> {
            Context c = appCtx;
            if (c == null) return;
            boolean changed;
            String src;
            synchronized (DayNight.class) {
                Eval e;
                try {
                    e = evaluate(c);
                } catch (Throwable t) {
                    Diag.error("tema.ridecisione", t.toString());
                    return;
                }
                pending = null;
                changed = applied == null || applied != e.day;
                applied = e.day;
                source = e.source;
                lastDetail = e.detail;
                src = e.source + " → " + (e.day ? "giorno" : "notte");
                if (changed) lastChange = stamp() + " (subito: " + why + ")";
            }
            Diag.put("tema.ridecisione", why + " · fonte " + src + (changed ? "" : " (già così)"));
            if (changed) {
                syncSystem(c, "subito: " + why);
                Runnable r = onChange;
                if (r != null) r.run();
            }
        });
    }

    static void setCluster(int dayNight, int dim) {
        ipkDayNight = dayNight;
        ipkDim = dim;
    }

    /** Anabbaglianti accesi? 1 sì, 0 no, -1 non si sa. */
    public static int lowBeam() {
        int d = dipped;
        if (d == Integer.MIN_VALUE || SystemClock.uptimeMillis() - lampsAt > 30_000) return -1;
        return d >= 1 && d <= 3 ? 1 : d == 0 ? 0 : -1;
    }

    /** Luci di posizione accese? (3 = accese, come isFrontActive della home originale) 1 sì, 0 no, -1 non si sa. */
    public static int positionLights() {
        int s = side;
        if (s == Integer.MIN_VALUE || SystemClock.uptimeMillis() - lampsAt > 30_000) return -1;
        return s == 3 ? 1 : s >= 0 && s <= 2 ? 0 : -1;
    }

    /** Fari sul disegno dell'auto: 1 accesi, 0 spenti, -1 stato non leggibile. */
    public static int headlights() {
        int lb = lowBeam(), pl = positionLights();
        if (lb == 1 || pl == 1) return 1;
        if (lb == 0 || pl == 0) return 0;
        return -1;
    }

    // ---- stato applicato --------------------------------------------------------------------------
    private static Integer startNight;          // uiMode all'avvio
    private static boolean uiModeMoved;
    private static Boolean applied;             // tema automatico applicato (true = giorno)
    private static Boolean pending;
    private static long pendingSince;
    private static String source = "—", lastChange = "—", lastDetail = "";
    private static volatile double lastSunElevation = Double.NaN;
    private static volatile boolean stuckNow;

    // ---- 0.3.2: letture per il Controllo funzioni --------------------------------------------------

    /** Fonte usata adesso. */
    public static synchronized String source() {
        return source;
    }

    /** Tema automatico applicato: true giorno, false notte, null non ancora deciso. */
    public static synchronized Boolean applied() {
        return applied;
    }

    /** Altezza del sole in gradi (NaN se non ancora calcolata). */
    public static double sunElevation() {
        return lastSunElevation;
    }

    /** uiMode considerato bloccato (ignorato). */
    public static boolean uiStuck() {
        return stuckNow;
    }

    /** Giorno secondo la catena (con l'attesa di 60 s). Va chiamato spesso (ogni 10 s) e a ogni costruzione della home. */
    public static synchronized boolean isDay(Context c) {
        if (appCtx == null) appCtx = c.getApplicationContext();
        Eval e = evaluate(c);
        long now = SystemClock.uptimeMillis();
        if (applied == null) {
            applied = e.day;
            source = e.source;
            lastChange = stamp() + " (avvio)";
            syncSystem(c, "avvio");
        } else if (e.day != applied) {
            if (pending == null || pending != e.day) {
                pending = e.day;
                pendingSince = now;
            } else if (now - pendingSince >= STABLE_MS) {
                applied = e.day;
                source = e.source;
                lastChange = stamp();
                pending = null;
                syncSystem(c, "decisione del launcher (" + e.source + ")");
            }
        } else {
            pending = null;
            source = e.source;
        }
        lastDetail = e.detail;
        return applied;
    }

    /** Riga per tema.stato (senza la parte della scelta manuale, che aggiunge Theme). */
    public static synchronized String status() {
        String wait = pending == null ? "" : " · in attesa di passare a " + (pending ? "giorno" : "notte") + " ("
                + Math.max(0, (STABLE_MS - (SystemClock.uptimeMillis() - pendingSince)) / 1000) + " s)";
        return "fonte " + source + " · " + lastDetail + wait + " · ultimo cambio " + lastChange;
    }

    private static String stamp() {
        return new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date());
    }

    private static final class Eval {
        boolean day;
        String source, detail;
    }

    private static Eval evaluate(Context c) {
        Eval e = new Eval();
        StringBuilder d = new StringBuilder();
        // a) uiMode, solo se cambia davvero
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (startNight == null) startNight = night;
        if (night != startNight && night != Configuration.UI_MODE_NIGHT_UNDEFINED) uiModeMoved = true;
        d.append("uiMode ").append(night == Configuration.UI_MODE_NIGHT_YES ? "notte" : night == Configuration.UI_MODE_NIGHT_NO ? "giorno" : "non definito")
                .append(uiModeMoved ? " (cambiato)" : " (fermo)");
        // d) sole (serve anche ai fari e al controllo di uiMode)
        Sun sun = sun(c);
        double elev = elevation(sun.lat, sun.lon, System.currentTimeMillis());
        lastSunElevation = elev;
        // 0.3.2: uiMode fermo da 12 h di accensione che contraddice il sole → bloccato, ignorato
        int uiNight = night == Configuration.UI_MODE_NIGHT_YES ? 1 : night == Configuration.UI_MODE_NIGHT_NO ? 0 : -1;
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        trackUiChanges(sp, uiNight);
        if (uiNight >= 0 && !Double.isNaN(elev)) {
            boolean contradicts = uiNight == 1 ? elev > 6 : elev < -6;
            long onMs = sp.getLong(K_UI_ON_MS, 0) + onPending;
            boolean noHistory = sp.getLong(K_UI_CHANGED_AT, 0) == 0 && onMs < UI_STUCK_ON_MS;
            if (!sp.getBoolean(K_UI_STUCK, false)) {
                if (onMs >= UI_STUCK_ON_MS && contradicts) markStuck(sp, "mai cambiato da 12 h, contraddice il sole");
                else if (noHistory && uiNight == 1 && elev > 10) markStuck(sp, "prima accensione, notte con il sole a " + Math.round(elev) + "°");
            }
        }
        stuckNow = sp.getBoolean(K_UI_STUCK, false);
        if (stuckNow) d.append(" · uiMode bloccato (").append(sp.getString(K_UI_STUCK_WHY, "bloccato")).append("): ignorato");
        long at = sp.getLong(K_UI_CHANGED_AT, 0);
        d.append(" · ultimo cambio uiMode ").append(at == 0 ? "mai visto" : new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date(at)))
                .append(" (").append((sp.getLong(K_UI_ON_MS, 0) + onPending) / 3_600_000L).append(" h di accensione da allora)");
        int minNow = minutesNow();
        boolean sunDay = sun.valid ? (minNow >= sun.rise && minNow < sun.set) : (minNow >= 7 * 60 && minNow < 18 * 60);
        boolean twilight = sun.valid && (Math.abs(minNow - sun.rise) <= TWILIGHT_MIN || Math.abs(minNow - sun.set) <= TWILIGHT_MIN);
        // c) fari
        int lb = lowBeam(), pl = positionLights();
        d.append(" · fari anabb. ").append(lb < 0 ? "?" : lb == 1 ? "accesi" : "spenti")
                .append(" posiz. ").append(pl < 0 ? "?" : pl == 1 ? "accese" : "spente")
                .append(" (grezzi ").append(raw(dipped)).append('/').append(raw(side)).append('/').append(raw(switchPos)).append(')');
        d.append(" · sole alba ").append(sun.valid ? hm(sun.rise) : "?").append(" tramonto ").append(sun.valid ? hm(sun.set) : "?")
                .append(" (").append(sun.where).append(')');
        if (!Double.isNaN(elev)) d.append(" · sole a ").append(Math.round(elev)).append('°');
        d.append(" · home originale: nessun segnale (solo temi)");
        // 0.3.3 (SWI68 1.0.4 punto A, SWI69 0.4.4): uiMode non è più una fonte, resta solo nel log. Il tema lo decide
        // il launcher (fari → alba/tramonto) e lo scrive nel sistema (syncSystem, SKIN_THEME_CONFIG)
        // 0.3.5 (punto 0c, come SWI68 1.0.6): "Solo fari": scuro con i fari accesi a qualunque ora, chiaro con i fari
        // spenti. Con i fari non leggibili vale l'orario finché il servizio luci non risponde (poi si ridecide subito).
        boolean onlyLights = "solo_fari".equals(sp.getString("temaAutoFonte", "fari_orario"));
        int hl = headlights();
        if (onlyLights && hl >= 0) {
            e.day = hl != 1;
            e.source = "solo fari (" + (hl == 1 ? "accesi" : "spenti") + ")";
        } else if (onlyLights) {
            e.day = sunDay;
            e.source = "solo fari, ma fari non leggibili: orario (alba/tramonto) finché il servizio luci non risponde";
        } else if (twilight && (lb >= 0 || pl >= 0)) {
            e.day = !(lb == 1 || pl == 1);
            e.source = "fari (vicino a " + (Math.abs(minNow - sun.set) <= TWILIGHT_MIN ? "tramonto" : "alba") + ")";
        } else {
            e.day = sunDay;
            e.source = sun.valid ? "alba/tramonto" : "orario fisso 7-18 (posizione non disponibile)";
            if (!twilight && lb == 1 && sunDay) d.append(" · fari accesi di giorno: ignorati");
        }
        e.detail = d.toString();
        return e;
    }

    // ---- 0.3.3 (da SWI69 0.4.4, EH32): il tema si scrive nel sistema come fanno le app SAIC ----------------

    /** Impostazione di sistema delle app SAIC EH32 (SWI69/SWI131/SWI132): 0 = scuro (notte), 1 = chiaro (giorno). */
    public static final String SKIN_KEY = "SKIN_THEME_CONFIG";
    /** Avviso che fa ricaricare il tema a launcher SAIC, SystemUI, Clima e alle altre app SAIC. */
    public static final String SKIN_ACTION = "com.saicmotor.changeSkin";
    private static volatile String sysTxt = "non ancora scritto";
    private static volatile boolean appliedOk = true;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    private static Boolean wanted(Context c) {
        String mode = c.getApplicationContext().getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getString("theme", "auto");
        return "giorno".equals(mode) ? Boolean.TRUE : "notte".equals(mode) ? Boolean.FALSE : applied;
    }

    /** Modo del tema SAIC (Settings.System): 1 automatico (decide SAIC), 0 manuale (ModeConstants SCREEN_SKIN_MODE_*). */
    public static final String SKIN_MODE_KEY = "screen_skin_mode";
    private static final String K_SKIN_MODE_BEFORE = "temaSaicModoPrima";
    /** 0.3.5 (punto 14): SKIN_THEME_CONFIG assente o scrittura rifiutata (IllegalArgumentException): non si scrive più. */
    private static volatile boolean skinRefused;
    private static volatile String absentLogged;

    /** Il tema di sistema SAIC c'è (SKIN_THEME_CONFIG leggibile e scrivibile)? */
    public static boolean skinPresent(Context c) {
        if (skinRefused) return false;
        try {
            return android.provider.Settings.System.getString(c.getContentResolver(), SKIN_KEY) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    private static int skinMode(android.content.ContentResolver cr) {
        try {
            return android.provider.Settings.System.getInt(cr, SKIN_MODE_KEY, -1);
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * Scrive nel sistema il tema effettivo (scelta manuale Giorno/Notte, altrimenti quello automatico): Settings.System
     * SKIN_THEME_CONFIG (0 scuro, 1 chiaro) e l'avviso com.saicmotor.changeSkin, come il servizio CarAdapterService e le
     * Impostazioni SAIC. Si scrive solo se il valore è diverso: all'avvio, alle decisioni del launcher e alla scelta
     * manuale; mai a ogni giro. Log tema.sistema.
     * <p>
     * 0.3.5 (punto 14, come SWI69 0.4.6):
     * <ul>
     *   <li>SKIN_THEME_CONFIG assente (screen_skin_mode -1, scrittura con IllegalArgumentException): nessuna scrittura in
     *   Settings (una riga sola nel log), solo l'avviso com.saicmotor.changeSkin e UiModeManager;</li>
     *   <li>screen_skin_mode = 1 (tema automatico SAIC): alla scelta MANUALE dell'utente si passa a manuale (0) ricordando
     *   il valore di prima, che torna con "Automatico" o con la disinstallazione; in automatico decide SAIC (il launcher
     *   non scrive il tema di sistema).</li>
     * </ul>
     */
    public static void syncSystem(Context ctx, String why) {
        Context c = ctx.getApplicationContext();
        SharedPreferences sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        String choice = sp.getString("theme", "auto");
        boolean manual = "giorno".equals(choice) || "notte".equals(choice);
        android.content.ContentResolver cr = c.getContentResolver();
        String at = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        int modeBefore = skinMode(cr);
        String modeTxt = "";
        // screen_skin_mode: si cambia solo per una scelta manuale e si rimette com'era con "Automatico"
        try {
            if (manual && modeBefore == 1) {
                sp.edit().putInt(K_SKIN_MODE_BEFORE, modeBefore).apply();
                android.provider.Settings.System.putInt(cr, SKIN_MODE_KEY, 0);
                modeTxt = " · tema SAIC " + SKIN_MODE_KEY + " 1 → " + skinMode(cr) + " (manuale, scelta dell'utente; il valore di prima torna con Automatico)";
            } else if (!manual && sp.contains(K_SKIN_MODE_BEFORE)) {
                int prev = sp.getInt(K_SKIN_MODE_BEFORE, 1);
                android.provider.Settings.System.putInt(cr, SKIN_MODE_KEY, prev);
                sp.edit().remove(K_SKIN_MODE_BEFORE).apply();
                modeTxt = " · tema SAIC " + SKIN_MODE_KEY + " " + modeBefore + " → " + skinMode(cr) + " (rimesso com'era)";
            } else {
                modeTxt = " · tema SAIC " + SKIN_MODE_KEY + " " + modeBefore + (modeBefore == 1 ? " (automatico SAIC)" : modeBefore == 0 ? " (manuale)" : " (assente)");
            }
        } catch (Throwable e) {
            modeTxt = " · tema SAIC " + SKIN_MODE_KEY + " " + modeBefore + " · scrittura non riuscita " + e.getClass().getSimpleName();
        }
        Boolean day = wanted(c);
        if (day == null) {
            if (!modeTxt.isEmpty()) Diag.put("tema.sistema", "alle " + at + " · " + why + modeTxt);
            return;
        }
        int want = day ? 1 : 0;
        int modeNow = skinMode(cr);
        if (!manual && modeNow == 1) {
            // automatico del launcher con il tema automatico SAIC: decide SAIC, il launcher non scrive il tema di sistema
            int sys = -1;
            try {
                sys = android.provider.Settings.System.getInt(cr, SKIN_KEY, -1);
            } catch (Throwable ignored) {
            }
            sysTxt = "segue SAIC (automatico SAIC): " + SKIN_KEY + " " + sys + " non scritto · alle " + at + " · " + why + modeTxt;
            appliedOk = true;
            Diag.put("tema.sistema", sysTxt);
            return;
        }
        if (!skinPresent(c)) {
            systemFallback(c, day, "assente", at, why + modeTxt);
            return;
        }
        try {
            int now = android.provider.Settings.System.getInt(cr, SKIN_KEY, -1);
            if (now == want) {
                sysTxt = "già " + (day ? "giorno" : "notte") + " (" + SKIN_KEY + " " + now + ") · controllato alle " + at + " · " + why + modeTxt;
            } else {
                boolean ok;
                try {
                    ok = android.provider.Settings.System.putInt(cr, SKIN_KEY, want);
                } catch (IllegalArgumentException iae) {
                    skinRefused = true;
                    systemFallback(c, day, "scrittura rifiutata (IllegalArgumentException)", at, why + modeTxt);
                    return;
                }
                c.sendBroadcast(new android.content.Intent(SKIN_ACTION));
                int after = android.provider.Settings.System.getInt(cr, SKIN_KEY, -1);
                sysTxt = "scritto " + (day ? "giorno" : "notte") + " (" + SKIN_KEY + " " + now + " → " + want + ", avviso " + SKIN_ACTION
                        + ") · alle " + at + " · esito " + (ok && after == want ? "ok" : "errore (il sistema dice " + after + ")") + " · " + why + modeTxt;
            }
        } catch (Throwable e) {
            sysTxt = "scritto " + (day ? "giorno" : "notte") + " · alle " + at + " · esito errore " + e.getClass().getSimpleName() + " · " + why + modeTxt;
        }
        Diag.put("tema.sistema", sysTxt);
        // 3 s dopo si rilegge: se il servizio SAIC lo ha riscritto, si vede nel log (nessuna riscrittura a ripetizione)
        MAIN.postDelayed(() -> {
            int v = -1;
            try {
                v = android.provider.Settings.System.getInt(c.getContentResolver(), SKIN_KEY, -1);
            } catch (Throwable ignored) {
            }
            appliedOk = v == want;
            Diag.put("tema.sistema", sysTxt + " · dopo 3 s: " + (v == 1 ? "giorno" : v == 0 ? "notte" : "non leggibile")
                    + (appliedOk ? "" : " (DIVERSO da quello scritto)"));
        }, 3000);
    }

    /** SKIN_THEME_CONFIG assente: niente Settings, solo l'avviso SAIC e UiModeManager. Una riga nel log per valore. */
    private static void systemFallback(Context c, boolean day, String what, String at, String why) {
        String ui;
        try {
            android.app.UiModeManager um = (android.app.UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            um.setNightMode(day ? android.app.UiModeManager.MODE_NIGHT_NO : android.app.UiModeManager.MODE_NIGHT_YES);
            ui = "UiModeManager " + (day ? "giorno" : "notte");
        } catch (Throwable e) {
            ui = "UiModeManager non riuscito (" + e.getClass().getSimpleName() + ")";
        }
        try {
            c.sendBroadcast(new android.content.Intent(SKIN_ACTION));
        } catch (Throwable ignored) {
        }
        appliedOk = true;
        sysTxt = "tema di sistema non presente (" + SKIN_KEY + " " + what + "): niente scrittura in Settings · avviso " + SKIN_ACTION
                + " · " + ui + " · alle " + at + " · " + why;
        String k = what + "/" + day;
        if (!k.equals(absentLogged)) {
            absentLogged = k;
            Diag.put("tema.sistema", sysTxt);
        }
    }

    /** Disinstallazione: rimette screen_skin_mode com'era prima della scelta manuale. */
    public static void restoreSkinMode(Context ctx) {
        Context c = ctx.getApplicationContext();
        SharedPreferences sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        if (!sp.contains(K_SKIN_MODE_BEFORE)) return;
        try {
            int prev = sp.getInt(K_SKIN_MODE_BEFORE, 1);
            int before = skinMode(c.getContentResolver());
            android.provider.Settings.System.putInt(c.getContentResolver(), SKIN_MODE_KEY, prev);
            sp.edit().remove(K_SKIN_MODE_BEFORE).commit();
            Diag.put("tema.sistema", "disinstallazione: " + SKIN_MODE_KEY + " " + before + " → " + prev + " (rimesso com'era)");
        } catch (Throwable e) {
            Diag.error("tema.sistema", "ripristino non riuscito: " + e);
        }
    }

    /** Tema scritto nel sistema adesso: true giorno, false notte, null non leggibile (Controllo funzioni). */
    public static Boolean systemDay(Context c) {
        try {
            int v = android.provider.Settings.System.getInt(c.getContentResolver(), SKIN_KEY, -1);
            return v == 1 ? Boolean.TRUE : v == 0 ? Boolean.FALSE : null;
        } catch (Throwable e) {
            return null;
        }
    }

    public static boolean systemApplied() {
        return appliedOk;
    }

    /** Per il Controllo funzioni: [tema voluto (true giorno), tema del sistema riletto]. */
    public static Boolean[] systemCheck(Context c) {
        return new Boolean[]{wanted(c), systemDay(c)};
    }

    public static String systemTxt() {
        return sysTxt;
    }

    // ---- uiMode bloccato (0.3.2, come SWI68 1.0.3; dalla 0.3.3 solo nel log) -------------------------------------------------

    private static final long UI_STUCK_ON_MS = 12L * 60 * 60 * 1000;
    /** Intervallo massimo contato tra due controlli (il tema si controlla ogni 10 s): il tempo a auto spenta non conta. */
    private static final long MAX_GAP_MS = 30_000;
    private static final String K_UI_LAST = "temaUiUltimo", K_UI_CHANGED_AT = "temaUiCambiatoAlle", K_UI_ON_MS = "temaUiAccesoMs",
            K_UI_STUCK = "temaUiBloccato", K_UI_STUCK_WHY = "temaUiBloccatoPerche";
    private static long onTickAt, onPending, onSavedAt;

    /** Conta il tempo di accensione da quando uiMode è cambiato l'ultima volta (giorno↔notte), anche tra un'accensione e l'altra. */
    private static void trackUiChanges(SharedPreferences sp, int uiNight) {
        long now = SystemClock.elapsedRealtime();
        if (onTickAt > 0) onPending += Math.max(0, Math.min(now - onTickAt, MAX_GAP_MS));
        onTickAt = now;
        if (uiNight < 0) return;
        int last = sp.getInt(K_UI_LAST, -1);
        SharedPreferences.Editor e = null;
        if (last != uiNight) {
            e = sp.edit().putInt(K_UI_LAST, uiNight);
            if (last >= 0) {
                // cambio reale: si azzera il conteggio e uiMode torna affidabile
                e.putLong(K_UI_CHANGED_AT, System.currentTimeMillis()).putLong(K_UI_ON_MS, 0).putBoolean(K_UI_STUCK, false).remove(K_UI_STUCK_WHY);
                onPending = 0;
                onSavedAt = now;
            }
        }
        if (onPending > 0 && (now - onSavedAt > 5 * 60_000L || e != null)) {
            if (e == null) e = sp.edit();
            e.putLong(K_UI_ON_MS, sp.getLong(K_UI_ON_MS, 0) + onPending);
            onPending = 0;
            onSavedAt = now;
        }
        if (e != null) e.apply();
    }

    private static void markStuck(SharedPreferences sp, String why) {
        sp.edit().putBoolean(K_UI_STUCK, true).putString(K_UI_STUCK_WHY, why).apply();
        Diag.put("tema.uiMode", "bloccato: " + why);
    }

    /** Altezza del sole sull'orizzonte (gradi) in quel momento (formule NOAA). */
    static double elevation(double lat, double lon, long utcMs) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(utcMs);
        int doy = cal.get(Calendar.DAY_OF_YEAR);
        double hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0 + cal.get(Calendar.SECOND) / 3600.0;
        double g = 2 * Math.PI / 365.0 * (doy - 1 + (hour - 12) / 24);
        double eqt = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
                - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double tst = hour * 60 + eqt + 4 * lon;
        double ha = Math.toRadians(tst / 4 - 180);
        double latR = Math.toRadians(lat);
        double cosZ = Math.sin(latR) * Math.sin(decl) + Math.cos(latR) * Math.cos(decl) * Math.cos(ha);
        return 90 - Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosZ))));
    }

    private static String raw(int v) {
        return v == Integer.MIN_VALUE ? "?" : String.valueOf(v);
    }

    private static String hm(int min) {
        return String.format(Locale.US, "%02d:%02d", min / 60, min % 60);
    }

    private static int minutesNow() {
        Calendar cal = Calendar.getInstance();
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    /** Segnali giorno/notte leggibili, per tema.candidati. */
    public static String candidates(Context c) {
        StringBuilder sb = new StringBuilder();
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        sb.append("uiMode ").append(night == Configuration.UI_MODE_NIGHT_YES ? "notte" : night == Configuration.UI_MODE_NIGHT_NO ? "giorno" : "?");
        try {
            android.app.UiModeManager um = (android.app.UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            sb.append(" · UiModeManager ").append(um.getNightMode());
        } catch (Throwable ignored) {
        }
        sb.append(" · quadro giorno/notte ").append(raw(ipkDayNight)).append(" (luminosità quadro ").append(raw(ipkDim)).append(')');
        sb.append(" · fari ").append(raw(dipped)).append('/').append(raw(side)).append('/').append(raw(switchPos));
        for (String k : new String[]{"screen_skin_mode", "screen_brightness_mode", "screen_brightness", "SKIN_THEME_CONFIG"}) {
            try {
                sb.append(" · ").append(k).append(' ').append(android.provider.Settings.System.getInt(c.getContentResolver(), k, Integer.MIN_VALUE));
            } catch (Throwable ignored) {
            }
        }
        return sb.toString().replace(String.valueOf(Integer.MIN_VALUE), "?");
    }

    // ---- alba e tramonto senza rete ---------------------------------------------------------------

    private static final class Sun {
        boolean valid;
        int rise, set;      // minuti locali
        double lat = 42.5, lon = 12.5;
        String where = "";
    }

    private static Sun cachedSun;
    private static long sunAt;

    private static Sun sun(Context c) {
        long now = SystemClock.uptimeMillis();
        if (cachedSun != null && now - sunAt < 10 * 60_000L) return cachedSun;
        sunAt = now;
        Sun s = new Sun();
        double[] pos = position(c, s);
        s.lat = pos[0];
        s.lon = pos[1];
        Calendar cal = Calendar.getInstance();
        int doy = cal.get(Calendar.DAY_OF_YEAR);
        double offsetH = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000.0;
        double[] rs = riseSet(pos[0], pos[1], doy, offsetH);
        if (rs != null) {
            s.valid = true;
            s.rise = (int) Math.round(rs[0] * 60);
            s.set = (int) Math.round(rs[1] * 60);
        }
        cachedSun = s;
        return s;
    }

    /**
     * Ore locali di alba e tramonto (algoritmo NOAA semplificato, zenit 90,833°); null per notte o giorno polare.
     */
    static double[] riseSet(double lat, double lon, int dayOfYear, double tzOffsetHours) {
        double g = 2 * Math.PI / 365.0 * (dayOfYear - 1);
        double eqt = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
                - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double latR = Math.toRadians(lat);
        double cosH = (Math.cos(Math.toRadians(90.833)) / (Math.cos(latR) * Math.cos(decl))) - Math.tan(latR) * Math.tan(decl);
        if (cosH > 1 || cosH < -1) return null;
        double ha = Math.toDegrees(Math.acos(cosH));
        double riseUtcMin = 720 - 4 * (lon + ha) - eqt;
        double setUtcMin = 720 - 4 * (lon - ha) - eqt;
        return new double[]{(riseUtcMin / 60.0 + tzOffsetHours + 24) % 24, (setUtcMin / 60.0 + tzOffsetHours + 24) % 24};
    }

    /** Posizione: ultima nota del sistema (arrotondata), poi quella salvata, poi il fuso orario, poi il centro Italia. */
    private static double[] position(Context c, Sun s) {
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        try {
            if (c.checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                android.content.pm.PackageManager pm = c.getPackageManager();
                pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                        .invoke(pm, c.getPackageName(), "android.permission.ACCESS_COARSE_LOCATION", android.os.Process.myUserHandle());
            }
            android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            android.location.Location best = null;
            for (String p : lm.getProviders(true)) {
                android.location.Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) {
                // un decimale (circa 10 km) basta per l'alba e il tramonto
                double lat = Math.round(best.getLatitude() * 10) / 10.0, lon = Math.round(best.getLongitude() * 10) / 10.0;
                sp.edit().putFloat("dnLat", (float) lat).putFloat("dnLon", (float) lon).apply();
                s.where = "posizione dell'auto";
                return new double[]{lat, lon};
            }
        } catch (Throwable ignored) {
        }
        if (sp.contains("dnLat")) {
            s.where = "ultima posizione salvata";
            return new double[]{sp.getFloat("dnLat", 42.5f), sp.getFloat("dnLon", 12.5f)};
        }
        double[] tz = byTimeZone(TimeZone.getDefault().getID());
        if (tz != null) {
            s.where = "fuso orario " + TimeZone.getDefault().getID();
            return tz;
        }
        s.where = "centro Italia (riserva)";
        return new double[]{42.5, 12.5};
    }

    /** Capitali dei fusi orari più comuni tra gli utenti (riserva senza posizione). */
    private static double[] byTimeZone(String id) {
        switch (id) {
            case "Europe/Rome": case "Europe/San_Marino": case "Europe/Vatican": return new double[]{42.5, 12.5};
            case "Europe/London": case "Europe/Dublin": return new double[]{52.5, -1.5};
            case "Europe/Madrid": return new double[]{40.4, -3.7};
            case "Europe/Lisbon": return new double[]{39.5, -8.0};
            case "Europe/Paris": return new double[]{46.6, 2.4};
            case "Europe/Berlin": return new double[]{51.2, 10.4};
            case "Europe/Vienna": return new double[]{47.6, 14.1};
            case "Europe/Zurich": return new double[]{46.8, 8.2};
            case "Europe/Brussels": return new double[]{50.6, 4.6};
            case "Europe/Amsterdam": return new double[]{52.2, 5.3};
            case "Europe/Warsaw": return new double[]{52.1, 19.4};
            case "Europe/Prague": return new double[]{49.8, 15.5};
            case "Europe/Budapest": return new double[]{47.2, 19.5};
            case "Europe/Ljubljana": case "Europe/Zagreb": return new double[]{45.8, 15.5};
            case "Europe/Istanbul": return new double[]{39.0, 35.0};
            case "Europe/Athens": return new double[]{38.5, 23.5};
            case "Europe/Stockholm": return new double[]{60.1, 15.6};
            case "Europe/Oslo": return new double[]{60.5, 9.0};
            case "Europe/Copenhagen": return new double[]{56.0, 10.0};
            case "Europe/Helsinki": return new double[]{61.9, 25.7};
            default: return null;
        }
    }
}
