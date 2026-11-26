# Rigenera app/java/com/mg4/launcher/svc/GetterTable.java dal firmware (0.3.6): python3 -I tools/getter_table.py CarAdapterService_my24_ll.apk
# Stampa i BIND_CODE e un JSON con (interfaccia, codice, nome, tipo) dei metodi get/is senza argomenti.
import sys, re, json
from androguard.misc import AnalyzeAPK
from loguru import logger
logger.remove()
a, ds, dx = AnalyzeAPK(sys.argv[1])
ifaces = {}
stubs = {}
for d in ds:
    for c in d.get_classes():
        n = c.get_name()
        if not n.startswith('Lcom/saicmotor/carapi/'): continue
        if n.endswith('$Stub;'):
            codes = {}
            for f in c.get_fields():
                fn = f.get_name()
                if fn.startswith('TRANSACTION_'):
                    iv = f.get_init_value()
                    if iv is not None: codes[fn[12:]] = iv.get_value()
            stubs[n[:-6]+';'] = codes
        elif c.get_access_flags_string().find('interface') >= 0:
            ms = {}
            for m in c.get_methods():
                ms[m.get_name()] = m.get_descriptor()
            ifaces[n] = ms
        if 'BIND_CODE' in ''.join(f.get_name() for f in c.get_fields()):
            for f in c.get_fields():
                if f.get_name().startswith('BIND_CODE'):
                    iv = f.get_init_value()
                    print('BIND', n, f.get_name(), iv.get_value() if iv else None)
out = []
for iname, codes in stubs.items():
    ms = ifaces.get(iname, {})
    for mname, code in codes.items():
        desc = ms.get(mname)
        if not desc: continue
        m = re.match(r'\(\)([IFZ])$', desc.replace(' ', ''))
        if m and (mname.startswith('get') or mname.startswith('is')):
            out.append((iname, code, mname, m.group(1)))
print(json.dumps(sorted(out), indent=0))
