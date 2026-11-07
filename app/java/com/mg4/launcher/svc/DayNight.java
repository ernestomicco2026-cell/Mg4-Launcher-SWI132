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
        dipped = dippedBeam;
        side = sideLight;
        switchPos = switchPosition;
        lampsAt = SystemClock.uptimeMillis();
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

    /** Giorno secondo la catena (con l'attesa di 60 s). Va chiamato spesso (ogni 10 s) e a ogni costruzione della home. */
    public static synchronized boolean isDay(Context c) {
        Eval e = evaluate(c);
        long now = SystemClock.uptimeMillis();
        if (applied == null) {
            applied = e.day;
            source = e.source;
            lastChange = stamp() + " (avvio)";
        } else if (e.day != applied) {
            if (pending == null || pending != e.day) {
                pending = e.day;
                pendingSince = now;
            } else if (now - pendingSince >= STABLE_MS) {
                applied = e.day;
                source = e.source;
                lastChange = stamp();
                pending = null;
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
        return new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY).format(new Date());
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
        // d) sole (serve anche ai fari)
        Sun sun = sun(c);
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
        d.append(" · home originale: nessun segnale (solo temi)");
        if (uiModeMoved) {
            e.day = night != Configuration.UI_MODE_NIGHT_YES;
            e.source = "uiMode";
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

    private static String raw(int v) {
        return v == Integer.MIN_VALUE ? "?" : String.valueOf(v);
    }

    private static String hm(int min) {
        return String.format(Locale.ROOT, "%02d:%02d", min / 60, min % 60);
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
