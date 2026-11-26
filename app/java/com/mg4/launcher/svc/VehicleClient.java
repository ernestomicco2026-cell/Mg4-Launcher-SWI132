package com.mg4.launcher.svc;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.Map;

/**
 * Veicolo, versione SWI132 (MG4 MY24). I servizi e i codici dei comandi sono identici alla SWI69 (EH32 Color Radio),
 * verificati nel firmware SWI132-29958-1100R43 (carapi, LocalBinder.queryClient, CarBMSManager, YFVehicleProperty):
 * <ul>
 *   <li>com.saicmotor.caradapter: "hub" (ICarAdapterService.queryClient(codice)) che restituisce i binder di
 *       ricarica, clima, luci, configurazione, quadro e impostazioni veicolo, come la home originale (carapi);</li>
 *   <li>gestore batteria "bms" del CarService (CarBms): percentuale, potenza e i comandi di ricarica, come la
 *       pagina Gestione ricarica dell'app Energia (com.saicmotor.saicev, schede EH32 EU/UK);</li>
 *   <li>proprietà del CarService (CarProps): pressione pneumatici e temperatura interna.</li>
 * </ul>
 */
public final class VehicleClient {
    private static final String PKG = "com.saicmotor.caradapter";
    private static final String ACTION = "com.saicmotor.caradapter.CarAdapterService";

    private static final String HUB = "com.saicmotor.carapi.ICarAdapterService";
    private static final int HUB_QUERY_CLIENT = 1;
    private static final int BIND_EV = 4, BIND_HVAC = 7, BIND_CONFIG = 2, BIND_LAMP = 13, BIND_IPK = 5, BIND_VS = 8;

    private static final String EV = "com.saicmotor.carapi.ev.ICarEvService";
    private static final int EV_PERCENT = 3;          // int  getChargePercentChange
    private static final int EV_STATUS = 4;           // int  getChargeStatus (stessi codici della SWI68)
    private static final int EV_REMAIN_TIME = 6;      // int  getChargingRemainTime (minuti)
    private static final int EV_DISCHG_TIME = 7;      // int  getDisChargingRemainTime
    private static final int EV_UNITS = 11;           // int  getDistanceUnits (0 = km, 1 = miglia)
    private static final int EV_RANGE = 12;           // int  getCurrentRange
    private static final int EV_CHG_CTRL = 20;        // int  getChargeCtrlStatus
    private static final int EV_DISCHG_CTRL = 24;     // int  getDisChargeCtrl
    /** 0.3.5 (solo studio, sola lettura): getDrivingBatteryHeatStatus (13 = set, MAI usato). */
    private static final int EV_GET_BATTERY_HEAT = 14;
    public static final int CTRL_START = 1, CTRL_STOP = 2, CTRL_PREPARING = 3;
    /** Correnti di ricarica AC ammesse dall'auto (codici 1..4, CarBMSManager.ALTNG_CHRG_CRNT_*). */
    public static final String[] CURRENT_LABELS = {"6 A", "8 A", "16 A", "Max"};

    private static final String AC = "com.saicmotor.carapi.hvac.ICarHvacService";
    private static final int AC_SWITCH_POWER = 5;     // void switchHvacPowerStatus()
    private static final int AC_GET_POWER = 6;        // bool getHvacPowerStatus
    private static final int AC_SWITCH_AUTO = 9;      // void switchAutoStatus()
    private static final int AC_GET_AUTO = 10;        // bool getAutoStatus
    private static final int AC_SET_FAN = 19;         // void setFanSpeed(int)
    private static final int AC_GET_FAN = 20;         // int  getFanSpeed
    private static final int AC_SET_DRV_TEMP = 21;    // void setDriverTemperature(float)
    private static final int AC_GET_DRV_TEMP = 22;    // float getDriverTemperature
    private static final int AC_GET_OUT_TEMP = 25;    // float getOutSideTemperature
    private static final int AC_SWITCH_AC = 7, AC_GET_AC = 8;                 // switchACStatus / getACStatus
    private static final int AC_SWITCH_DEFROST = 27, AC_GET_DEFROST = 28;     // parabrezza
    /**
     * 0.3.5: direzione dell'aria e lunotto (ICarHvacService R43). setFanDirection(int) 17 / getFanDirection 18 con gli
     * stessi sette modi SAIC di AirflowModes (verificato nell'app clima EH32, HvacMainFragment.onBlowClicked:
     * 0 viso, 1 viso+piedi, 2 piedi, 3 parabrezza+piedi, 4 parabrezza, 5 tutti, 6 parabrezza+viso).
     * switchRearDefrostStatus 29 (commuta, senza argomenti) / getRearDefrostStatus 30 (boolean).
     */
    private static final int AC_SET_FAN_DIR = 17, AC_GET_FAN_DIR = 18;
    private static final int AC_SWITCH_REAR_DEFROST = 29, AC_GET_REAR_DEFROST = 30;
    // ricircolo, come la barra clima della home originale: "cambia" senza argomenti; 0 interno, 1 esterno, 2 auto
    private static final int AC_SWITCH_LOOP = 15, AC_GET_LOOP = 16;
    // SWI132: sedili e volante riscaldati, come l'app Clima originale HVAC_my24_ll (HvacManager / t11_eh32):
    // switchSeatHeatStatus(sedile) = "premuto" (l'auto passa al livello successivo), getSeatHeatStatus(sedile) 0..3,
    // setSteeringWheelHeatStatus(1) = "premuto" (commuta), getSteeringWheelHeatStatus 3 = acceso
    private static final int AC_SWITCH_SEAT_HEAT = 31, AC_GET_SEAT_HEAT = 32, AC_SET_WHEEL_HEAT = 70, AC_GET_WHEEL_HEAT = 71;
    private static final int SEAT_DRIVER = 1, SEAT_PASSENGER = 2;
    // chiavi dello stato "previsto" (diverse dai codici già usati)
    private static final int K_SEAT_L = 70032, K_SEAT_R = 70033, K_WHEEL = 70071;

    // fendinebbia, come la schermata dell'auto originale: set(1) = "premuto" (commuta), get 1 = acceso
    private static final String LAMP = "com.saicmotor.carapi.lamp.ICarLampService";
    private static final int LAMP_SET_REAR_FOG = 30, LAMP_GET_REAR_FOG = 31, LAMP_SET_FRONT_FOG = 40, LAMP_GET_FRONT_FOG = 41;
    // 0.3.1: anabbaglianti, luci di posizione e posizione del comando luci, come la home originale SWI132 (ModeManager:
    // getDipdBeamLghtStatus / getVehSideLightMode / getLightSwitchPosition, schermata a riposo ScreenOffView)
    private static final int LAMP_GET_DIPPED = 42, LAMP_GET_SIDE = 43, LAMP_GET_SWITCH = 44;
    private static final String CONFIG = "com.saicmotor.carapi.config.ICarConfigService";
    private static final int CONFIG_FRONT_FOG = 60;   // getFrontFrogConfigData: 0 = fendinebbia anteriori presenti
    // assistenza alla guida, come l'app Impostazioni veicolo originale (SWI69 R45, verificata identica su SWI132 R43)
    private static final int CONFIG_LKA = 20;         // getLkaConfigData: 0 = LKA presente, 1 assente
    private static final int CONFIG_ONE_PEDAL = 43;
    // SWI132, come HvacService originale: getCarModelConfigData, getSeatHeatingConfigData (2 = sedili riscaldati
    // assenti), getSteeringWheelHeatConfig (0 = volante riscaldato presente, solo sui modelli EH32 elencati sotto)
    private static final int CONFIG_CAR_MODEL = 8, CONFIG_SEAT_HEAT = 23, CONFIG_WHEEL_HEAT = 52;
    /** AppCache.hasSteeringWheelHeatEH32CarModeConfig() dell'app Clima SWI132. */
    private static final int[] WHEEL_HEAT_MODELS = {69, 130, 131, 166, 173, 177, 132, 198, 200};   // getOnePedalConfig: 0 = One Pedal presente (H17A), 1 assente
    private static final String VS = "com.saicmotor.carapi.vs.ICarVehicleSettingService";
    // LKA (getLasMode/setLasMode): 0 nessuna richiesta, 1 spento, 2 LDW, 3 LDP, 4 LKA, 5 ELK
    private static final int VS_SET_LAS = 30, VS_GET_LAS = 31;
    // One Pedal: setSignalPedalState / getSignalPedalState (1 acceso, 0 spento) / getSignalPedalEnable (blocco:
    // 1 limite di carica, 2 sistema frenante, 3 condizioni, 0 nessuno)
    private static final int VS_SET_OPD = 162, VS_GET_OPD = 163, VS_GET_OPD_INHIBIT = 164;
    // chiavi dello stato "previsto" (diverse dai codici delle luci: 31 è anche il retronebbia)
    private static final int K_LKA = 80031, K_OPD = 80163;
    // assistente di guida (ACC/TJA) e pedale del freno (0.3.8 / 0.2.5): stesse proprietà dell'auto della SWI68
    private static final int VS_SET_ACC_TJA = 152, VS_GET_ACC_TJA = 153, VS_SET_PEDAL = 156, VS_GET_PEDAL = 157;
    private static final int K_ACC_TJA = 80153, K_PEDAL = 80157;
    private static final String IPK = "com.saicmotor.carapi.ipk.ICarIpkService";
    private static final int IPK_TYRE_UNIT = 29;      // 0 bar, 1 kPa, 2 psi (scelta dell'utente sul quadro)
    // 0.3.1: giorno/notte e luminosità del quadro (solo per la diagnostica tema.candidati: significato non verificato)
    private static final int IPK_DAY_NIGHT = 23, IPK_DIM_LEVEL = 25;

    /** Ventola come la barra del launcher: 0 = clima spento, 1..11, 15 = automatico. */
    public static final int FAN_OFF = 0, FAN_AUTO = 15;
    private static final int FAN_MIN = 1, FAN_MAX = 11;
    /** Per quanto tempo il valore impostato dall'utente prevale sulle letture (che arrivano in ritardo). */
    private static final long HOLD_MS = 4000;

    /**
     * Temperatura nella barra: MG4 EU/UK su SWI69 va da 16 a 30 °C, con LO = 15,5 e HI = 30,5
     * (CarModel.isTempRange16to30CarModel della home originale). Nel launcher: 15 = "LO", 31 = "HI".
     */
    public static final int TEMP_MIN = 15;   // "LO"
    public static final int TEMP_MAX = 31;   // "HI"
    private static final float CAR_LO = 15.5f, CAR_HI = 30.5f;

    /** Istantanea dello stato del veicolo letta dai servizi. */
    public static final class State {
        public boolean connected;
        /** 1.0.4: presa di ricarica BMS_CHRG_PLUG_CNCTNIO (0 cavo inserito, 1 no, -1 non letta). */
        public int plugProp = -1;
        /**
         * 0.4.5: READY vero da SENSOR_EPTRDY (servizio stato auto SAIC, ICarStateService.getSensorEptrdyState):
         * 1 = READY, 0 = non in READY, -1 = sconosciuto. L'accensione (quadro acceso) NON è READY.
         */
        public int ready = -1;
        public String readySource = "non letto";
        /** 0.4.5: marcia dal servizio stato auto (CarState: 1 P, 2 R, 3 N, 4-11 avanti, 0 tra due marce); -1 non letta. */
        public int gear = -1;
        public float speedKmh = Float.NaN;
        public float soc = -1;            // %
        public int rangeKm = -1;
        public int chargingStatus = -1;   // codici SAIC (uguali alla SWI68), vedi chargeText
        public int predictMinutes = -1;
        public float chargeKw = 0;
        /** 1.0.7: tensione della batteria di trazione (getPowerBatteryVol), -1 = non letta. */
        public float batteryVolts = -1;
        public int chargeLimit = -1;      // % (convertito dal codice SAIC)
        public int chargeLimitCode = -1;  // 1..7
        public int currentLimit = -1;     // 1..4 (6 A, 8 A, 16 A, Max), 0 = non disponibile
        public int chargeLock = -1;       // 0.4.0 (punto 6): 1 bloccata, 2 sbloccata, -1 non letta
        public int chargeControl = -1;    // 1 in carica, 2 fermo, 3 in preparazione
        public int dischargeControl = -1; // idem per il V2L
        public int dischargeLimit = -1;   // % sotto cui il V2L si ferma
        public int dischargeMinutes = -1;
        public boolean bmsAvailable;
        public int distUnit = -1;         // 0 km, 1 miglia (dal quadro)
        public int tyreUnitCluster = -1;  // unità pressione del quadro: 0 bar, 1 kPa, 2 psi
        public int dischargeStatus = -1;
        public int loopMode = -1;         // ricircolo: 0 interno, 1 esterno, 2 auto, -1 non supportato
        public int acTemp = -1;           // 15 = LO, 16..30, 31 = HI
        public int hvacPower = -1;
        public int acOn = -1, autoOn = -1, defrostOn = -1;   // 1 acceso, 0 spento, -1 sconosciuto
        /** 0.3.5: direzione dell'aria (modi SAIC 0..6) e lunotto (1 acceso, 0 spento); -1 non disponibile. */
        public int blowerMode = -1, rearDefrostOn = -1;
        /** 0.3.5 (solo studio): riscaldamento intelligente batteria, valore grezzo; MIN_VALUE non leggibile. */
        public int batteryHeat = Integer.MIN_VALUE;
        public int seatLeft = -1, seatRight = -1;          // 0..3, -1 non disponibile (dalla configurazione dell'auto)
        /** 0.3.1: limite di ricarica supportato (lettura valida dal gestore batteria). */
        public boolean chargeLimitSupported;
        /** 0.3.1: ricarica programmata (come l'app Energia): -1 non supportata, 0 spenta, 1 attiva; orari "HH:MM". */
        public int scheduled = -1;
        public String schedStart = "", schedEnd = "";
        /** 0.3.1: fari (1 accesi, 0 spenti, -1 non leggibili). */
        public int headlights = -1;
        /** 0.3.1: fonte della temperatura interna ("" = nessuna fonte valida). */
        public String insideSource = "";
        public int wheelHeat = -1;                         // 1 acceso, 0 spento, -1 non disponibile
        public float[] tireBar = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};   // AS, AD, PS, PD
        public float insideTemp = Float.NaN;
        public int rearFog = -1, frontFog = -1;           // 1 acceso, 0 spento, -1 non disponibile
        public boolean hasFrontFog;
        public int lka = -1;              // 1 acceso, 0 spento, -1 non disponibile
        public int lkaMode = -1;          // valore grezzo dell'auto (vedi VS_GET_LAS)
        /** Assistente di guida (valori dell'auto): 1 ACC, 2 TJA, 4 spento; -1 non disponibile. */
        public int accTja = -1;
        /** Sensibilità del pedale del freno: 1 Comfort, 0 Medium, 2 Sport; -1 non disponibile. */
        public int pedal = -1;
        public int opd = -1;              // One Pedal Drive: 1 acceso, 0 spento, -1 non disponibile
        public int opdInhibit;            // motivo del blocco OPD (1 in carica, 2 sistema, 3 condizioni), 0 nessuno
        public int fanLevel = -1;         // 0 spenta, 1..max, 15 auto
        public int fanMin = FAN_MIN, fanMax = FAN_MAX;
        public float outsideTemp = -10000f;   // °C
    }

    public interface Listener {
        void onVehicleState(State s);
    }

    private final Context ctx;
    private final ServiceLink hub;
    private final CarBms bms;
    private final Map<Integer, IBinder> subs = new HashMap<>();
    private final HandlerThread thread = new HandlerThread("mg4-vehicle");
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile State last = new State();
    private volatile int pendingTemp = -1, pendingFan = -1;
    private volatile int heldTemp = -1, heldFan = -1;
    private volatile long tempHoldUntil, fanHoldUntil;
    private boolean running;

    public VehicleClient(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        hub = new ServiceLink(ctx, "veicolo", ServiceLink.byAction(PKG, ACTION));
        bms = new CarBms(ctx);
        hub.setOnConnected(() -> {
            synchronized (subs) {
                subs.clear();
            }
            if (bg != null) bg.post(this::poll);
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public State last() {
        return last;
    }

    public void start() {
        if (running) return;
        running = true;
        if (bg == null) {
            thread.start();
            bg = new Handler(thread.getLooper());
        }
        hub.start();
        bms.start();
        bg.post(loop);
    }

    public void stop() {
        pause();
        hub.stop();
    }

    /** Sospende le letture (launcher non visibile) senza scollegarsi dai servizi. */
    public void pause() {
        running = false;
        if (bg != null) {
            bg.removeCallbacks(loop);
            bg.removeCallbacks(powerLoop);
        }
    }

    // ---- 1.0.7: consumo istantaneo (potenza della batteria) ------------------------------------------------------
    /** kW smussati: positivo = consumo, negativo = recupero; NaN = non leggibile. Sul thread principale. */
    public interface PowerListener {
        void onPower(float kw);
    }

    private volatile PowerListener powerListener;
    private float powerSmooth = Float.NaN;
    private static volatile boolean powerLogged;
    private static final long POWER_MS = 500;   // al massimo 2 volte al secondo

    /**
     * Potenza istantanea = corrente × tensione della batteria di trazione (IVehicleChargingService 7
     * getActualChargingCurrent, 64 getPowerBatteryVol): nei log, a ricarica ferma, la corrente è positiva in consumo e
     * negativa in recupero. Solo letture. null = ferma. Solo mentre il launcher è visibile (pause() la ferma).
     */
    public void setPowerListener(PowerListener l) {
        powerListener = l;
        restartPower();
    }

    /**
     * Riparte da zero il giro della potenza. Tutto sul thread di sfondo: lì il giro non può essere a metà, quindi non
     * restano mai due giri insieme (che leggerebbero 4 volte al secondo).
     */
    private void restartPower() {
        Handler h = bg;
        if (h == null) return;
        h.post(() -> {
            h.removeCallbacks(powerLoop);
            powerSmooth = Float.NaN;
            if (powerListener != null) h.post(powerLoop);
        });
    }

    /** Toglie l'ascoltatore solo se è ancora quello dato (una home ricostruita ha già messo il suo). */
    public void clearPowerListener(PowerListener l) {
        if (powerListener == l) setPowerListener(null);
    }

    private final Runnable powerLoop = new Runnable() {
        @Override
        public void run() {
            PowerListener l = powerListener;
            if (l == null || !running) return;
            float kw = Float.NaN;
            try {
                CarProps p = props;
                if (p != null && p.ready()) {
                    float a = p.getFloat(BMS_PACK_CRNT), v = p.getFloat(BMS_PACK_VOL);
                    // tensione fuori scala o corrente assurda = dato non valido
                    if (!Float.isNaN(a) && !Float.isNaN(v) && v > 100 && v < 1000 && Math.abs(a) < 1000) kw = currentSign() * a * v / 1000f;
                    if (!powerLogged) {
                        powerLogged = true;
                        Diag.put("consumo.fonte", "CarService BMS_PACK_CRNT 0x2160f407 × BMS_PACK_VOL 0x2160f406 (come l'app Energia SWI132) · "
                                + (Float.isNaN(kw) ? "non leggibile (" + a + " A / " + v + " V)"
                                : String.format(java.util.Locale.US, "primo valore %.1f A × %.0f V = %.1f kW · segno %s", a, v, kw, signNote())));
                    }
                }
            } catch (Exception ignored) {
            }
            // smussatura esponenziale: la barra non balla a ogni lettura
            if (Float.isNaN(kw)) powerSmooth = Float.NaN;
            else powerSmooth = Float.isNaN(powerSmooth) ? kw : powerSmooth + 0.3f * (kw - powerSmooth);
            final float out = powerSmooth;
            main.post(() -> {
                PowerListener cur = powerListener;
                if (cur != null) cur.onPower(out);
            });
            if (bg != null) bg.postDelayed(this, POWER_MS);
        }
    };

    /**
     * 0.3.6: BMS_PACK_CRNT / BMS_PACK_VOL del CarService (proprietà SAIC lette dall'app Energia della SWI132, firmware R43).
     * Il segno della corrente sulla SWI132 non è verificato in auto: si impara in ricarica (la corrente che entra nella
     * batteria è "recupero"): se in ricarica la corrente è positiva il segno si inverte. Finché non si è vista una
     * ricarica vale come la SWI68 (positiva = consumo). Salvato nelle preferenze (consumoSegno).
     */
    static final int BMS_PACK_CRNT = 0x2160f407, BMS_PACK_VOL = 0x2160f406;
    private int signCache;

    private int currentSign() {
        if (signCache == 0) signCache = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getInt("consumoSegno", 1);
        return signCache;
    }

    private String signNote() {
        return ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).contains("consumoSegno")
                ? (currentSign() > 0 ? "imparato in ricarica: positiva = consumo" : "imparato in ricarica: positiva = recupero")
                : "non ancora visto in ricarica: positiva = consumo (da verificare)";
    }

    /** In ricarica vera (cavo inserito, corrente di almeno 3 A) si impara il segno; una riga di log quando cambia. */
    private void learnSign(State s) {
        CarProps p = props;
        if (p == null || !p.ready() || !isCharging(s.chargingStatus)) return;
        float a = p.getFloat(BMS_PACK_CRNT);
        if (Float.isNaN(a) || Math.abs(a) < 3f) return;
        int sign = a > 0 ? -1 : 1;   // entra nella batteria = recupero (negativo)
        android.content.SharedPreferences sp = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        if (sp.getInt("consumoSegno", 0) == sign) return;
        sp.edit().putInt("consumoSegno", sign).apply();
        signCache = sign;
        Diag.put("consumo.segno", "imparato in ricarica: corrente " + String.format(java.util.Locale.US, "%.1f", a) + " A → "
                + (sign > 0 ? "positiva = consumo" : "positiva = recupero"));
    }

    /** true se la potenza della batteria si legge (per mostrare l'opzione nelle impostazioni). */
    public static boolean powerReadable(State s) {
        return s != null && s.connected && s.batteryVolts > 100 && s.batteryVolts < 1000;
    }

    public void resume() {
        if (bg == null || running) return;
        running = true;
        bg.post(loop);
        if (powerListener != null) restartPower();
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            poll();
            bg.postDelayed(this, 2000);
        }
    };

    /** 0.3.6 (diagnostica completa): servizio del hub (BIND_CODE_CAR_*) per le sole letture; null se non raggiungibile. */
    public IBinder subService(int code) {
        try {
            return sub(code);
        } catch (Throwable e) {
            return null;
        }
    }

    private IBinder sub(int code) throws RemoteException {
        synchronized (subs) {
            IBinder b = subs.get(code);
            if (b != null && b.isBinderAlive()) return b;
        }
        IBinder h = hub.binder();
        if (h == null) return null;
        IBinder b = Rpc.callBinder(h, HUB, HUB_QUERY_CLIENT, p -> p.writeInt(code));
        if (b != null) {
            synchronized (subs) {
                subs.put(code, b);
            }
        }
        return b;
    }

    private void forget(int code) {
        synchronized (subs) {
            subs.remove(code);
        }
    }

    /** Limite di ricarica: codice 1 = 40% … 7 = 100% (CarBMSManager.CHRG_TRGT_SOC_*). */
    static int limitPercent(int code) {
        if (code >= 1 && code <= 7) return (code + 3) * 10;
        return -1;
    }

    /** Temperatura dell'auto (°C, a mezzi gradi) → valore della barra (15 = LO … 31 = HI). */
    static int tempFromCar(float f) {
        if (Float.isNaN(f) || f <= 0) return -1;
        if (f <= CAR_LO + 0.01f) return TEMP_MIN;
        if (f >= CAR_HI - 0.01f) return TEMP_MAX;
        return Math.max(TEMP_MIN + 1, Math.min(TEMP_MAX - 1, Math.round(f)));
    }

    /** Valore della barra → temperatura da inviare all'auto. */
    static float tempToCar(int v) {
        if (v <= TEMP_MIN) return CAR_LO;
        if (v >= TEMP_MAX) return CAR_HI;
        return v;
    }

    private static boolean ok(float v) {
        return !Float.isNaN(v);
    }

    private int distUnit = -1;

    private void poll() {
        State s = new State();
        s.connected = hub.isConnected();
        long now = android.os.SystemClock.uptimeMillis();
        if (s.connected) {
            try {
                IBinder e = sub(BIND_EV);
                if (e != null) {
                    s.soc = Rpc.callInt(e, EV, EV_PERCENT);
                    s.rangeKm = Rpc.callInt(e, EV, EV_RANGE);
                    s.chargingStatus = Rpc.callInt(e, EV, EV_STATUS);
                    s.predictMinutes = Rpc.callInt(e, EV, EV_REMAIN_TIME);
                    s.dischargeStatus = Rpc.callInt(e, EV, EV_DISCHG_CTRL);
                    if (isDischarging(s.chargingStatus)) s.dischargeMinutes = Rpc.callInt(e, EV, EV_DISCHG_TIME);
                    if (distUnit < 0) distUnit = Rpc.callInt(e, EV, EV_UNITS);
                    s.distUnit = distUnit;
                    Diag.put("veicolo.autonomia", s.rangeKm + " (unità " + distUnit + ")");
                    Diag.put("veicolo.statoRicarica", s.chargingStatus);
                    Diag.put("veicolo.minutiRicarica", s.predictMinutes);
                    Diag.put("veicolo.comandoRicarica(ev)", Rpc.callInt(e, EV, EV_CHG_CTRL));
                    Diag.put("veicolo.statoScarica(V2L)", s.dischargeStatus);
                    if (batteryHeatReadable) {
                        try {
                            s.batteryHeat = Rpc.callInt(e, EV, EV_GET_BATTERY_HEAT);   // sola lettura
                        } catch (Exception ex) {
                            batteryHeatReadable = false;
                            Diag.put("ricarica.riscaldamentoBatteria", "non leggibile: " + ex.getClass().getSimpleName());
                        }
                    }
                }
            } catch (Exception ex) {
                Diag.error("ricarica", ex.toString());
                forget(BIND_EV);
            }
            try {
                IBinder a = sub(BIND_HVAC);
                if (a != null) {
                    sendPending(a);
                    boolean power = Rpc.callBool(a, AC, AC_GET_POWER);
                    boolean auto = Rpc.callBool(a, AC, AC_GET_AUTO);
                    float drv = Rpc.callFloat(a, AC, AC_GET_DRV_TEMP);
                    int readTemp = tempFromCar(drv);
                    s.acTemp = (now < tempHoldUntil && heldTemp > 0) ? heldTemp : readTemp;
                    int speed = Rpc.callInt(a, AC, AC_GET_FAN);
                    int readFan = !power ? FAN_OFF : auto ? FAN_AUTO : speed;
                    s.fanLevel = (now < fanHoldUntil && heldFan >= 0) ? heldFan : readFan;
                    s.hvacPower = power ? 1 : 0;
                    s.outsideTemp = Rpc.callFloat(a, AC, AC_GET_OUT_TEMP);
                    // 0.9.3: valore non valido (flag del firmware, come il SystemUI originale) o -40 di "nessun dato": non si mostra
                    float rawOut = s.outsideTemp;
                    int outV = outValid;
                    // 0.3.8/0.2.5: qui "nessun dato" vale 0, uguale a 0 °C veri: lo 0 si mostra solo se l'auto lo dà per valido
                    boolean outBad = outV == 1 || rawOut <= -39.5f || (rawOut == 0f && outV != 0);
                    if (outBad) s.outsideTemp = -10000f;
                    else lastOutside = rawOut;
                    Diag.put("clima.tempEsterna", rawOut + (outV == Integer.MIN_VALUE ? "" : " · validità " + (outV == 0 ? "ok" : outV == 1 ? "NON valida" : String.valueOf(outV)))
                            + (outBad ? " → nascosta" : ""));
                    Diag.put("clima.tempGuidatore", drv + (s.acTemp != readTemp ? " (richiesta " + s.acTemp + ")" : ""));
                    Diag.put("clima.ventola", speed + (auto ? " auto" : "") + (s.fanLevel != readFan ? " (richiesta " + s.fanLevel + ")" : ""));
                    Diag.put("clima.accensione", power);
                    s.acOn = held(AC_GET_AC, Rpc.callBool(a, AC, AC_GET_AC) ? 1 : 0, now);
                    s.autoOn = held(AC_GET_AUTO, auto ? 1 : 0, now);
                    s.defrostOn = held(AC_GET_DEFROST, Rpc.callBool(a, AC, AC_GET_DEFROST) ? 1 : 0, now);
                    Diag.put("clima.ac/auto/sbrinamento", s.acOn + " / " + s.autoOn + " / " + s.defrostOn);
                    // 0.3.5: letture facoltative, isolate (un errore non ferma gli altri comandi)
                    if (fanDirSupported) {
                        try {
                            int mode = Rpc.callInt(a, AC, AC_GET_FAN_DIR);
                            s.blowerMode = held(AC_GET_FAN_DIR, mode >= 0 && mode <= 6 ? mode : -1, now);
                        } catch (Exception ex) {
                            fanDirSupported = false;
                            Diag.put("clima.direzione", "non leggibile: " + ex.getClass().getSimpleName());
                        }
                    }
                    if (rearDefrostSupported) {
                        try {
                            s.rearDefrostOn = held(AC_GET_REAR_DEFROST, Rpc.callBool(a, AC, AC_GET_REAR_DEFROST) ? 1 : 0, now);
                        } catch (Exception ex) {
                            rearDefrostSupported = false;
                            Diag.put("clima.lunotto", "non leggibile: " + ex.getClass().getSimpleName());
                        }
                    }
                    Diag.put("clima.direzione/lunotto", s.blowerMode + " / " + s.rearDefrostOn);
                    if (loopSupported) {
                        try {
                            int loop = Rpc.callInt(a, AC, AC_GET_LOOP);
                            s.loopMode = held(AC_GET_LOOP, loop >= 0 && loop <= 2 ? loop : -1, now);
                            Diag.put("clima.ricircolo", loop + " (0 interno, 1 esterno, 2 auto)");
                        } catch (Exception ex) {
                            loopSupported = false;   // non si richiede più: il pulsante resta nascosto
                            Diag.put("clima.ricircolo", "non supportato: " + ex.getClass().getSimpleName());
                        }
                    }
                    try {
                        readComfort(a, s, now);
                    } catch (Exception ex) {
                        Diag.error("comfort", ex.toString());
                    }
                }
            } catch (Exception ex) {
                Diag.error("clima", ex.toString());
                forget(BIND_HVAC);
            }
            readCluster(s);
        }
        readLamps(s);
        readBms(s, now);
        readCarProps(s);
        s.headlights = DayNight.headlights();
        readChargeSignals(s);
        last = s;
        lastShared = s;
        main.post(() -> {
            if (listener != null) listener.onVehicleState(s);
        });
    }

    private boolean loopSupported = true;
    private boolean fanDirSupported = true, rearDefrostSupported = true, batteryHeatReadable = true;

    /** Unità della pressione scelta sul quadro (per "Automatica"). */
    private void readCluster(State s) {
        long t = android.os.SystemClock.uptimeMillis();
        if (t - dayNightAt > 10_000) {
            dayNightAt = t;
            try {
                IBinder ipk = sub(BIND_IPK);
                if (ipk != null) DayNight.setCluster(Rpc.callInt(ipk, IPK, IPK_DAY_NIGHT), Rpc.callInt(ipk, IPK, IPK_DIM_LEVEL));
            } catch (Exception e) {
                DayNight.setCluster(Integer.MIN_VALUE, Integer.MIN_VALUE);
            }
        }
        if (clusterUnit != Integer.MIN_VALUE && android.os.SystemClock.uptimeMillis() - clusterAt < 60_000) {
            s.tyreUnitCluster = clusterUnit;
            return;
        }
        try {
            IBinder ipk = sub(BIND_IPK);
            if (ipk != null) {
                clusterUnit = Rpc.callInt(ipk, IPK, IPK_TYRE_UNIT);
                clusterAt = android.os.SystemClock.uptimeMillis();
                Diag.put("tpms.unitàQuadro", clusterUnit + " (0 bar, 1 kPa, 2 psi)");
            }
        } catch (Exception e) {
            forget(BIND_IPK);
        }
        s.tyreUnitCluster = clusterUnit == Integer.MIN_VALUE ? -1 : clusterUnit;
    }

    private int clusterUnit = Integer.MIN_VALUE;
    private long clusterAt, dayNightAt;

    /** Gestore batteria: percentuale con decimali, potenza, limite e comandi di ricarica (come l'app Energia). */
    private void readBms(State s, long now) {
        if (!bms.isReady()) {
            Diag.put("bms.letture", "non disponibile");
            return;
        }
        s.bmsAvailable = true;
        float socDsp = bms.getFloat(CarBms.PACK_SOC_DSP);
        if (ok(socDsp) && socDsp >= 0 && socDsp <= 100) s.soc = socDsp;
        int code = bms.getInt(CarBms.CHRG_TRGT_SOC);
        if (code >= 1 && code <= 7) {
            s.chargeLimitCode = code;
            s.chargeLimit = limitPercent(code);
            s.chargeLimitSupported = true;
        }
        Diag.put("ricarica.limite", "supportato " + (s.chargeLimitSupported ? "sì" : "no") + " · valore letto " + code
                + (s.chargeLimit > 0 ? " (" + s.chargeLimit + "%)" : "") + " · scelta " + limitMode);
        readSchedule(s, now);
        int lim = held(CarBms.CHRG_TRGT_SOC, s.chargeLimitCode, now);
        if (lim >= 1 && lim != s.chargeLimitCode) {
            s.chargeLimitCode = lim;
            s.chargeLimit = limitPercent(lim);
        }
        int cur = bms.getInt(CarBms.ALTNG_CHRG_CRNT);
        s.currentLimit = held(CarBms.ALTNG_CHRG_CRNT, cur >= 0 && cur <= 4 ? cur : -1, now);
        int ctl = bms.getInt(CarBms.CHRG_CTRL);
        s.chargeControl = held(CarBms.CHRG_CTRL, ctl == Integer.MIN_VALUE ? -1 : ctl, now);
        int v2x = bms.getInt(CarBms.V2X);
        s.dischargeControl = held(CarBms.V2X, v2x == Integer.MIN_VALUE ? -1 : v2x, now);
        int lk = bms.getInt(CarBms.CHRG_LOCK);
        s.chargeLock = held(CarBms.CHRG_LOCK, lk == 1 || lk == 2 ? lk : -1, now);
        Diag.putQuiet("ricarica.bloccoPresa", s.chargeLock > 0 ? (s.chargeLock == 1 ? "bloccata" : "sbloccata") : "non letto");
        int dl = bms.getInt(CarBms.V2X_SP_SOC);
        s.dischargeLimit = dl >= 0 && dl <= 7 ? (dl + 1) * 10 : -1;
        float amps = bms.getFloat(CarBms.PACK_CRNT);
        float volts = bms.getFloat(CarBms.PACK_VOL);
        if (ok(amps) && ok(volts)) s.chargeKw = Math.abs(amps * volts) / 1000f;
        if (s.rangeKm <= 0) {
            int r = bms.getInt(CarBms.ESTD_ELEC_RNG);
            if (r > 0 && r < 2000) s.rangeKm = r;
        }
        Diag.put("bms.soc", socDsp);
        Diag.put("veicolo.limiteRicarica", "codice " + code + " = " + s.chargeLimit + "%");
        Diag.put("ricarica.comandi", "corrente " + cur + " · avvia/ferma " + ctl + " · V2L " + v2x + " · limite V2L " + dl
                + " · unità " + distUnit);
        Diag.putQuiet("veicolo.correnteA/tensioneV", amps + " / " + volts);
    }

    /** Scelta dell'utente per il limite di ricarica (Automatico / Mostra / Nascondi), solo per la diagnostica. */
    public static volatile String limitMode = "auto";

    /** Ricarica programmata: si mostra solo se l'auto risponde con un interruttore valido (1, 2 o 3) e orari validi. */
    private void readSchedule(State s, long now) {
        if (now - schedAt < 10_000 && schedCache != null) {
            s.scheduled = schedCache[0];
            s.schedStart = schedText[0];
            s.schedEnd = schedText[1];
            return;
        }
        schedAt = now;
        int sw = bms.getInt(CarBms.RESV_SWITCH);
        int sh = bms.getInt(CarBms.RESV_START_H), sm = bms.getInt(CarBms.RESV_START_M);
        int eh = bms.getInt(CarBms.RESV_END_H), em = bms.getInt(CarBms.RESV_END_M);
        boolean timesOk = sh >= 0 && sh <= 23 && sm >= 0 && sm <= 59 && eh >= 0 && eh <= 23 && em >= 0 && em <= 59;
        int st = sw >= 1 && sw <= 3 && timesOk ? (sw == 2 ? 0 : 1) : -1;
        st = held(CarBms.RESV_SWITCH, st, now);
        schedCache = new int[]{st};
        schedText[0] = timesOk ? String.format(java.util.Locale.US, "%02d:%02d", sh, sm) : "";
        schedText[1] = timesOk ? String.format(java.util.Locale.US, "%02d:%02d", eh, em) : "";
        s.scheduled = st;
        s.schedStart = schedText[0];
        s.schedEnd = schedText[1];
        Diag.put("ricarica.programmata", (st < 0 ? "non supportata" : st == 1 ? "attiva" : "spenta") + " · interruttore " + sw
                + " · " + sh + ":" + sm + "–" + eh + ":" + em);
    }

    private long schedAt;
    private int[] schedCache;
    private final String[] schedText = {"", ""};

    /** Attiva o spegne la ricarica programmata (stessi valori dell'app Energia: 1 attiva, 2 spenta). */
    public void setScheduled(boolean on) {
        if (bg == null) return;
        hold(CarBms.RESV_SWITCH, on ? 1 : 0);
        schedAt = 0;
        bg.post(() -> {
            boolean sent = bms.setInt(CarBms.RESV_SWITCH, on ? 1 : 2);
            Diag.put("ricarica.ultimoComando", "programmata " + (on ? "attiva" : "spenta") + (sent ? " · inviato" : " · non inviato"));
            poll();
        });
    }

    // ---- TPMS e validità della temperatura esterna dal CarService (0.9.5: la temperatura interna non c'è) ----------------------------------------------

    private CarProps props;
    private static volatile CarProps shared;

    /** Lettore del CarService condiviso (null finché il servizio veicolo non l'ha creato). */
    public static CarProps carProps() {
        return shared;
    }

    private long propsAt;
    private final float[] tireCache = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
    private volatile int outValid = Integer.MIN_VALUE;
    private volatile float lastOutside = Float.NaN;

    private void readCarProps(State s) {
        if (props == null) {
            props = new CarProps(ctx);
            props.connect();
            shared = props;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (props.ready() && now - propsAt > 5000) {
            propsAt = now;
            int[] ids = {CarProps.TIRE_FL, CarProps.TIRE_FR, CarProps.TIRE_RL, CarProps.TIRE_RR};
            StringBuilder raw = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                int v = props.getInt(ids[i]);
                tireCache[i] = CarProps.toBar(v);
                raw.append(i == 0 ? "" : " / ").append(v == Integer.MIN_VALUE ? "?" : String.valueOf(v));
            }
            Diag.put("pneumatici (grezzo AS/AD/PS/PD)", raw);
            // 0.9.3: temperatura esterna, validità (0 valida, 1 non valida)
            outValid = props.getIntQuiet(CarProps.HVAC_TEMPERATURE_OUTCARV);
            readInside(s.outsideTemp);
        }
        System.arraycopy(tireCache, 0, s.tireBar, 0, 4);
        s.insideTemp = insideCache;
        s.insideSource = insideSrc;
    }

    private volatile float insideCache = Float.NaN;
    private volatile String insideSrc = "";

    /**
     * 0.3.1: temperatura interna solo da una fonte valida. Mai l'esterna al posto dell'interna: un valore uguale all'esterna
     * (HVAC_AMBIENT_TEMPERATURE su queste auto è l'esterna), 0 o fuori scala non vale, e allora si mostra "--".
     */
    private void readInside(float outside) {
        boolean outOk = outside > -60 && outside < 70;
        String[] names = {"HVAC_TEMPERATURE_CURRENT", "HVAC_AMBIENT_TEMPERATURE"};
        int[] ids = {CarProps.HVAC_TEMPERATURE_CURRENT, CarProps.HVAC_AMBIENT_TEMPERATURE};
        StringBuilder d = new StringBuilder();
        float found = Float.NaN;
        String src = "";
        for (int i = 0; i < ids.length; i++) {
            Boolean av = props.available(ids[i]);
            float v = Boolean.FALSE.equals(av) ? Float.NaN : props.getFloat(ids[i]);
            boolean sameAsOut = outOk && !Float.isNaN(v) && Math.abs(v - outside) < 0.6f;
            boolean ok = valid(v) && !sameAsOut;
            d.append(i == 0 ? "" : " · ").append(names[i]).append(' ').append(Float.isNaN(v) ? "non leggibile" : String.valueOf(v))
                    .append(ok ? " valida" : sameAsOut ? " = esterna, scartata" : Float.isNaN(v) ? "" : " non valida");
            if (ok && src.isEmpty()) {
                found = v;
                src = names[i];
            }
        }
        // 0.3.2: proprietà assente su questa auto (elenco già letto) → l'opzione Automatico della temperatura interna si
        // nasconde da sola
        Boolean avCur = props.available(CarProps.HVAC_TEMPERATURE_CURRENT);
        if (avCur != null) {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
            if (sp.getBoolean("tempInternaAssente", false) != !avCur) sp.edit().putBoolean("tempInternaAssente", !avCur).apply();
        }
        insideCache = found;
        insideSrc = src;
        Diag.put("clima.tempInterna", (src.isEmpty() ? "-- (nessuna fonte valida)" : found + " da " + src) + " · esterna "
                + (outOk ? String.valueOf(outside) : "non valida") + " · " + d);
    }

    /**
     * Segnali per l'animazione di ricarica (SWI69 R45, famiglia EH32: vale anche per SWI131/SWI132):
     * <ul>
     *   <li>presa: proprietà BMS della SWI68 (YFVehicleProperty BMS_CHRG_PLUG_CNCTNIO 557904904: 0 inserito, 1 no);</li>
     *   <li>0.4.5: READY vero da SENSOR_EPTRDY. Servizio stato auto SAIC: CarAdapterService, codice 11
     *       (BIND_CODE_CAR_STATE), interfaccia com.saicmotor.carapi.carstate.ICarStateService, 8 = getSensorEptrdyState
     *       (CarState: 1 READY, 0 non READY), 3 = isServiceReady, 5 = getGearState. Con il servizio non pronto o un
     *       errore READY resta sconosciuto. L'accensione (2 = quadro acceso) non si usa più come READY;</li>
     *   <li>velocità PERF_VEHICLE_SPEED.</li>
     * </ul>
     */
    private static final int BIND_STATE = 11, ST_SERVICE_READY = 3, ST_GEAR = 5, ST_EPTRDY = 8;
    private static final String STATE_DESC = "com.saicmotor.carapi.carstate.ICarStateService";
    private volatile int plugCache = -1;

    private void readChargeSignals(State s) {
        if (props != null && props.ready()) {
            String[] why = new String[1];
            Integer plug = props.readLight(CarProps.BMS_CHRG_PLUG_CNCTNIO, false, why);
            plugCache = plug == null ? -1 : plug;
            Diag.putQuiet("ricarica.presa.segnale", plug == null ? "non letta (" + why[0] + ")" : (plug == 0 ? "cavo inserito" : "cavo non inserito") + " (" + plug + ")");
        }
        s.plugProp = plugCache;
        s.speedKmh = speedKmh();
        // 0.3.6: tensione della batteria di trazione (per l'opzione del consumo istantaneo) e segno della corrente
        if (props != null && props.ready()) {
            float bv = props.getFloat(BMS_PACK_VOL);
            s.batteryVolts = Float.isNaN(bv) ? -1 : bv;
            try {
                learnSign(s);
            } catch (Throwable ignored) {
            }
        }
        try {
            IBinder st = sub(BIND_STATE);
            if (st == null) {
                s.readySource = "servizio stato auto non disponibile";
            } else {
                Boolean ok = Rpc.call(st, STATE_DESC, ST_SERVICE_READY, Rpc.NONE, p -> p.readInt() != 0);
                int r = Rpc.callInt(st, STATE_DESC, ST_EPTRDY);
                int g = Rpc.callInt(st, STATE_DESC, ST_GEAR);
                if (ok == null || !ok) s.readySource = "SENSOR_EPTRDY, servizio non pronto";
                else if (r == 0 || r == 1) {
                    s.ready = r;
                    s.readySource = "SENSOR_EPTRDY";
                } else s.readySource = "SENSOR_EPTRDY valore " + (r == Integer.MIN_VALUE ? "non letto" : String.valueOf(r));
                if (ok != null && ok && g != Integer.MIN_VALUE && g >= 0 && g <= 11) s.gear = g;
            }
        } catch (Exception e) {
            s.readySource = "SENSOR_EPTRDY errore " + e.getClass().getSimpleName();
        }
        Diag.putQuiet("auto.ready", "ready " + s.ready + " (" + s.readySource + ") · marcia " + gearName(s.gear));
    }

    /** Nome della marcia del servizio stato auto, per i log. */
    public static String gearName(int g) {
        switch (g) {
            case -1: return "non letta";
            case 0: return "tra due marce";
            case 1: return "P";
            case 2: return "R";
            case 3: return "N";
            default: return "D (" + g + ")";
        }
    }

    /** Temperatura interna leggibile? true / false, null finché non si sa (CarService non ancora pronto). */
    public static Boolean insideAvailable() {
        CarProps p = shared;
        if (p == null) return null;
        Boolean a = p.available(CarProps.HVAC_TEMPERATURE_CURRENT), b = p.available(CarProps.HVAC_AMBIENT_TEMPERATURE);
        if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) return true;
        if (Boolean.FALSE.equals(a) && Boolean.FALSE.equals(b)) return false;
        return null;
    }

    private static boolean valid(float t) {
        return !Float.isNaN(t) && t > -40 && t < 80 && t != 0f;
    }

    private int rvcAvmConfig = Integer.MIN_VALUE;

    /**
     * Telecamere presenti secondo la configurazione dell'auto: true / false, null se non si sa ancora
     * (in quel caso il pulsante 360° resta come prima). Stessi valori della home originale SWI69.
     */
    public static Boolean cameraPresent(int cfg) {
        if (cfg < 0) return null;
        return cfg >= 1 && cfg <= 5;
    }

    /** 0.3.3: fari per il Controllo funzioni: 1 accesi, 0 spenti, -1 non letti. */
    public static int lights() {
        return DayNight.headlights();
    }

    public static String lightsDescription() {
        return Diag.value("luci.fari");
    }

    /** 0.4.4: rilettura immediata dei fari (prova del Controllo funzioni, fuori dal thread principale). */
    public void refreshLightsNow() {
        Handler h = bg;
        if (h == null) return;
        final Object done = new Object();
        final boolean[] ok = {false};
        h.post(() -> {
            try {
                State s = new State();
                s.connected = last.connected;
                readLamps(s);
            } catch (Throwable ignored) {
            }
            synchronized (done) {
                ok[0] = true;
                done.notifyAll();
            }
        });
        synchronized (done) {   // la prova del Controllo funzioni aspetta la lettura (al massimo 1,5 s)
            long until = android.os.SystemClock.uptimeMillis() + 1500;
            while (!ok[0] && android.os.SystemClock.uptimeMillis() < until) {
                try {
                    done.wait(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void readLamps(State s) {
        if (!s.connected) return;
        try {
            if (frontFogConfig == Integer.MIN_VALUE) {
                IBinder cfg = sub(BIND_CONFIG);
                if (cfg != null) {
                    frontFogConfig = Rpc.callInt(cfg, CONFIG, CONFIG_FRONT_FOG);
                    Diag.put("luci.fendinebbiaAnterioriConfig", frontFogConfig);
                }
            }
        } catch (Exception e) {
            Diag.error("luci.config", e.toString());
            forget(BIND_CONFIG);
        }
        try {
            IBinder l = sub(BIND_LAMP);
            if (l != null) {
                long now = android.os.SystemClock.uptimeMillis();
                s.rearFog = held(LAMP_GET_REAR_FOG, Rpc.callInt(l, LAMP, LAMP_GET_REAR_FOG) == 1 ? 1 : 0, now);
                s.hasFrontFog = frontFogConfig == 0;
                if (s.hasFrontFog) s.frontFog = held(LAMP_GET_FRONT_FOG, Rpc.callInt(l, LAMP, LAMP_GET_FRONT_FOG) == 1 ? 1 : 0, now);
                Diag.put("luci.fendinebbia ant/post", s.frontFog + " / " + s.rearFog);
                if (lampsReadable) {
                    try {
                        int dp = Rpc.callInt(l, LAMP, LAMP_GET_DIPPED), sd = Rpc.callInt(l, LAMP, LAMP_GET_SIDE), sw = Rpc.callInt(l, LAMP, LAMP_GET_SWITCH);
                        DayNight.setLamps(dp, sd, sw);
                        Diag.putQuiet("luci.fari", "anabbaglianti " + dp + " · posizione " + sd + " · comando " + sw + " → "
                                + (DayNight.headlights() == 1 ? "accesi" : DayNight.headlights() == 0 ? "spenti" : "non letti"));
                    } catch (Exception e) {
                        lampsReadable = false;   // servizio senza questi comandi: il disegno resta com'è
                        Diag.put("luci.fari", "non leggibili: " + e.getClass().getSimpleName());
                    }
                }
            }
        } catch (Exception e) {
            Diag.error("luci", e.toString());
            forget(BIND_LAMP);
        }
        try {
            readAssist(s, android.os.SystemClock.uptimeMillis());
        } catch (Exception e) {
            Diag.error("assistenza", e.toString());
            forget(BIND_VS);
        }
    }

    private boolean lampsReadable = true;
    private int lkaConfig = Integer.MIN_VALUE, onePedalConfig = Integer.MIN_VALUE;
    private Boolean vsValid;

    /** Servizio impostazioni veicolo, solo se il collegamento 8 risponde con l'interfaccia giusta. */
    private IBinder vs() throws RemoteException {
        IBinder b = sub(BIND_VS);
        if (b == null) return null;
        if (vsValid == null) {
            String d = b.getInterfaceDescriptor();
            vsValid = VS.equals(d);
            Diag.put("assistenza.servizio", String.valueOf(d));
        }
        return vsValid ? b : null;
    }

    /** LKA e OPD: configurazione letta una volta, stato a ogni giro. Senza configurazione valida il pulsante non c'è. */
    private void readAssist(State s, long now) throws Exception {
        if (lkaConfig == Integer.MIN_VALUE || onePedalConfig == Integer.MIN_VALUE) {
            IBinder cfg = sub(BIND_CONFIG);
            if (cfg == null) return;
            lkaConfig = Rpc.callInt(cfg, CONFIG, CONFIG_LKA);
            onePedalConfig = Rpc.callInt(cfg, CONFIG, CONFIG_ONE_PEDAL);
            Diag.put("assistenza.config", "LKA " + lkaConfig + ", One Pedal " + onePedalConfig);
        }
        IBinder v = vs();
        if (v == null) return;
        try {
            int at = Rpc.callInt(v, VS, VS_GET_ACC_TJA);
            s.accTja = held(K_ACC_TJA, at == 1 || at == 2 || at == 4 ? at : -1, now);
            int bp = Rpc.callInt(v, VS, VS_GET_PEDAL);
            s.pedal = held(K_PEDAL, bp >= 0 && bp <= 2 ? bp : -1, now);
            Diag.put("assistenza.guida", "ACC/TJA " + at + ", pedale " + bp);
        } catch (Exception e) {
            Diag.put("assistenza.guida", "non leggibile: " + e);
        }
        if (lkaConfig == 0) {
            int mode = Rpc.callInt(v, VS, VS_GET_LAS);
            s.lkaMode = mode;
            int on = mode == 1 ? 0 : mode >= 2 && mode <= 5 ? 1 : -1;
            s.lka = held(K_LKA, on, now);
        }
        if (onePedalConfig == 0) {
            int o = Rpc.callInt(v, VS, VS_GET_OPD);
            s.opd = held(K_OPD, o == 1 ? 1 : o == 0 ? 0 : -1, now);
            s.opdInhibit = Rpc.callInt(v, VS, VS_GET_OPD_INHIBIT);
        }
        Diag.put("assistenza.stato", "LKA " + s.lka + " (modo " + s.lkaMode + "), OPD " + s.opd + " (blocco " + s.opdInhibit + ")");
    }

    /** LKA acceso/spento: come l'interruttore dell'app originale (SWI69 e SWI132), accende con 2 (LDW) e spegne con 1. */
    public void toggleLka() {
        if (bg == null) return;
        int cur = predicted(K_LKA, last.lka);
        if (cur < 0) return;
        boolean turnOn = cur == 0;
        hold(K_LKA, turnOn ? 1 : 0);
        bg.post(() -> {
            try {
                IBinder v = vs();
                if (v == null) return;
                int value = turnOn ? 2 : 1;
                Rpc.call(v, VS, VS_SET_LAS, p -> p.writeInt(value));
                Diag.put("comando.ultimo", "assistenza.lka · " + (turnOn ? "acceso (modo " + value + ")" : "spento"));
            } catch (Exception e) {
                Diag.error("assistenza.lka", e.toString());
                forget(BIND_VS);
            }
            poll();
        });
    }

    // ---- assistente di guida e pedale (0.9.5 / 0.3.8 / 0.2.5) -------------------------------------

    /** Velocità in km/h dal CarService (NaN se non leggibile). */
    public static float speedKmh() {
        CarProps p = shared;
        if (p == null) return Float.NaN;
        float v = p.getFloat(CarProps.PERF_VEHICLE_SPEED);   // m/s; NaN se l'auto non concede CAR_SPEED (letta una volta sola)
        return Float.isNaN(v) ? Float.NaN : Math.abs(v) * 3.6f;
    }

    /** Si cambiano solo ad auto ferma (sopra 3 km/h no): sono impostazioni della guida. */
    public static boolean stopped() {
        float v = speedKmh();
        return Float.isNaN(v) || v < 3f;
    }

    /** Assistente di guida: Spento → ACC → TJA → Spento, come le impostazioni dell'auto. */
    public boolean cycleAccTja() {
        if (bg == null || !stopped()) return false;
        int cur = predicted(K_ACC_TJA, last.accTja);
        if (cur < 0) return false;
        int next = cur == 4 ? 1 : cur == 1 ? 2 : 4;
        hold(K_ACC_TJA, next);
        bg.post(() -> {
            try {
                IBinder b = vs();
                if (b == null) return;
                Rpc.call(b, VS, VS_SET_ACC_TJA, p -> p.writeInt(next));
                Diag.put("comando.ultimo", "assistenza.accTja · " + cur + " → " + next);
            } catch (Exception e) {
                Diag.error("assistenza.accTja", e.toString());
                forget(BIND_VS);
            }
            poll();
        });
        return true;
    }

    /** Pedale del freno: Comfort → Medium → Sport → Comfort. */
    public boolean cyclePedal() {
        if (bg == null || !stopped()) return false;
        int cur = predicted(K_PEDAL, last.pedal);
        if (cur < 0) return false;
        int next = cur == 1 ? 0 : cur == 0 ? 2 : 1;
        hold(K_PEDAL, next);
        bg.post(() -> {
            try {
                IBinder b = vs();
                if (b == null) return;
                Rpc.call(b, VS, VS_SET_PEDAL, p -> p.writeInt(next));
                Diag.put("comando.ultimo", "assistenza.pedale · " + cur + " → " + next);
            } catch (Exception e) {
                Diag.error("assistenza.pedale", e.toString());
                forget(BIND_VS);
            }
            poll();
        });
        return true;
    }

    /** One Pedal Drive acceso/spento. Se l'auto lo blocca (in carica, condizioni…) lo stato letto resta com'era. */
    public void toggleOpd() {
        if (bg == null) return;
        int cur = predicted(K_OPD, last.opd);
        if (cur < 0) return;
        int next = cur == 1 ? 0 : 1;
        hold(K_OPD, next);
        bg.post(() -> {
            try {
                IBinder v = vs();
                if (v == null) return;
                Rpc.call(v, VS, VS_SET_OPD, p -> p.writeInt(next));
                Diag.put("comando.ultimo", "assistenza.opd · " + (next == 1 ? "acceso" : "spento") + " · blocco " + Rpc.callInt(v, VS, VS_GET_OPD_INHIBIT));
            } catch (Exception e) {
                Diag.error("assistenza.opd", e.toString());
                forget(BIND_VS);
            }
            poll();
        });
    }

    // ---- stati di ricarica ----------------------------------------------------------------------

    private int frontFogConfig = Integer.MIN_VALUE;

    /** Stati di ricarica (stessi codici della SWI68): in carica / bilanciamento / rapida / wireless. */
    public static boolean isCharging(int st) {
        return st == 1 || st == 3 || st == 10 || st == 16 || st == 11;
    }

    /** V2L: in scarica (13). */
    public static boolean isDischarging(int st) {
        return st == 13;
    }

    /**
     * Modalità del pulsante avvia/ferma, come le due schede della Gestione ricarica originale:
     * 2 = presa V2L collegata (11 collegato, 13 in scarica, 14 sospeso, 15 finito), 1 = cavo di ricarica collegato,
     * 0 = niente di collegato.
     */
    /**
     * 1.0.4 (punto F): cavo di ricarica inserito, anche prima che la colonnina cominci. Segnale della presa
     * (BMS_CHRG_PLUG_CNCTNIO = 0) oppure uno stato di ricarica con il cavo attaccato (1-5, 7-10, 12; 11 solo con potenza).
     * Escluso il V2L (13, 14, 15).
     */
    private static volatile State lastShared;

    /** 0.4.4: cavo inserito adesso (ultima lettura); false se non si sa. */
    public static boolean cablePluggedNow() {
        State s = lastShared;
        return s != null && s.connected && cablePlugged(s);
    }

    /**
     * 0.4.5: cavo inserito. La presa che dice "attaccato" basta da sola; la presa che dice "staccato" NON vince su uno
     * stato di ricarica che dice cavo attaccato (1-5, 7-10, 12; 11 solo con potenza). Se non sono d'accordo si scrive
     * "ricarica.presa = contrasto: presa <p> · stato <s>".
     */
    public static boolean cablePlugged(State s) {
        int st = s.chargingStatus;
        if (st == 13 || st == 14 || st == 15) return false;   // V2L: niente cavo di ricarica
        boolean byState = st == 11 ? s.chargeKw > 0.3f : st >= 1 && st <= 5 || st >= 7 && st <= 10 || st == 12;
        boolean plugged = s.plugProp == 0 || byState;
        String txt;
        if (s.plugProp == 1 && byState || s.plugProp == 0 && !byState && st >= 0) {
            txt = "contrasto: presa " + s.plugProp + " · stato " + st + " → " + (plugged ? "cavo inserito" : "cavo non inserito");
        } else {
            txt = (plugged ? "cavo inserito" : "cavo non inserito") + " · presa " + s.plugProp + " · stato " + st;
        }
        Diag.putQuiet("ricarica.presa", txt);
        return plugged;
    }

    /** 1.0.4: carica finita (2) o ferma al limite (7/8 con la batteria al limite scelto). */
    public static boolean chargeDone(State s) {
        int st = s.chargingStatus;
        if (st == 2) return true;
        return (st == 7 || st == 8) && s.chargeLimit > 0 && s.soc >= s.chargeLimit - 1;
    }

    public static int plugMode(State s) {
        int st = s.chargingStatus;
        if (st == 13 || st == 14 || st == 15) return 2;
        if (st == 11) return s.chargeKw > 0.3f ? 1 : 2;
        if (st == 1 || st == 2 || st == 3 || st == 4 || (st >= 6 && st <= 10)) return 1;
        return 0;
    }

    // ---- 0.3.5: spegnimento della vettura -----------------------------------------------------------------

    /** Marcia P del servizio stato auto (CarState.getGearState). */
    public static final int GEAR_P = 1;
    /**
     * Comando: servizio comfort SAIC dal "hub" (CarAdapterClient.queryClient(0xf), BIND_CODE_CAR_CARFORTABLET),
     * ICarComfortabletService.setPowerModeSwitch(int) = transazione 8, valore 2 come il riquadro "Spegni" del SystemUI
     * originale (PowerTile → CarComfortableController.setPowerModeSwitch(2)). Sulla SWI132 getPowerModeSwitch NON esiste:
     * nel log si scrive "non presente". Accensione per la verifica: IGNITION_STATE di Android (2 spenta, 3 accessori, 4 on).
     */
    private static final int BIND_COMFORT = 0xf, COMFORT_SET_POWER_MODE = 8, POWER_OFF = 2;
    private static final String COMFORT = "com.saicmotor.carapi.comfortablet.ICarComfortabletService";
    private static final int IGNITION_STATE = 289408009;

    /** Esito dello spegnimento: "inviato", "non in P", "non disponibile", "errore". */
    public interface PowerOffDone {
        void done(String result);
    }

    /** Marcia riletta adesso (servizio stato auto pronto), MIN_VALUE se non si legge. Da un thread di lavoro. */
    private int gearNow() {
        try {
            IBinder st = sub(BIND_STATE);
            if (st == null) return Integer.MIN_VALUE;
            Boolean ok = Rpc.call(st, STATE_DESC, ST_SERVICE_READY, Rpc.NONE, p -> p.readInt() != 0);
            if (ok == null || !ok) return Integer.MIN_VALUE;
            int g = Rpc.callInt(st, STATE_DESC, ST_GEAR);
            return g >= 0 && g <= 11 ? g : Integer.MIN_VALUE;
        } catch (Exception e) {
            forget(BIND_STATE);
            return Integer.MIN_VALUE;
        }
    }

    private int readyNow() {
        try {
            IBinder st = sub(BIND_STATE);
            return st == null ? Integer.MIN_VALUE : Rpc.callInt(st, STATE_DESC, ST_EPTRDY);
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    private int ignitionNow() {
        CarProps p = props;
        if (p == null || !p.ready()) return Integer.MIN_VALUE;
        Integer v = p.readLight(IGNITION_STATE, false, new String[1]);
        return v == null ? Integer.MIN_VALUE : v;
    }

    /** Controllo funzioni (sola lettura): il servizio comfort risponde con l'interfaccia giusta? null = non si sa. */
    public Boolean powerOffAvailable() {
        try {
            IBinder b = sub(BIND_COMFORT);
            if (b == null) return hub.isConnected() ? Boolean.FALSE : null;
            return COMFORT.equals(b.getInterfaceDescriptor());
        } catch (Exception e) {
            forget(BIND_COMFORT);
            return Boolean.FALSE;
        }
    }

    /** Riscaldamento intelligente batteria (solo studio, sola lettura, da un thread di lavoro). MIN_VALUE non leggibile. */
    public int batteryHeat() {
        try {
            IBinder e = sub(BIND_EV);
            return e == null ? Integer.MIN_VALUE : Rpc.callInt(e, EV, EV_GET_BATTERY_HEAT);
        } catch (Exception ex) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Spegne la vettura. Solo in P (1) e ferma (sotto 1 km/h): marcia e velocità si RILEGGONO qui, subito prima del
     * comando; se una delle due non si legge non si invia niente. Dopo 5 s si rilegge: "esito confermato" o "nessun effetto".
     */
    public void powerOff(PowerOffDone done) {
        Handler mh = new Handler(Looper.getMainLooper());
        if (bg == null) {
            Diag.put("auto.spegnimento", "richiesto · servizio non collegato · non inviato");
            mh.post(() -> done.done("errore"));
            return;
        }
        bg.post(() -> {
            String res;
            int gear = gearNow();
            float speed = speedKmh();
            int ign = ignitionNow(), rdy = readyNow();
            try {
                if (gear != GEAR_P || Float.isNaN(speed) || speed >= 1f) res = "non in P";
                else {
                    IBinder c = sub(BIND_COMFORT);
                    if (c == null || !COMFORT.equals(c.getInterfaceDescriptor())) res = "non disponibile";
                    else {
                        // ultimo controllo, subito prima dell'invio
                        float sp2 = speedKmh();
                        if (gearNow() != GEAR_P || Float.isNaN(sp2) || sp2 >= 1f) res = "non in P";
                        else {
                            Boolean r = Rpc.call(c, COMFORT, COMFORT_SET_POWER_MODE, p -> p.writeInt(POWER_OFF), p -> p.readInt() != 0);
                            res = "inviato";
                            Diag.put("auto.spegnimento.risposta", String.valueOf(r));
                        }
                    }
                }
            } catch (Exception e) {
                res = "errore";
                forget(BIND_COMFORT);
                Diag.error("auto.spegnimento", e.toString());
            }
            Diag.put("auto.spegnimento", "richiesto · marcia " + gearName(gear == Integer.MIN_VALUE ? -1 : gear) + " · velocità "
                    + (Float.isNaN(speed) ? "non letta" : String.format(java.util.Locale.US, "%.1f", speed))
                    + " · metodo ICarComfortabletService.setPowerModeSwitch(2) · getPowerModeSwitch non presente su SWI132"
                    + " · accensione " + ign + " · ready " + rdy + " · esito " + res);
            if ("inviato".equals(res)) bg.postDelayed(() -> checkPowerOff(ign, rdy), 5000);
            String r = res;
            mh.post(() -> done.done(r));
        });
    }

    private void checkPowerOff(int ignBefore, int readyBefore) {
        int ign = ignitionNow(), rdy = readyNow();
        boolean off = (readyBefore == 1 && rdy == 0) || (ign != Integer.MIN_VALUE && ign <= 3 && (ignBefore == Integer.MIN_VALUE || ign < ignBefore));
        Diag.put("auto.spegnimento", "dopo 5 s · accensione " + ignBefore + " → " + ign + " · ready " + readyBefore + " → " + rdy
                + " · " + (off ? "esito confermato" : "nessun effetto"));
    }

    // ---- pulsanti a commutazione ----------------------------------------------------------------

    private final Map<Integer, long[]> holds = new HashMap<>();   // codice lettura -> {valore, scadenza}

    /** Dopo un tocco mostra subito il nuovo stato per qualche secondo, finché l'auto non lo conferma. */
    private int held(int getCode, int read, long now) {
        synchronized (holds) {
            long[] h = holds.get(getCode);
            if (h == null) return read;
            if (now < h[1] && read != h[0]) return (int) h[0];
            holds.remove(getCode);
            return read;
        }
    }

    private int predicted(int getCode, int fallback) {
        synchronized (holds) {
            long[] h = holds.get(getCode);
            return h != null && android.os.SystemClock.uptimeMillis() < h[1] ? (int) h[0] : fallback;
        }
    }

    private void hold(int getCode, int value) {
        synchronized (holds) {
            holds.put(getCode, new long[]{value, android.os.SystemClock.uptimeMillis() + HOLD_MS});
        }
    }

    /** Commuta: il servizio SWI69 ha solo "cambia stato" (senza argomenti) per il clima, set(1) per le luci. */
    private void press(int bind, String desc, int setCode, boolean withOne, int getCode, int current, String what) {
        if (bg == null) return;
        if (current == 0 || current == 1) hold(getCode, 1 - current);
        bg.post(() -> {
            try {
                IBinder b = sub(bind);
                if (b != null) {
                    if (withOne) Rpc.call(b, desc, setCode, p -> p.writeInt(1));
                    else Rpc.call(b, desc, setCode, Rpc.NONE);
                }
            } catch (Exception e) {
                Diag.error(what, e.toString());
                forget(bind);
            }
            poll();
        });
    }

    public void toggleAc() {
        press(BIND_HVAC, AC, AC_SWITCH_AC, false, AC_GET_AC, last.acOn, "clima.ac");
    }

    public void toggleAuto() {
        press(BIND_HVAC, AC, AC_SWITCH_AUTO, false, AC_GET_AUTO, last.autoOn, "clima.auto");
    }

    public void toggleFrontDefrost() {
        press(BIND_HVAC, AC, AC_SWITCH_DEFROST, false, AC_GET_DEFROST, last.defrostOn, "clima.sbrinamento");
    }

    /** 0.3.5: lunotto termico, separato dal parabrezza (commuta). */
    public void toggleRearDefrost() {
        if (last.rearDefrostOn < 0) return;
        press(BIND_HVAC, AC, AC_SWITCH_REAR_DEFROST, false, AC_GET_REAR_DEFROST, predicted(AC_GET_REAR_DEFROST, last.rearDefrostOn), "clima.lunotto");
    }

    /** 0.3.5: apre o chiude una direzione dell'aria (sette modi SAIC, almeno una resta aperta). */
    public void toggleAirDirection(int direction) {
        if (bg == null || !last.connected || last.blowerMode < 0) return;
        bg.post(() -> {
            int current = predicted(AC_GET_FAN_DIR, last.blowerMode);
            int next = AirflowModes.toggle(current, direction);
            if (next == current) return;
            try {
                IBinder a = sub(BIND_HVAC);
                if (a == null) return;
                Rpc.call(a, AC, AC_SET_FAN_DIR, p -> p.writeInt(next));
                hold(AC_GET_FAN_DIR, next);   // tocchi rapidi in ordine
                Diag.put("comando.ultimo", "clima.direzione " + current + " → " + next);
            } catch (Exception e) {
                synchronized (holds) {
                    holds.remove(AC_GET_FAN_DIR);
                }
                Diag.error("clima.direzione", e.toString());
                forget(BIND_HVAC);
            }
            poll();
        });
    }

    public void toggleRearFog() {
        press(BIND_LAMP, LAMP, LAMP_SET_REAR_FOG, true, LAMP_GET_REAR_FOG, last.rearFog, "luci.retronebbia");
    }

    public void toggleFrontFog() {
        press(BIND_LAMP, LAMP, LAMP_SET_FRONT_FOG, true, LAMP_GET_FRONT_FOG, last.frontFog, "luci.fendinebbia");
    }

    // ---- sedili e volante riscaldati (SWI132) ------------------------------------------------------

    private int carModelConfig = Integer.MIN_VALUE, seatHeatConfig = Integer.MIN_VALUE, wheelHeatConfig = Integer.MIN_VALUE;
    private volatile int seatTargetLeft = -1, seatTargetRight = -1;

    /** Configurazione letta una volta (come l'app Clima originale); stato a ogni giro. Senza comando il pulsante non c'è. */
    private void readComfort(IBinder a, State s, long now) throws Exception {
        if (seatHeatConfig == Integer.MIN_VALUE) {
            IBinder cfg = sub(BIND_CONFIG);
            if (cfg == null) return;
            carModelConfig = Rpc.callInt(cfg, CONFIG, CONFIG_CAR_MODEL);
            wheelHeatConfig = Rpc.callInt(cfg, CONFIG, CONFIG_WHEEL_HEAT);
            seatHeatConfig = Rpc.callInt(cfg, CONFIG, CONFIG_SEAT_HEAT);
            Diag.put("comfort.config", "modello " + carModelConfig + ", sedili " + seatHeatConfig + " (2 = assenti), volante "
                    + wheelHeatConfig + " (0 = presente) → sedili " + (hasSeatHeat() ? "sì" : "no") + ", volante " + (hasWheelHeat() ? "sì" : "no"));
        }
        int ls = -1, rs = -1, wh = -1;
        // 0.3.1: Automatico = configurazione dell'auto, come l'app Clima originale (le letture rispondono anche sulle auto
        // senza riscaldamento, quindi la presenza non si decide dalla risposta); Mostra / Nascondi = scelta dell'utente
        boolean show = "show".equals(comfortMode), hide = "hide".equals(comfortMode);
        boolean seats = !hide && (show || hasSeatHeat()), wheel = !hide && (show || hasWheelHeat());
        if (seats) {
            ls = seatLevel(a, SEAT_DRIVER);
            rs = seatLevel(a, SEAT_PASSENGER);
            s.seatLeft = held(K_SEAT_L, ls >= 0 && ls <= 3 ? ls : show ? 0 : -1, now);
            s.seatRight = held(K_SEAT_R, rs >= 0 && rs <= 3 ? rs : show ? 0 : -1, now);
        }
        if (wheel) {
            wh = Rpc.callInt(a, AC, AC_GET_WHEEL_HEAT);
            s.wheelHeat = held(K_WHEEL, wh == 3 ? 1 : 0, now);
        }
        lastSeatRead[0] = ls;
        lastSeatRead[1] = rs;
        lastSeatRead[2] = wh;
        Diag.put("clima.sedili sx/dx · volante", ls + " / " + rs + " · " + wh);
        Diag.put("comfort.riscaldamento", "presenza: configurazione auto (modello " + carModelConfig + ", sedili " + seatHeatConfig
                + ", volante " + wheelHeatConfig + ") → sedili " + (hasSeatHeat() ? "sì" : "no") + ", volante " + (hasWheelHeat() ? "sì" : "no")
                + " · scelta " + comfortMode + " · letti sx " + ls + " dx " + rs + " volante " + wh + " · ultimo comando " + lastComfortCmd);
    }

    /** Scelta dell'utente per sedili e volante riscaldati: "auto", "show", "hide" (dalle impostazioni). */
    public static volatile String comfortMode = "auto";
    private final int[] lastSeatRead = {-1, -1, -1};
    private volatile String lastComfortCmd = "nessuno";

    /**
     * 0.3.1: dopo circa 1 s si rilegge dall'auto; se il valore non è cambiato il pulsante torna allo stato reale e lo si
     * scrive nella diagnostica.
     */
    private void verifyComfort(int key, int seat, int before, String what) {
        bg.postDelayed(() -> {
            try {
                IBinder a = sub(BIND_HVAC);
                if (a == null) return;
                int now = seat > 0 ? seatLevel(a, seat) : Rpc.callInt(a, AC, AC_GET_WHEEL_HEAT);
                boolean changed = now != before;
                if (!changed) {
                    synchronized (holds) {
                        holds.remove(key);
                    }
                }
                lastComfortCmd = what + " · prima " + before + " · riletto " + now + (changed ? " · ok" : " · NON cambiato: pulsante riportato allo stato reale");
                Diag.put("comfort.riscaldamento.comando", lastComfortCmd);
            } catch (Exception e) {
                Diag.error(what, e.toString());
            }
            poll();
        }, 1100);
    }

    private boolean hasSeatHeat() {
        return seatHeatConfig != Integer.MIN_VALUE && seatHeatConfig != 2;
    }

    private boolean hasWheelHeat() {
        if (wheelHeatConfig != 0) return false;
        for (int m : WHEEL_HEAT_MODELS) if (m == carModelConfig) return true;
        return false;
    }

    private static int seatLevel(IBinder a, int seat) throws RemoteException {
        Integer v = Rpc.call(a, AC, AC_GET_SEAT_HEAT, p -> p.writeInt(seat), android.os.Parcel::readInt);
        return v == null ? -1 : v;
    }

    /**
     * Sedile (barra senza gruppi): ogni tocco invia "premuto" e l'auto passa al livello successivo con il suo ciclo,
     * come l'app Clima originale; si mostra subito il livello previsto (spento → 3 → 2 → 1 → spento) e poi quello letto.
     */
    public void pressSeatLeft() {
        pressSeat(SEAT_DRIVER, K_SEAT_L, last.seatLeft, "clima.sedileSx");
    }

    public void pressSeatRight() {
        pressSeat(SEAT_PASSENGER, K_SEAT_R, last.seatRight, "clima.sedileDx");
    }

    private void pressSeat(int seat, int key, int current, String what) {
        if (bg == null) return;
        int cur = predicted(key, current);
        if (cur < 0) return;
        hold(key, cur == 0 ? 3 : cur - 1);
        bg.post(() -> {
            int before = -1;
            try {
                IBinder a = sub(BIND_HVAC);
                if (a != null) {
                    before = seatLevel(a, seat);
                    Rpc.call(a, AC, AC_SWITCH_SEAT_HEAT, p -> p.writeInt(seat));
                }
                Diag.put("comando.ultimo", what + " · premuto");
            } catch (Exception e) {
                Diag.error(what, e.toString());
                forget(BIND_HVAC);
            }
            verifyComfort(key, seat, before, what + " premuto");
        });
    }

    /** Volante: acceso ↔ spento con un "premuto", come l'app Clima originale (l'auto usa 3 per acceso). */
    public void toggleWheelHeat() {
        if (bg == null) return;
        int cur = predicted(K_WHEEL, last.wheelHeat);
        if (cur < 0) return;
        hold(K_WHEEL, cur > 0 ? 0 : 1);
        bg.post(() -> {
            int before = -1;
            try {
                IBinder a = sub(BIND_HVAC);
                if (a != null) {
                    before = Rpc.callInt(a, AC, AC_GET_WHEEL_HEAT);
                    Rpc.call(a, AC, AC_SET_WHEEL_HEAT, p -> p.writeInt(1));
                }
                Diag.put("comando.ultimo", "clima.volante · premuto");
            } catch (Exception e) {
                Diag.error("clima.volante", e.toString());
                forget(BIND_HVAC);
            }
            verifyComfort(K_WHEEL, 0, before, "volante premuto");
        });
    }

    /**
     * Gruppo Comfort: porta il sedile al livello successivo nell'ordine spento → 1 → 2 → 3 → spento, come la SWI68.
     * L'auto conosce solo "premuto" (e nessun "spegni"): si preme, distanziando e rileggendo, finché il livello letto è
     * quello voluto (al massimo 4 pressioni).
     */
    public void stepSeat(boolean driver) {
        if (bg == null) return;
        int key = driver ? K_SEAT_L : K_SEAT_R, seat = driver ? SEAT_DRIVER : SEAT_PASSENGER;
        int cur = predicted(key, driver ? last.seatLeft : last.seatRight);
        if (cur < 0) return;
        int target = (cur + 1) % 4;
        hold(key, target);
        if (driver) seatTargetLeft = target; else seatTargetRight = target;
        String what = driver ? "clima.sedileSx" : "clima.sedileDx";
        bg.post(() -> {
            if ((driver ? seatTargetLeft : seatTargetRight) != target) return;   // superato da un tocco più recente
            try {
                IBinder a = sub(BIND_HVAC);
                if (a == null) return;
                int now = seatLevel(a, seat), presses = 0;
                while (now != target && presses < 4 && (driver ? seatTargetLeft : seatTargetRight) == target) {
                    Rpc.call(a, AC, AC_SWITCH_SEAT_HEAT, p -> p.writeInt(seat));
                    presses++;
                    Thread.sleep(450);
                    now = seatLevel(a, seat);
                }
                Diag.put("comando.ultimo", what + " · livello " + target + " · pressioni " + presses + " · letto " + now);
                lastComfortCmd = what + " → livello " + target + " · pressioni " + presses + " · riletto " + now + (now == target ? " · ok" : " · NON raggiunto");
                if (now != target) {
                    synchronized (holds) {
                        holds.remove(key);
                    }
                }
            } catch (Exception e) {
                Diag.error(what, e.toString());
                forget(BIND_HVAC);
            }
            poll();
        });
    }

    /**
     * Ricircolo: il servizio ha solo "cambia" (passa allo stato successivo, come il pulsante della home originale);
     * si mostra subito lo stato previsto (interno ↔ esterno) e poi quello letto dall'auto.
     */
    public void toggleRecirc() {
        if (bg == null) return;
        int cur = predicted(AC_GET_LOOP, last.loopMode);
        if (cur < 0) return;
        hold(AC_GET_LOOP, cur == 0 ? 1 : cur == 1 ? 2 : 0);   // giro Interno → Esterno → Auto
        bg.post(() -> {
            try {
                IBinder a = sub(BIND_HVAC);
                if (a != null) Rpc.call(a, AC, AC_SWITCH_LOOP, Rpc.NONE);
                Diag.put("clima.ricircoloComando", "cambia (era " + cur + ")");
            } catch (Exception e) {
                Diag.error("clima.ricircolo", e.toString());
                forget(BIND_HVAC);
            }
            poll();
        });
    }

    // ---- temperatura e ventola --------------------------------------------------------------------

    /** Imposta la temperatura lato guida (15 = LO … 31 = HI). Il valore richiesto resta visibile per qualche secondo. */
    public void setDriverTemp(int celsius) {
        int t = Math.max(TEMP_MIN, Math.min(TEMP_MAX, celsius));
        heldTemp = t;
        tempHoldUntil = android.os.SystemClock.uptimeMillis() + HOLD_MS;
        pendingTemp = t;
        sendSoon();
    }

    /** Livello ventola: 0 = spegne il clima, 1..11, 15 = automatico. */
    public void setFanLevel(int level) {
        int l = level == FAN_AUTO || level == FAN_OFF ? level : Math.max(FAN_MIN, Math.min(FAN_MAX, level));
        heldFan = l;
        fanHoldUntil = android.os.SystemClock.uptimeMillis() + HOLD_MS;
        // OFF: l'auto non accetta la ventola a 0 (la rimette a 1 dopo un attimo). Come l'app originale, la ventola resta
        // da 1 in su e "OFF" vuol dire spegnere il clima.
        if (l == FAN_OFF) {
            Diag.put("clima.ventolaOff", "ventola OFF → spengo il clima");
            setHvacPower(false);
            return;
        }
        pendingFan = l;
        sendSoon();
    }

    private void sendPending(IBinder a) throws RemoteException {
        int t = pendingTemp;
        if (t > 0) {
            pendingTemp = -1;
            float f = tempToCar(t);
            Rpc.call(a, AC, AC_SET_DRV_TEMP, p -> p.writeFloat(f));
        }
        int fan = pendingFan;
        if (fan >= 0) {
            pendingFan = -1;
            boolean power = Rpc.callBool(a, AC, AC_GET_POWER);
            if (fan == FAN_OFF) {
                if (power) Rpc.call(a, AC, AC_SWITCH_POWER, Rpc.NONE);
            } else if (fan == FAN_AUTO) {
                if (!Rpc.callBool(a, AC, AC_GET_AUTO)) Rpc.call(a, AC, AC_SWITCH_AUTO, Rpc.NONE);
            } else {
                if (!power) Rpc.call(a, AC, AC_SWITCH_POWER, Rpc.NONE);
                Rpc.call(a, AC, AC_SET_FAN, p -> p.writeInt(fan));
            }
        }
    }

    /** Invia il comando subito, senza riscrivere la UI con una lettura immediata. */
    private void sendSoon() {
        if (bg == null) return;
        bg.post(() -> {
            try {
                IBinder a = sub(BIND_HVAC);
                if (a != null) sendPending(a);
            } catch (Exception e) {
                Diag.error("clima.comando", e.toString());
                forget(BIND_HVAC);
            }
        });
    }

    /** Accende o spegne il clima (il servizio SWI69 ha solo il comando "cambia stato"). */
    public void setHvacPower(boolean on) {
        if (bg == null) return;
        bg.post(() -> {
            try {
                IBinder a = sub(BIND_HVAC);
                if (a != null && Rpc.callBool(a, AC, AC_GET_POWER) != on) {
                    Rpc.call(a, AC, AC_SWITCH_POWER, Rpc.NONE);
                }
            } catch (Exception e) {
                Diag.error("clima.accensione", e.toString());
            }
            poll();
        });
    }

    // ---- ricarica: comandi del gestore batteria, come la Gestione ricarica dell'app Energia --------

    /** Limite di ricarica: codice 1..7 (40..100 %). */
    public void setChargeLimitCode(int code) {
        if (code < 1 || code > 7) return;
        chargeCommand(CarBms.CHRG_TRGT_SOC, code, code, "ricarica.limite → " + limitPercent(code) + "%");
    }

    /** Corrente di ricarica AC: codice 1..4 (6 A, 8 A, 16 A, Max). */
    public void setCurrentLimit(int code) {
        if (code < 1 || code > 4) return;
        chargeCommand(CarBms.ALTNG_CHRG_CRNT, code, code, "ricarica.corrente → " + CURRENT_LABELS[code - 1]);
    }

    /** Avvia o ferma la ricarica (cavo collegato). */
    public void setCharging(boolean start) {
        chargeCommand(CarBms.CHRG_CTRL, CTRL_PREPARING, start ? CTRL_START : CTRL_STOP, "ricarica." + (start ? "avvia" : "ferma"));
    }

    /** Avvia o ferma la scarica V2L. */
    public void setDischarging(boolean start) {
        chargeCommand(CarBms.V2X, CTRL_PREPARING, start ? CTRL_START : CTRL_STOP, "v2l." + (start ? "avvia" : "ferma"));
    }

    private void chargeCommand(int prop, int shown, int value, String what) {
        if (bg == null) return;
        hold(prop, shown);
        bg.post(() -> {
            boolean sent = bms.setInt(prop, value);
            Diag.put("ricarica.ultimoComando", what + (sent ? " · inviato" : " · non inviato (gestore batteria non pronto)"));
            poll();
        });
    }

    /** 0.4.0 (punto 6): sblocca la presa di ricarica (stesso comando della Gestione ricarica: blocco presa = 2). */
    public void unlockChargingSocket() {
        if (bg == null) return;
        hold(CarBms.CHRG_LOCK, 2);
        bg.post(() -> {
            boolean sent = bms.setInt(CarBms.CHRG_LOCK, 2);
            String ora = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
            Diag.put("ricarica.sbloccaPresa", (sent ? "inviato alle " : "NON inviato (gestore batteria non pronto) alle ") + ora);
            poll();
        });
    }
}
