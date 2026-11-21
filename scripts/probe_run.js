// probe_run.js — inject into Google One and LIST every offer. Read-only.
//
//   adb forward tcp:27042 tcp:27042
//   frida -H 127.0.0.1:27042 -p <PID> -l scripts/probe_run.js
//   adb logcat -s ENIPROBE
//
// Requires: /data/local/tmp/probe.dex (billing 5.2.1 + com.eni.Probe), chmod 444.
// frida 16.x on both ends.

Java.perform(function () {
  var Log = Java.use("android.util.Log");
  var ctx = Java.use("android.app.ActivityThread").currentApplication().getApplicationContext();

  // Android 10+ refuses a dex that lives on a writable path -> chmod 444 on device.
  var DexClassLoader = Java.use("dalvik.system.DexClassLoader");
  var loader = DexClassLoader.$new(
    "/data/local/tmp/probe.dex",
    ctx.getCodeCacheDir().getAbsolutePath(),
    null,
    ctx.getClassLoader()
  );

  // From here on, Java.use resolves classes through our dex (billing + Probe).
  Java.classFactory.loader = loader;

  var Probe = Java.use("com.eni.Probe");
  Log.i("ENIPROBE", "probe loaded: " + Probe.class.getName());
  Probe.run(ctx);
  Log.i("ENIPROBE", "Probe.run() called — read the ENIPROBE logcat tag");
});
