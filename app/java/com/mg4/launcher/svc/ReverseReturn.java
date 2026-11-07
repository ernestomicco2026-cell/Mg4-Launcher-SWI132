package com.mg4.launcher.svc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 0.3.1 (punto 5): ritorno all'app di prima dopo la retromarcia.
 * <p>
 * Con la home MG l'auto torna da sola a CarPlay/Android Auto dopo le telecamere: la telecamera (com.saicmotor.evs) manda
 * lo stato della retromarcia al servizio della home originale (ILauncherService, comando 16395) e la sua gestione
 * dell'"ultima modalità" (LastModeManager) riapre l'app. Con il nostro launcher come home quel ripristino non c'è più: alla
 * chiusura delle telecamere ricompare la home (la home MG, che StartGuard sostituisce col launcher, o direttamente il
 * launcher). Qui si fa il ripristino: all'apertura delle telecamere (avviso com.saicmotor.360camera.show o app evs in primo
 * piano) si ricorda l'app che c'era davanti; alla chiusura, se davanti torna la home, si riapre quell'app come fa il
 * pulsante CarPlay/Android Auto del launcher. Non si riapre se l'utente nel frattempo ha scelto un'altra app o premuto
 * Home, se la retromarcia è durata più di 3 minuti o se l'app non è più collegata (box staccato).
 * Tutto va nella diagnostica retro.ritorno.
 */
public final class ReverseReturn {
    private static final String EVS = "com.saicmotor.evs";
    private static final String SHOW = "com.saicmotor.360camera.show", CLOSE = "com.saicmotor.360camera.close";
    private static final long MAX_REVERSE_MS = 3 * 60_000L, DECIDE_WINDOW_MS = 6000;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Context app;
    private static volatile MediaClient media;
    private static boolean registered;

    // stato
    private static String lastPkg, lastCls;          // ultima app "da riaprire" vista davanti
    private static String remembered, rememberedCls; // app ricordata all'apertura delle telecamere
    private static long cameraSince = -1, closedAt = -1;
    private static boolean cameraOn, userAction, decided;
    private static final StringBuilder after = new StringBuilder();

    private ReverseReturn() {}

    /** Collegamento: avvisi delle telecamere (una sola registrazione per processo) e client media per CarPlay/AA. */
    public static synchronized void init(Context c, MediaClient m) {
        app = c.getApplicationContext();
        if (m != null) media = m;
        if (registered) return;
        registered = true;
        try {
            IntentFilter f = new IntentFilter(SHOW);
            f.addAction(CLOSE);
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    if (SHOW.equals(i.getAction())) cameraShown("avviso telecamere");
                    else cameraClosed("avviso telecamere");
                }
            }, f);
        } catch (Throwable e) {
            Diag.put("retro.ritorno", "avvisi telecamere non disponibili: " + e.getClass().getSimpleName());
        }
    }

    private static boolean enabled() {
        return app != null && app.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getBoolean("retroRitorno", true);
    }

    private static boolean isCamera(String pkg) {
        return EVS.equals(pkg);
    }

    /** Home MG, nostro launcher, barre di sistema: non sono app "da riaprire". */
    private static boolean isHomeOrSystem(String pkg) {
        return pkg == null || pkg.equals(app.getPackageName()) || BootWatcher.MG_HOME.equals(pkg) || pkg.equals("com.android.systemui")
                || pkg.equals("android") || pkg.startsWith("com.android.launcher") || pkg.equals("com.android.settings.FallbackHome")
                || pkg.equals("com.android.provision");
    }

    static String kind(String pkg) {
        if (pkg == null) return "nessuna";
        if (pkg.startsWith("com.allgo.carplay")) return "CarPlay";
        if (pkg.startsWith("com.allgo.app.androidauto") || pkg.startsWith("com.allgo.aap")) return "Android Auto";
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("telenav") || p.contains("igo") || p.contains("navi") || p.contains("map") || p.contains("waze")) return "navigatore " + pkg;
        return "app " + pkg;
    }

    private static boolean isProjection(String pkg) {
        return pkg != null && (pkg.startsWith("com.allgo.carplay") || pkg.startsWith("com.allgo.app.androidauto") || pkg.startsWith("com.allgo.aap"));
    }

    /** Ogni cambio di app in primo piano (dal servizio di accessibilità o dal controllo di riserva). */
    public static synchronized void onForeground(Context c, String pkg, String cls) {
        if (app == null) app = c.getApplicationContext();
        if (pkg == null) return;
        if (isCamera(pkg)) {
            if (!cameraOn) cameraByForeground = true;
            cameraShown("telecamere in primo piano");
            return;
        }
        if (cameraOn) {
            if (cameraByForeground) {
                // le telecamere erano un'app in primo piano e ora non lo sono più: chiuse
                if (!isHomeOrSystem(pkg) && !pkg.equals(remembered)) userAction = true;
                cameraClosed("telecamere non più in primo piano");
            } else if (!isHomeOrSystem(pkg) && !pkg.equals(remembered)) {
                userAction = true;   // un'altra app aperta mentre le telecamere (finestra) sono attive
            }
        }
        if (closedAt > 0 && !decided) {
            if (after.length() < 200) after.append(" → ").append(pkg);
            if (!isHomeOrSystem(pkg)) {
                // tornata da sola l'app di prima, oppure l'utente ne ha scelta un'altra
                decided = true;
                Diag.put("retro.ritorno", "app ricordata " + kind(remembered) + " · dopo le telecamere:" + after
                        + (pkg.equals(remembered) ? " · tornata da sola, niente da fare" : " · scelta un'altra app: non riapro"));
            } else {
                H.postDelayed(ReverseReturn::decide, 700);
            }
        }
        if (!isHomeOrSystem(pkg)) {
            lastPkg = pkg;
            lastCls = cls;
        }
    }

    private static boolean cameraByForeground;

    private static synchronized void cameraShown(String why) {
        if (cameraOn) return;
        cameraOn = true;
        cameraSince = SystemClock.elapsedRealtime();
        closedAt = -1;
        decided = false;
        userAction = false;
        after.setLength(0);
        String top = StartGuard.topPackage(app);
        // l'app davanti prima delle telecamere: l'ultima vista, se non era la home
        remembered = lastPkg != null && !isCamera(lastPkg) ? lastPkg : null;
        rememberedCls = lastCls;
        if (top != null && !isCamera(top) && !isHomeOrSystem(top)) remembered = top;
        // se prima c'era la home, non c'è niente da riaprire
        if (top != null && isHomeOrSystem(top) && !isCamera(top)) remembered = null;
        Diag.put("retro.ritorno", "telecamere aperte (" + why + ") · app ricordata " + kind(remembered));
    }

    private static synchronized void cameraClosed(String why) {
        if (!cameraOn) return;
        cameraOn = false;
        cameraByForeground = false;
        closedAt = SystemClock.elapsedRealtime();
        H.postDelayed(ReverseReturn::decide, 900);
        H.postDelayed(ReverseReturn::decide, 2500);
        H.postDelayed(ReverseReturn::decide, 5000);
        Diag.put("retro.ritorno", "telecamere chiuse (" + why + ") dopo " + (closedAt - cameraSince) / 1000 + " s · app ricordata " + kind(remembered));
    }

    /** Tasto Home premuto mentre le telecamere sono aperte o subito dopo: non si riapre nulla. */
    public static synchronized void userPressedHome() {
        if (cameraOn || (closedAt > 0 && !decided && SystemClock.elapsedRealtime() - closedAt > 1500)) {
            userAction = true;
        }
    }

    /**
     * La home MG sta per essere sostituita dal launcher (StartGuard): se è il ritorno dopo le telecamere e c'è un'app da
     * riaprire, si riapre quella al posto del launcher. True se ci ha pensato ReverseReturn.
     */
    public static synchronized boolean onMgHomeAfterCamera(Context c) {
        if (closedAt < 0 || decided || cameraOn) return false;
        if (SystemClock.elapsedRealtime() - closedAt > DECIDE_WINDOW_MS) return false;
        if (after.length() < 200) after.append(" (home MG: la sostituisce il ritorno)");
        return decideNow("home MG dopo le telecamere");
    }

    private static synchronized void decide() {
        if (closedAt < 0 || decided || cameraOn) return;
        if (SystemClock.elapsedRealtime() - closedAt > DECIDE_WINDOW_MS) return;
        String top = StartGuard.topPackage(app);
        if (top == null || isCamera(top)) return;
        if (!isHomeOrSystem(top)) {
            decided = true;
            Diag.put("retro.ritorno", "app ricordata " + kind(remembered) + " · davanti " + top + (top.equals(remembered) ? ": tornata da sola" : ": non riapro"));
            return;
        }
        String who = BootWatcher.MG_HOME.equals(top) ? "home MG" : top.equals(app.getPackageName()) ? "launcher (home del sistema)" : top;
        decideNow(who);
    }

    /** Decide e, se va bene, riapre l'app ricordata. True se l'ha riaperta. */
    private static boolean decideNow(String broughtBy) {
        decided = true;
        long dur = closedAt - cameraSince;
        String base = "app ricordata " + kind(remembered) + " · in primo piano dopo le telecamere: " + broughtBy
                + " (sequenza:" + (after.length() == 0 ? " —" : after) + ")";
        String why = null;
        if (!enabled()) why = "opzione spenta";
        else if (remembered == null) why = "prima delle telecamere c'era la home";
        else if (dur > MAX_REVERSE_MS) why = "retromarcia durata " + dur / 1000 + " s (più di 3 minuti)";
        else if (userAction) why = "nel frattempo l'utente ha premuto Home o scelto un'altra app";
        else if (isProjection(remembered)) {
            MediaClient m = media;
            if (m == null || m.last().deviceType == 0) why = "telefono o box non più collegato";
        } else if (app.getPackageManager().getLaunchIntentForPackage(remembered) == null) why = "app non più presente";
        if (why != null) {
            Diag.put("retro.ritorno", base + " · non riaperta: " + why);
            return false;
        }
        boolean ok;
        if (isProjection(remembered)) {
            ok = media.openProjection();
        } else {
            ok = false;
            try {
                Intent i = app.getPackageManager().getLaunchIntentForPackage(remembered);
                if (i != null) {
                    app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                    ok = true;
                }
            } catch (Throwable e) {
                Diag.error("retro.ritorno", e.toString());
            }
        }
        Diag.put("retro.ritorno", base + (ok ? " · riaperta " + kind(remembered) : " · riapertura NON riuscita"));
        return ok;
    }
}
