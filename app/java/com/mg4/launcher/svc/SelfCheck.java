package com.mg4.launcher.svc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.mg4.launcher.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.3.2 (punto 11, da SWI68 1.0.3): Controllo funzioni, come l'AutoTest di MG4 Clima e il controllo di MG4 Voice.
 * <p>
 * Tutto in SOLA LETTURA: si guardano i servizi collegati e i valori già letti, non si manda nessun comando all'auto
 * (niente clima, radio, play, chiamate). Ogni prova dà ✓ (funziona), ✗ (non funziona) o — (non presente su questa auto,
 * oppure saltata). Nel log: controllo.&lt;nome&gt; = ✓/✗/— dettaglio, più controllo.riepilogo.
 * <p>
 * Dopo ogni aggiornamento il controllo parte da solo, in silenzio, 60 s dopo l'avvio: solo log, senza la prova dei
 * tasti al volante (che chiede di premere un tasto).
 */
public final class SelfCheck {
    public static final int OK = 1, FAIL = 0, ABSENT = -1, SKIPPED = -2;

    public static final class Item {
        public final String key;
        public final int label;
        public int status = SKIPPED;
        /** Dettaglio in italiano per il log. */
        public String detail = "";
        /** Dettaglio senza parole (numeri, °C, porte) mostrato a schermo accanto alla frase tradotta. */
        public String shown = "";

        Item(String key, int label) {
            this.key = key;
            this.label = label;
        }

        public String mark() {
            return status == OK ? "✓" : status == FAIL ? "✗" : "—";
        }
    }

    public interface Progress {
        /** Una riga è cambiata (sul thread principale). */
        void onItem(Item it);

        /** Prova dei tasti: secondi che restano (sul thread principale); 0 = finita. */
        void onKeysCountdown(int seconds);

        /** 0.3.5: passo guidato dei tasti: next = "premi AVANTI", altrimenti "premi INDIETRO"; secondi che restano. */
        default void onKeysStep(boolean next, int seconds) {
            onKeysCountdown(seconds);
        }

        /** 1.0.4: prova dei fari: secondi che restano (sul thread principale); 0 = finita. */
        default void onLightsCountdown(int seconds) {
        }

        void onDone(List<Item> items, int ok, int fail, int absent);
    }

    private static VehicleClient vehicle;
    private static MediaClient media;
    private static LogUploader logs;
    private static volatile boolean running;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private SelfCheck() {}

    /** Chiamato da MainActivity: i client già collegati (il controllo non ne apre di nuovi). */
    public static void bind(VehicleClient v, MediaClient m, LogUploader l) {
        vehicle = v;
        media = m;
        logs = l;
    }

    public static boolean running() {
        return running;
    }

    /** Elenco delle prove, nell'ordine in cui compaiono a schermo. */
    public static List<Item> items() {
        List<Item> l = new ArrayList<>();
        l.add(new Item("veicolo", R.string.chk_vehicle));
        l.add(new Item("clima", R.string.chk_climate));
        l.add(new Item("radio", R.string.chk_radio));
        l.add(new Item("media", R.string.chk_media));
        l.add(new Item("bms", R.string.chk_bms));
        l.add(new Item("carplayAA", R.string.chk_projection));
        l.add(new Item("tempEsterna", R.string.chk_out_temp));
        l.add(new Item("tempInterna", R.string.chk_in_temp));
        l.add(new Item("tema", R.string.chk_theme));
        l.add(new Item("temaSistema", R.string.chk_theme_system));
        l.add(new Item("fari", R.string.chk_lights));
        l.add(new Item("usb", R.string.chk_usb));
        l.add(new Item("bluetooth", R.string.chk_bt));
        l.add(new Item("stazioni", R.string.chk_stations));
        l.add(new Item("tastiVolante", R.string.chk_keys));
        l.add(new Item("tastoClima", R.string.chk_sys_button));
        l.add(new Item("sediliVolante", R.string.chk_comfort));
        l.add(new Item("aggiornamento", R.string.chk_update));
        l.add(new Item("log", R.string.chk_logs));
        l.add(new Item("avvisi", R.string.chk_notices));
        l.add(new Item("animazione", R.string.chk_charge_anim));
        l.add(new Item("ready", R.string.chk_ready));
        l.add(new Item("spegnimento", R.string.chk_power));
        l.add(new Item("riscaldamentoBatteria", R.string.chk_batt_heat));
        return l;
    }

    /**
     * Avvia il controllo su un thread a parte. interactive = true: con la prova dei tasti al volante (10 s).
     * Progress può essere null (controllo silenzioso dopo un aggiornamento).
     */
    public static void run(Context ctx, boolean interactive, Progress p) {
        if (running) return;
        running = true;
        Context c = ctx.getApplicationContext();
        List<Item> items = items();
        new Thread(() -> {
            try {
                Diag.put("controllo.avvio", (interactive ? "dalle impostazioni" : "automatico dopo l'aggiornamento") + " · "
                        + new java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.ITALY).format(new java.util.Date()));
                for (Item it : items) {
                    try {
                        check(c, it, interactive, p);
                    } catch (Throwable e) {
                        it.status = FAIL;
                        it.detail = "errore " + e.getClass().getSimpleName();
                    }
                    Diag.put("controllo." + it.key, it.mark() + " " + it.detail);
                    // esito salvato: una funzione ✗ o assente nasconde la sua opzione "Automatico" nelle impostazioni
                    if (it.status != SKIPPED) c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).edit()
                            .putInt("controllo_" + it.key, it.status).apply();
                    if (p != null) main.post(() -> p.onItem(it));
                }
                int ok = 0, fail = 0, absent = 0;
                for (Item it : items) {
                    if (it.status == OK) ok++;
                    else if (it.status == FAIL) fail++;
                    else absent++;
                }
                Diag.put("controllo.riepilogo", ok + " ok · " + fail + " errori · " + absent + " assenti");
                int o = ok, f = fail, a = absent;
                if (p != null) main.post(() -> p.onDone(items, o, f, a));
            } finally {
                running = false;
            }
        }, "mg4-controllo").start();
    }

    private static void set(Item it, int status, String detail, String shown) {
        it.status = status;
        it.detail = detail;
        it.shown = shown == null ? "" : shown;
    }

    private static void check(Context c, Item it, boolean interactive, Progress p) throws InterruptedException {
        VehicleClient v = vehicle;
        MediaClient m = media;
        VehicleClient.State s = v == null ? null : v.last();
        switch (it.key) {
            case "veicolo": {
                CarProps cp = VehicleClient.carProps();
                boolean hub = s != null && s.connected;
                boolean props = cp != null && cp.ready();
                set(it, hub ? OK : FAIL, "servizio veicolo " + (hub ? "collegato" : "non collegato") + " · CarService "
                        + (props ? "pronto" : "non pronto"), "");
                break;
            }
            case "clima": {
                boolean read = s != null && (s.hvacPower >= 0 || s.acTemp > 0 || s.fanLevel >= 0);
                boolean app = installed(c, com.mg4.launcher.apps.MgApps.HVAC_PKG);
                if (read) set(it, OK, "stato letto (acceso " + s.hvacPower + ", temperatura " + s.acTemp + ", ventola " + s.fanLevel + ")",
                        s.acTemp > 0 ? s.acTemp + "°" : "");
                else set(it, FAIL, "stato del clima non letto" + (app ? "" : " · app Clima assente"), "");
                break;
            }
            case "radio": {
                if (!installed(c, "com.saicmotor.radio")) {
                    set(it, ABSENT, "servizio radio non installato", "");
                    break;
                }
                boolean ok = m != null && m.radioLinked();
                set(it, ok ? OK : FAIL, "servizio radio " + (ok ? "collegato" : "non collegato") + " · " + Diag.value("radio.codici"), "");
                break;
            }
            case "media": {
                boolean ok = m != null && m.mediaLinked();
                set(it, ok ? OK : FAIL, "servizio media (stato + lettore) " + (ok ? "collegato" : "non collegato"), "");
                break;
            }
            case "bms": {
                boolean ok = s != null && s.bmsAvailable && s.soc >= 0;
                set(it, ok ? OK : FAIL, ok ? "carica letta " + Math.round(s.soc) + "%" : "carica della batteria non letta",
                        ok ? Math.round(s.soc) + "%" : "");
                break;
            }
            case "carplayAA": {
                Boolean l = m == null ? Boolean.FALSE : m.projectionLinked();
                if (l == null) set(it, ABSENT, "Allgo (CarPlay/Android Auto) non installato", "");
                else set(it, l ? OK : FAIL, "servizio Allgo " + (l ? "collegato" : "non collegato")
                        + (m != null && m.last().deviceType != 0 ? " · telefono collegato" : " · nessun telefono"), "");
                break;
            }
            case "tempEsterna": {
                boolean ok = s != null && s.outsideTemp > -60 && s.outsideTemp < 70;
                set(it, ok ? OK : FAIL, ok ? "letta " + s.outsideTemp + " °C" : "non letta", ok ? Math.round(s.outsideTemp) + "°C" : "");
                break;
            }
            case "tempInterna": {
                CarProps cp = VehicleClient.carProps();
                if (cp != null && cp.ready() && !cp.has(CarProps.HVAC_TEMPERATURE_CURRENT)) {
                    set(it, ABSENT, "proprietà assente (l'opzione si nasconde da sola)", "");
                    // l'opzione "Automatico" si nasconde, come fa anche la lettura normale
                    c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).edit().putBoolean("tempInternaAssente", true).apply();
                    break;
                }
                boolean ok = s != null && !Float.isNaN(s.insideTemp) && !s.insideSource.isEmpty();
                set(it, ok ? OK : FAIL, ok ? "letta " + s.insideTemp + " °C da " + s.insideSource : "nessun valore valido · " + Diag.value("clima.tempInterna"),
                        ok ? Math.round(s.insideTemp) + "°C" : "");
                break;
            }
            case "tema": {
                Boolean day = DayNight.applied();
                double sun = DayNight.sunElevation();
                String mode = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getString("theme", "auto");
                String base = "fonte " + DayNight.source() + " · applicato " + (day == null ? "—" : day ? "giorno" : "notte")
                        + (Double.isNaN(sun) ? "" : " · sole a " + Math.round(sun) + "°") + (DayNight.uiStuck() ? " · uiMode bloccato: ignorato" : "");
                if (!"auto".equals(mode)) {
                    set(it, OK, "scelta manuale " + mode + " · " + base, "");
                } else if (day == null || Double.isNaN(sun)) {
                    set(it, FAIL, "tema non ancora deciso · " + base, "");
                } else {
                    boolean wrong = !day && sun > 6 || day && sun < -6;
                    set(it, wrong ? FAIL : OK, (wrong ? "non coerente col sole · " : "coerente col sole · ") + base, "");
                }
                break;
            }
            case "temaSistema": {
                // 0.4.4: il tema scritto dal launcher nel sistema, riletto da Settings.System SKIN_THEME_CONFIG
                Boolean[] r = DayNight.systemCheck(c);
                if (!DayNight.skinPresent(c)) set(it, ABSENT, "tema di sistema non presente (SKIN_THEME_CONFIG assente): solo avviso "
                        + DayNight.SKIN_ACTION + " e UiModeManager · " + DayNight.systemTxt(), "");
                else if (r[0] == null) set(it, FAIL, "tema non ancora deciso · " + DayNight.systemTxt(), "");
                else if (r[1] == null) set(it, FAIL, "SKIN_THEME_CONFIG non leggibile · " + DayNight.systemTxt(), "");
                else set(it, r[0].equals(r[1]) && DayNight.systemApplied() ? OK : FAIL, "voluto " + (r[0] ? "giorno" : "notte") + " · sistema " + (r[1] ? "giorno" : "notte")
                        + (DayNight.systemApplied() ? "" : " · configurazione diversa")
                        + " · " + DayNight.systemTxt(), r[1] ? "☀" : "☾");
                break;
            }
            case "fari": {
                // 1.0.4 (punto B): con la prova interattiva si chiede di accendere le luci di posizione entro 10 s
                if (VehicleClient.lights() < 0) {
                    set(it, FAIL, "fari non leggibili · " + VehicleClient.lightsDescription(), "");
                    break;
                }
                if (!interactive) {
                    // 0.3.5 (punto 16): letture tutte a zero = fari spenti, è una lettura valida
                    set(it, OK, (VehicleClient.lights() == 1 ? "accesi" : "spenti") + " · leggibili · " + VehicleClient.lightsDescription(), "");
                    break;
                }
                boolean seenOn = VehicleClient.lights() == 1;
                for (int sec = 10; sec > 0 && !seenOn; sec--) {
                    int left = sec;
                    if (p != null) main.post(() -> p.onLightsCountdown(left));
                    Thread.sleep(1000);
                    if (v != null) v.refreshLightsNow();
                    seenOn = VehicleClient.lights() == 1;
                }
                if (p != null) main.post(() -> p.onLightsCountdown(0));
                // 0.3.5 (punto 16): ✗ solo se la lettura non riesce; spenti per 10 s = lettura valida "spenti"
                int lv = VehicleClient.lights();
                if (lv < 0) set(it, FAIL, "fari non leggibili · " + VehicleClient.lightsDescription(), "");
                else set(it, OK, (seenOn ? "accensione vista" : "spenti (accensione non vista in 10 s)") + " · "
                        + VehicleClient.lightsDescription(), "");
                break;
            }
            case "riscaldamentoBatteria": {
                // 0.3.5 (punto 11): SOLA LETTURA, nessun comando (ICarEvService.getDrivingBatteryHeatStatus, codice 14)
                int bh = v == null ? Integer.MIN_VALUE : v.batteryHeat();
                if (bh == Integer.MIN_VALUE) set(it, ABSENT, "getDrivingBatteryHeatStatus non leggibile (disponibile: no)", "");
                else set(it, OK, "disponibile: sì · getDrivingBatteryHeatStatus " + bh + (bh == 1 ? " (acceso)" : bh == 2 || bh == 0 ? " (spento)"
                        : " (valore sconosciuto)") + " · comando setDrivingBatteryHeatStatus non usato", String.valueOf(bh));
                break;
            }
            case "spegnimento": {
                // 0.3.5 (punto 3): SOLA LETTURA, non spegne niente: il servizio comfort (setPowerModeSwitch) c'è sì/no
                Boolean av = v == null ? null : v.powerOffAvailable();
                String g = s == null ? "?" : VehicleClient.gearName(s.gear);
                if (av == null) set(it, FAIL, "spegnimento disponibile: non si sa (servizio veicolo non collegato)", "");
                else set(it, av ? OK : ABSENT, "spegnimento disponibile: " + (av ? "sì" : "no") + " · metodo ICarComfortabletService.setPowerModeSwitch "
                        + (av ? "presente" : "assente") + " · getPowerModeSwitch non presente su SWI132 · marcia " + g + " (si spegne solo in P e ferma)", "");
                break;
            }
            case "animazione": {
                String color = com.mg4.launcher.ui.Prefs.CAR_COLORS[Math.max(0, Math.min(7,
                        c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getInt("carColor", 5)))];
                boolean on = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getBoolean("animazioneRicarica", true);
                if (com.mg4.launcher.ui.ChargeAnimView.errors > 0) set(it, FAIL, "errori di riproduzione: " + com.mg4.launcher.ui.ChargeAnimView.errors
                        + " · " + ChargePack.state(), "");
                else if (ChargePack.complete(c, color)) set(it, OK, "pacchetto " + color + " presente" + (on ? "" : " (animazione spenta)"), "");
                else if (ChargePack.failed()) set(it, FAIL, ChargePack.state(), "");
                else set(it, ABSENT, "non ancora disponibile · " + ChargePack.state(), "");
                break;
            }
            case "usb": {
                List<String> ports = m == null ? null : m.usbPortsSeen();
                if (ports == null) {
                    set(it, FAIL, "servizio media non risponde", "");
                    break;
                }
                StringBuilder names = new StringBuilder();
                for (String port : ports) names.append(names.length() == 0 ? "" : " · ")
                        .append("usb1".equals(port) ? "USB 1" : "usb2".equals(port) ? "USB 2" : port);
                String note = " · " + Diag.value("media.usb2");
                if (ports.isEmpty()) set(it, ABSENT, "nessuna chiavetta inserita" + note, "");
                else set(it, OK, "viste " + names + note, names.toString());
                break;
            }
            case "bluetooth": {
                if (m == null || !m.btLinked()) {
                    set(it, FAIL, "servizio Bluetooth musica non collegato", "");
                    break;
                }
                int st = m.btState();
                if (st != 2) set(it, ABSENT, "nessun telefono collegato in Bluetooth (stato " + st + ")", "");
                else set(it, OK, "telefono collegato · titoli da " + (MediaClient.btMetaSource.isEmpty() ? "— (aprire la sorgente Bluetooth)"
                        : MediaClient.btMetaSource) + " · " + Diag.value("media.bt.attesa"), "");
                break;
            }
            case "stazioni": {
                if (m == null || !m.radioLinked()) {
                    set(it, FAIL, "servizio radio non collegato", "");
                    break;
                }
                // 1.0.4 (punto C2): l'elenco può arrivare dopo l'avvio del controllo: si aspetta fino a 30 s
                MediaClient.State ms = m.last();
                long until = android.os.SystemClock.uptimeMillis() + 30_000;
                while ((ms.stations == null || ms.stations.isEmpty()) && (ms.favorites == null || ms.favorites.isEmpty())
                        && android.os.SystemClock.uptimeMillis() < until) {
                    Thread.sleep(1000);
                    ms = m.last();
                }
                int n = ms.stations == null ? 0 : ms.stations.size();
                int f = ms.favorites == null ? 0 : ms.favorites.size();
                String elenco = Diag.value("radio.elenco");
                if (n > 0 || f > 0) set(it, OK, n + " stazioni · " + f + " preferiti · " + elenco, String.valueOf(Math.max(n, f)));
                // 0.3.5 (punto 4): elenco vuoto senza aver aperto la Radio = "—" (da verificare), non un errore;
                // ✗ solo nel controllo manuale dopo aver aperto la Radio
                else if (!interactive || !MediaClient.radioOpened()) set(it, SKIPPED, "da verificare: elenco non ancora arrivato (Radio non aperta) · " + elenco, "");
                else set(it, FAIL, "nessuna stazione ricevuta dopo aver aperto la Radio (fare una ricerca) · " + elenco, "0");
                break;
            }
            case "tastiVolante": {
                if (!interactive) {
                    set(it, SKIPPED, "saltata (controllo automatico: serve premere un tasto)", "");
                    break;
                }
                // 0.3.5 (punto 15): passo guidato. "Premi AVANTI" (10 s), poi "premi INDIETRO" (10 s): si registrano i codici
                // grezzi visti (anche 297/298 dall'avviso SAIC) e si associano. Log tasti.volante.
                int[] got = new int[2];
                String[] via = new String[2];
                String[] seenAll = {"", ""};
                for (int step = 0; step < 2; step++) {
                    final int st = step;
                    got[st] = Integer.MIN_VALUE;
                    Object lock = new Object();
                    WheelKeys.setRawProbe((w, raw, down, lp) -> {
                        if (!down) return;
                        // volume e muto non contano
                        if (raw == android.view.KeyEvent.KEYCODE_VOLUME_UP || raw == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                                || raw == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) return;
                        synchronized (lock) {
                            seenAll[st] += (seenAll[st].isEmpty() ? "" : ", ") + raw + " (" + w + ")";
                            // vale il primo codice visto; il tasto multimediale (87/88) ha la precedenza sull'avviso
                            if (got[st] == Integer.MIN_VALUE || "tasto multimediale".equals(w) && !"tasto multimediale".equals(via[st])) {
                                got[st] = raw;
                                via[st] = w;
                            }
                            lock.notifyAll();
                        }
                    });
                    try {
                        for (int sec = 10; sec > 0 && got[st] == Integer.MIN_VALUE; sec--) {
                            int left = sec;
                            if (p != null) main.post(() -> p.onKeysStep(st == 0, left));
                            synchronized (lock) {
                                if (got[st] == Integer.MIN_VALUE) lock.wait(1000);
                            }
                        }
                        Thread.sleep(700);   // eventuale secondo evento dello stesso tasto (avviso + tasto multimediale)
                    } finally {
                        WheelKeys.setRawProbe(null);
                    }
                }
                if (p != null) main.post(() -> p.onKeysCountdown(0));
                WheelKeys.learn(got[0], via[0], got[1], via[1]);
                Diag.put("tasti.volante.passo", "AVANTI: " + (seenAll[0].isEmpty() ? "niente" : seenAll[0]) + " · INDIETRO: "
                        + (seenAll[1].isEmpty() ? "niente" : seenAll[1]));
                boolean both = got[0] != Integer.MIN_VALUE && got[1] != Integer.MIN_VALUE, same = both && got[0] == got[1] && via[0].equals(via[1]);
                String txt = "avanti " + (got[0] == Integer.MIN_VALUE ? "non visto" : got[0] + " (" + via[0] + ")")
                        + " · indietro " + (got[1] == Integer.MIN_VALUE ? "non visto" : got[1] + " (" + via[1] + ")")
                        + " · ascolto tasti multimediali " + Diag.value("media.tastiVolante.listener");
                if (both && !same) set(it, OK, txt, got[0] + "/" + got[1]);
                else set(it, FAIL, (same ? "stesso codice per avanti e indietro · " : "") + txt, "");
                break;
            }
            case "ready": {
                // 0.4.5: READY letto da SENSOR_EPTRDY (sola lettura); l'accensione non conta come READY
                if (s == null) {
                    set(it, FAIL, "servizio veicolo non collegato", "");
                    break;
                }
                String txt = "ready " + s.ready + " (" + s.readySource + ") · marcia " + VehicleClient.gearName(s.gear);
                if (s.ready == 1) set(it, OK, txt, "READY");
                else if (s.ready == 0) set(it, OK, txt, "—");
                else set(it, FAIL, txt + " · READY sconosciuto: l'animazione non si blocca", "");
                break;
            }
            case "tastoClima": {
                boolean orig = installed(c, com.mg4.launcher.apps.MgApps.HVAC_PKG), mg4 = installed(c, "com.mg4.clima");
                if (orig || mg4) set(it, OK, "apre " + (mg4 ? "MG4 Clima" + (orig ? " (c'è anche l'app Clima originale)" : "") : "l'app Clima originale"), "");
                else set(it, ABSENT, "né app Clima originale né MG4 Clima", "");
                break;
            }
            case "sediliVolante": {
                if (s == null) {
                    set(it, FAIL, "servizio veicolo non collegato", "");
                    break;
                }
                String cfg = "sedile sinistro " + (s.seatLeft >= 0 ? "sì" : "no") + " · sedile destro " + (s.seatRight >= 0 ? "sì" : "no")
                        + " · volante " + (s.wheelHeat >= 0 ? "sì" : "no");
                boolean any = s.seatLeft >= 0 || s.seatRight >= 0 || s.wheelHeat >= 0;
                set(it, any ? OK : ABSENT, cfg, "");
                break;
            }
            case "aggiornamento":
            case "avvisi": {
                String[] r = remote();
                if ("aggiornamento".equals(it.key)) {
                    if (r[0] != null) set(it, OK, "latest.json letto: versione " + r[0], r[0]);
                    else set(it, FAIL, "latest.json non raggiungibile (internet assente?)", "");
                } else {
                    if (r[1] != null) set(it, OK, "avvisi.json letto: " + r[1] + " avvisi", r[1]);
                    else set(it, FAIL, "avvisi.json non letto (internet assente?)", "");
                }
                break;
            }
            case "log": {
                if (!LogUploader.configured()) {
                    set(it, FAIL, "invio non configurato in questa versione", "");
                    break;
                }
                boolean on = logs == null || logs.enabled();
                String st = LogUploader.status();
                boolean bad = st != null && st.startsWith("ultimo tentativo non riuscito");
                if (!on) set(it, ABSENT, "invio spento dall'utente", "");
                else set(it, bad ? FAIL : OK, "stato: " + (st == null || st.isEmpty() ? "—" : st), "");
                break;
            }
            default:
                set(it, SKIPPED, "", "");
        }
    }

    private static String[] remoteCache;
    private static long remoteAt;

    /** latest.json e avvisi.json letti una volta per controllo (sola lettura). */
    private static synchronized String[] remote() {
        long now = android.os.SystemClock.uptimeMillis();
        if (remoteCache == null || now - remoteAt > 30_000) {
            remoteCache = Updater.probeRemote();
            remoteAt = now;
        }
        return remoteCache;
    }

    /** Esito salvato dell'ultimo controllo: true se la funzione è risultata ✗ o assente. */
    public static boolean failedOrAbsent(Context c, String key) {
        int st = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getInt("controllo_" + key, OK);
        return st == FAIL || st == ABSENT;
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- controllo automatico dopo un aggiornamento --------------------------------------------

    private static final String K_DONE_FOR = "controlloFattoPer";

    /** Se questa versione non è ancora stata controllata, il controllo silenzioso parte 60 s dopo. */
    public static void scheduleAfterUpdate(Context ctx, String version) {
        Context c = ctx.getApplicationContext();
        android.content.SharedPreferences sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        if (version.equals(sp.getString(K_DONE_FOR, ""))) return;
        main.postDelayed(() -> {
            if (version.equals(sp.getString(K_DONE_FOR, "")) || running) return;
            sp.edit().putString(K_DONE_FOR, version).apply();
            run(c, false, null);
        }, 60_000);
    }
}
