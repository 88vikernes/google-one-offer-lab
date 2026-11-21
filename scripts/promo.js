// promo.js — open Play's checkout for ONE offer. Opens the sheet; never confirms.
//
//   frida -H 127.0.0.1:27042 -p <PID> -l scripts/promo.js
//   adb logcat -s ENIPROBE
//
// Edit SKU and CYCLES below.
//   CYCLES = 3   -> 3-month intro price (billingCycleCount == 3, cheapest such offer)
//
// Warning: on many SKUs, a successful launchBillingFlow (code=0) already consumes
// the account's eligibility, even if the sheet is dismissed.

var SKU    = "g1.ai.ultra.3months_ip_90p.acq";   // <-- target product
var CYCLES = 3;                                   // <-- length of the promotional phase

Java.perform(function () {
  var Log = Java.use("android.util.Log");
  var ctx = Java.use("android.app.ActivityThread").currentApplication().getApplicationContext();

  var DexClassLoader = Java.use("dalvik.system.DexClassLoader");
  var loader = DexClassLoader.$new(
    "/data/local/tmp/probe.dex",
    ctx.getCodeCacheDir().getAbsolutePath(),
    null,
    ctx.getClassLoader()
  );
  Java.classFactory.loader = loader;

  var Probe = Java.use("com.eni.Probe");

  // launchBillingFlow needs a live Activity, not just a Context.
  var act = null;
  Java.choose("android.app.Activity", {
    onMatch: function (a) { act = a; return "stop"; },
    onComplete: function () { Log.i("ENIPROBE", "activity=" + act); }
  });
  if (act === null) {
    Log.e("ENIPROBE", "no Activity found — open Google One in the foreground first");
    return;
  }

  Probe.activatePromo(act, SKU, CYCLES);
  Log.i("ENIPROBE", "activatePromo(" + SKU + ", " + CYCLES + ") called");
});
