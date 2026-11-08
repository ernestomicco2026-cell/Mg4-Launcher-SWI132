package com.mg4.launcher.svc;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.3.2 (punto 8): telefono Bluetooth per i "Preferiti telefono" del riquadro in basso.
 * <p>
 * SWI132 (app telefono com.saicmotor.btphone, A9_Btphone): la chiamata la fa il servizio PhoneService dell'app telefono
 * con l'azione com.saicmotor.btphone.call.number e il numero nell'extra "number" (lo stesso ingresso che usano i
 * comandi vocali). Se la chiamata non parte, l'app telefono si apre da sola sulla scheda delle chiamate. Il servizio
 * chiede BIND_INCALL_SERVICE, che il launcher (firma platform) ha. Al launcher non serve CALL_PHONE.
 * <p>
 * Telefono collegato: profilo Bluetooth HFP lato auto (HEADSET_CLIENT, 16). Rubrica: la scarica il Bluetooth (PBAP)
 * nei contatti di Android; l'app telefono la legge da lì (ContactsContract Phone), così come qui.
 * Nei log mai nomi né numeri: solo quanti sono.
 */
public final class PhoneClient {
    public interface Listener {
        void onPhoneChanged();
    }

    public static final class Contact {
        public final String name, number;

        public Contact(String name, String number) {
            this.name = name == null ? "" : name;
            this.number = number == null ? "" : number;
        }
    }

    private static final String PKG = "com.saicmotor.btphone", CALL_ACTION = "com.saicmotor.btphone.call.number";
    /** Profili Bluetooth lato auto (costanti nascoste di BluetoothProfile): HFP client 16, PBAP client 17. */
    private static final int PROFILE_HFP_CLIENT = 16, PROFILE_PBAP_CLIENT = 17;

    private static PhoneClient instance;

    private final Context ctx;
    private final Handler work;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile int btState = -1, syncState = -1;
    private boolean receiverOn;
    private int users;

    private PhoneClient(Context c) {
        ctx = c.getApplicationContext();
        HandlerThread t = new HandlerThread("mg4-telefono");
        t.start();
        work = new Handler(t.getLooper());
    }

    public static synchronized PhoneClient get(Context c) {
        if (instance == null) instance = new PhoneClient(c);
        return instance;
    }

    /** Ascolta i cambi del telefono (contato: smette quando l'ultimo utilizzatore chiama stop). */
    public synchronized void start(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
        if (users++ == 0) {
            try {
                android.content.IntentFilter f = new android.content.IntentFilter("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED");
                f.addAction("android.bluetooth.pbapclient.profile.action.CONNECTION_STATE_CHANGED");
                f.addAction("android.bluetooth.device.action.PBAP_DOWNLOAD_STATE_CHANGED");
                f.addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
                ctx.registerReceiver(changes, f);
                receiverOn = true;
            } catch (Exception e) {
                Diag.error("telefono.ascolto", e.toString());
            }
            refresh();
        }
    }

    public synchronized void stop(Listener l) {
        if (l != null) listeners.remove(l);
        if (users > 0 && --users == 0 && receiverOn) {
            receiverOn = false;
            try {
                ctx.unregisterReceiver(changes);
            } catch (Exception ignored) {
            }
        }
    }

    private final android.content.BroadcastReceiver changes = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, android.content.Intent i) {
            refresh();
        }
    };

    /** L'app telefono originale c'è (è lei che fa la chiamata). */
    public boolean serviceConnected() {
        try {
            ctx.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Telefono collegato in Bluetooth (HFP). */
    public boolean phoneConnected() {
        return btState == 2;
    }

    public int btState() {
        return btState;
    }

    public int syncState() {
        return syncState;
    }

    private static int profile(int p) {
        try {
            android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (a == null) return -1;
            if (!a.isEnabled()) return 0;
            return a.getProfileConnectionState(p);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Rilegge stato del telefono (HFP) e della rubrica (PBAP) sul thread del telefono. */
    public void refresh() {
        work.post(() -> {
            int bs = profile(PROFILE_HFP_CLIENT), ss = profile(PROFILE_PBAP_CLIENT);
            boolean changed = bs != btState || ss != syncState;
            btState = bs;
            syncState = ss;
            Diag.put("telefono.stato", "app telefono " + (serviceConnected() ? "presente" : "assente") + " · telefono (HFP) " + profTxt(bs)
                    + " · rubrica (PBAP) " + profTxt(ss));
            if (changed) notifyListeners();
        });
    }

    private static String profTxt(int s) {
        switch (s) {
            case 0: return "scollegato";
            case 1: return "in collegamento";
            case 2: return "collegato";
            case 3: return "in scollegamento";
            default: return "non leggibile";
        }
    }

    private void notifyListeners() {
        main.post(() -> {
            for (Listener l : listeners) l.onPhoneChanged();
        });
    }

    /** Numero pulito come fa l'app Telefono: niente spazi, trattini, parentesi. */
    public static String clean(String n) {
        if (n == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : n.toCharArray()) if (Character.isDigit(ch) || ch == '+' || ch == '*' || ch == '#') sb.append(ch);
        return sb.toString();
    }

    /**
     * Chiama il numero tramite il servizio dell'app telefono (com.saicmotor.btphone.call.number). Se il servizio non
     * riesce, è l'app telefono stessa ad aprirsi. Risultato nel log senza il numero.
     */
    public void call(String number, java.util.function.Consumer<Boolean> done) {
        String n = clean(number);
        work.post(() -> {
            boolean ok = false;
            String why;
            if (n.isEmpty()) why = "numero vuoto";
            else if (!serviceConnected()) why = "app telefono assente";
            else if (btState == 0) why = "telefono non collegato";
            else {
                try {
                    android.content.Intent i = new android.content.Intent(CALL_ACTION).setPackage(PKG).putExtra("number", n);
                    ok = ctx.startService(i) != null;
                    why = ok ? "inviata all'app telefono" : "servizio dell'app telefono non trovato";
                } catch (SecurityException e) {
                    why = "permesso negato (BIND_INCALL_SERVICE)";
                } catch (Exception e) {
                    why = "errore " + e.getClass().getSimpleName();
                }
            }
            Diag.put("telefono.chiamata", why + " · " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ITALY).format(new java.util.Date()));
            if (done != null) {
                boolean res = ok;
                main.post(() -> done.accept(res));
            }
        });
    }

    /**
     * Rubrica del telefono collegato (nome + numero, senza doppioni), in ordine alfabetico. Vuota se il telefono non
     * è collegato o la rubrica non è sincronizzata. Da chiamare fuori dal thread principale.
     */
    public List<Contact> contacts() {
        List<Contact> out = new ArrayList<>();
        ensureContactsPermission(ctx);
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{"display_name", "data1"}, null, null, "display_name COLLATE NOCASE ASC");
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (c != null && c.moveToNext() && out.size() < 2000) {
                String name = c.getString(0), num = clean(c.getString(1));
                if (num.isEmpty() || name == null || name.isEmpty() || "本机号码".equals(name)) continue;
                if (!seen.add(name + "|" + num)) continue;
                out.add(new Contact(name, num));
            }
            Diag.put("telefono.rubrica", out.size() + " numeri letti");
        } catch (Throwable e) {
            Diag.error("telefono.rubrica", e.getClass().getSimpleName());
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Il launcher è un'app di sistema: il permesso della rubrica se lo concede da solo (come la posizione). */
    static void ensureContactsPermission(Context c) {
        String perm = "android.permission.READ_CONTACTS";
        if (c.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                    .invoke(pm, c.getPackageName(), perm, android.os.Process.myUserHandle());
        } catch (Throwable ignored) {
        }
    }
}
