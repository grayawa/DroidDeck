package com.droiddeck.launcher.runtime;

import com.droiddeck.launcher.core.AddonPack;
import android.content.Context;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.system.StructUtsname;
import com.droiddeck.launcher.core.FileUtils;
import com.droiddeck.launcher.session.SessionPrefs;


import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class LinuxRuntime {
    public static final String DIR = "linuxfs";
    public static final String SESSION_SCRIPT = "/usr/local/bin/droiddeck-session";
    public static final String MODE_DESKTOP = "desktop";
    public static final String MODE_STEAM = "steam";
    public static final String MODE_RUN = "run";
    public static final String EXTRA_LINUX_MODE = "linux_mode";
    private static final String KGSL_DEVICE = "/dev/kgsl-3d0";
    /** Where Turnip's KGSL backend gets its shareable memory from; see {@link #bindGpuNode}. */
    private static final String DMA_HEAP_DIR = "/dev/dma_heap";
    /** Where every Linux session's debug log lands: public, so a user can just hand the folder over. */
    public static final String DEBUG_LOG_DIR = "DroidDeck";

    public static File logDir(Context context) {
        return new File(context.getFilesDir(), "logs");
    }

    public static File legacyLogDir() {
        return new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), DEBUG_LOG_DIR);
    }

    private LinuxRuntime() {}

    public static File rootDir(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    public static File sessionRoot(Context context) {
        return new File(context.getFilesDir(), "session");
    }

    public static File liveDir(Context context) {
        // Android may evict cache files even while guest processes are using them.
        File dir = new File(context.getNoBackupFilesDir(), "guest-runtime");
        dir.mkdirs();
        return dir;
    }

    public static File prootTempDir(Context context) {
        File dir = new File(liveDir(context), "proot");
        dir.mkdirs();
        return dir;
    }

    private static final String HOST_DIR = "opt/android-host";

    public static File prootBinary(Context context) {
        if (AddonPack.PROOT.installed(context)) return AddonPack.PROOT.file(context, "libproot.so");
        return new File(rootDir(context), HOST_DIR + "/proot");
    }

    public static File prootLoader(Context context) {
        if (AddonPack.PROOT.installed(context)) return AddonPack.PROOT.file(context, "libproot-loader.so");
        return new File(rootDir(context), HOST_DIR + "/loader");
    }

    public static String prootLibraryPath(Context context) {
        File dir = new File(rootDir(context), HOST_DIR);
        return dir.equals(prootBinary(context).getParentFile()) ? dir.getPath() : "";
    }

    /**
     * The rooted session's chroot runner, or null when this build carries none. It is executed
     * from the native library directory, which is the only place an app may run a program from.
     */
    public static File rootRunner(Context context) {
        File packaged = new File(context.getApplicationInfo().nativeLibraryDir, "librootrun.so");
        return packaged.isFile() ? packaged : null;
    }

    /**
     * The runner's command line: what to mount where, the guest's environment, and the program.
     *
     * The mounts are the same {@code host:guest} pairs proot is given, so a rooted session sees
     * what an unrooted one does. The guest's own /proc, /sys and /dev are mounted by the runner
     * rather than bound, so they are not in the list.
     */
    public static List<String> rootRunnerCommand(Context context, File runner, File pidFile,
                                                 File sessionRoot, File runtimeDir,
                                                 File externalStorage, List<String> extraBinds,
                                                 List<String> guestEnv, List<String> guestProgram) {
        List<String> cmd = new ArrayList<>();
        cmd.add(runner.getPath());
        cmd.add("--root");
        cmd.add(rootDir(context).getPath());
        cmd.add("--dir");
        cmd.add("/root");
        cmd.add("--hostname");
        cmd.add(SessionPrefs.guestHostname(context));
        cmd.add("--pidfile");
        cmd.add(pidFile.getPath());
        cmd.add("--uid");
        cmd.add(String.valueOf(Process.myUid()));
        cmd.add("--gid");
        cmd.add(String.valueOf(Process.myUid()));
        for (String spec : binds(context, sessionRoot, runtimeDir, externalStorage, extraBinds)) {
            if (spec.equals("/proc") || spec.equals("/sys") || spec.equals("/dev")) continue;
            cmd.add("--bind");
            cmd.add(spec);
        }
        // The accounts the guest's own tools look the user up by, written where the guest reads them
        // (writeAccounts writes them in the app's copy of the runtime; the guest may not see it).
        try {
            int uid = Process.myUid();
            cmd.add("--write");
            cmd.add("/etc/passwd:root:x:" + uid + ":" + uid + ":root:/root:/bin/bash");
            cmd.add("--write");
            cmd.add("/etc/group:root:x:" + uid + ":");
        } catch (Throwable ignored) {
        }
        if (guestEnv != null) {
            for (String line : guestEnv) {
                if (line.indexOf('=') <= 0) continue;
                cmd.add("--env");
                cmd.add(line);
            }
        }
        cmd.add("--");
        cmd.addAll(guestProgram);
        return cmd;
    }

    /** The rootfs is present with gamescope and the session script the launcher hands control to. */
    public static boolean isInstalled(Context context) {
        if (LinuxRuntimeInstaller.isRemoving() || LinuxRuntimeInstaller.hasRemovalPending(context)) return false;
        File root = rootDir(context);
        return new File(root, "usr/bin/gamescope").isFile()
                && (new File(root, SESSION_SCRIPT.substring(1)).isFile()
                        || new File(root, LegacyNames.SESSION_SCRIPT.substring(1)).isFile())
                && prootBinary(context).isFile()
                && prootLoader(context).isFile();
    }

    public static File vulkanIcd(Context context) {
        File icdDir = new File(rootDir(context), "usr/share/vulkan/icd.d");
        File[] manifests = icdDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (manifests == null) return null;
        for (File manifest : manifests) {
            if (manifest.getName().contains("freedreno")) return manifest;
        }
        return manifests.length > 0 ? manifests[0] : null;
    }

    /**
     * The host paths the session needs inside the guest, as {@code host:guest} pairs: the app's own
     * directories for the compositor and audio sockets, external storage for the user's games, and
     * the device nodes the GPU and the pads live on. Bound at their own paths, so nothing on either
     * side needs translating and proot never touches the fds a dma-buf travels in. Android has no
     * /dev/shm; a directory under the cache stands in, which glibc's shm_open and Chromium's shared
     * memory accept.
     *
     * One list, used by both ways into the guest - proot's {@code -b} and the rooted runner's
     * {@code --bind} - so a rooted session mounts exactly what an unrooted one is given.
     */

    private static volatile List<String> lastBinds;

    public static List<String> lastBinds(Context context) {
        List<String> binds = lastBinds;
        if (binds != null) return binds;
        return binds(context, null, null, android.os.Environment.getExternalStorageDirectory(), null);
    }

    public static List<String> prootPrefix(Context context, File root, String cwd) {
        return prootPrefix(context, root, cwd, false);
    }

    public static List<String> prootPrefix(Context context, File root, String cwd, boolean fakeRoot) {
        List<String> cmd = new ArrayList<>();
        cmd.add(prootBinary(context).getPath());
        cmd.add("--kill-on-exit");
        cmd.add("--kernel-release=" + guestUtsname(SessionPrefs.guestHostname(context)));
        int uid = Process.myUid();
        if (fakeRoot) {
            cmd.add("-0");
        } else {
            cmd.add("-i");
            cmd.add(uid + ":" + uid);
        }
        cmd.add("-r");
        cmd.add(root.getPath());
        cmd.add("-w");
        cmd.add(cwd);
        return cmd;
    }

    public static List<String> binds(Context context, File sessionRoot, File runtimeDir,
                                     File externalStorage, List<String> extraBinds) {
        File root = rootDir(context);
        List<String> specs = new ArrayList<>();
        add(specs, "/dev");
        add(specs, "/proc");
        add(specs, "/sys");
        add(specs, "/dev/urandom:/dev/random");
        add(specs, "/proc/self/fd:/dev/fd");
        add(specs, "/proc/self/fd/0:/dev/stdin");
        add(specs, "/proc/self/fd/1:/dev/stdout");
        add(specs, "/proc/self/fd/2:/dev/stderr");
        add(specs, new File(root, "etc/bannerlator/empty").getPath() + ":/sys/fs/selinux");
        add(specs, context.getFilesDir().getPath());
        add(specs, context.getCacheDir().getPath());
        if (runtimeDir != null) add(specs, runtimeDir.getPath());
        if (sessionRoot != null) add(specs, sessionRoot.getPath());
        if (externalStorage != null && externalStorage.isDirectory()) add(specs, externalStorage.getPath());
        File shm = new File(context.getCacheDir(), "shm");
        shm.mkdirs();
        add(specs, shm.getPath() + ":/dev/shm");

        // Android denies these; glibc, Steam and libcap read them at startup.
        File fakeProc = new File(root, "etc/bannerlator/proc");
        File pciDevices = new File(fakeProc, "pci_devices");
        if (!pciDevices.isFile()) {
            try {
                fakeProc.mkdirs();
                pciDevices.createNewFile();
            } catch (java.io.IOException e) {
            }
        }
        String[][] seeded = {
                {"file-max", "9223372036854775807\n"},
                {"overcommit_memory", "0\n"},
        };
        for (String[] entry : seeded) {
            File fake = new File(fakeProc, entry[0]);
            if (fake.isFile()) continue;
            try {
                Files.write(fake.toPath(), entry[1].getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
            }
        }
        String[][] procFiles = {
                {"pci_devices", "/proc/bus/pci/devices"},
                {"stat", "/proc/stat"},
                {"version", "/proc/version"},
                {"loadavg", "/proc/loadavg"},
                {"uptime", "/proc/uptime"},
                {"vmstat", "/proc/vmstat"},
                {"cap_last_cap", "/proc/sys/kernel/cap_last_cap"},
                {"overflowuid", "/proc/sys/kernel/overflowuid"},
                {"overflowgid", "/proc/sys/kernel/overflowgid"},
                {"file-max", "/proc/sys/fs/file-max"},
                {"overcommit_memory", "/proc/sys/vm/overcommit_memory"},
        };
        for (String[] entry : procFiles) {
            File fake = new File(fakeProc, entry[0]);
            if (fake.isFile() && !new File(entry[1]).canRead()) {
                add(specs, fake.getPath() + ":" + entry[1]);
            }
        }
        bindGpuNode(context, specs);
        bindAdrenoStats(specs);
        bindCpuTemps(specs, root);
        if (extraBinds != null) {
            for (String spec : extraBinds) add(specs, spec);
        }
        return specs;
    }

    /** Compatibility name used by the rooted runner and its command-line test. */
    public static List<String> bindSpecs(Context context, File sessionRoot, File runtimeDir,
                                         File externalStorage, List<String> extraBinds) {
        List<String> specs = binds(context, sessionRoot, runtimeDir, externalStorage, extraBinds);
        if (sessionRoot != null) lastBinds = specs;
        return specs;
    }

    /**
     * The proot command line running {@code guestCommand} inside the rootfs.
     */
    public static List<String> command(Context context, File sessionRoot, File runtimeDir,
                                       File externalStorage, List<String> guestCommand) {
        return command(context, sessionRoot, runtimeDir, externalStorage, null, guestCommand);
    }

    /** As above, plus {@code host:guest} bind specs - the installed games handed to Steam. */
    public static List<String> command(Context context, File sessionRoot, File runtimeDir,
                                       File externalStorage, List<String> extraBinds,
                                       List<String> guestCommand) {
        File root = rootDir(context);
        List<String> binds = binds(context, sessionRoot, runtimeDir, externalStorage, extraBinds);
        // A session's binds are the view its programs have; a one-off command's are not recorded.
        if (sessionRoot != null) lastBinds = binds;
        List<String> cmd = prootPrefix(context, root, "/root");
        for (String spec : binds) bind(cmd, spec);
        cmd.addAll(guestCommand);
        return cmd;
    }

    /**
     * An app process may not open {@code /dev/dri} - the nodes exist but are labelled
     * {@code graphics_device}, which stock policy grants surfaceflinger and not us - yet libdrm and
     * everything built on it identify a GPU by its render node, and gamescope refuses to offer
     * linux-dmabuf without one. The KGSL device Turnip actually drives ({@code gpu_device}, which we
     * may open) stands in: it appears as a render node with the sysfs entries libdrm reads, and our
     * Turnip build reports the same device numbers for it.
     */
    private static void bindGpuNode(Context context, List<String> specs) {
        StructStat st;
        try {
            st = Os.stat(KGSL_DEVICE);
        } catch (ErrnoException e) {
            return;
        }
        long dev = st.st_rdev;
        long major = ((dev >> 8) & 0xfff) | ((dev >> 32) & ~0xfffL);
        long minor = (dev & 0xff) | ((dev >> 12) & ~0xffL);
        String node = "renderD" + minor;
        File base = new File(liveDir(context), "drm");
        File dri = new File(base, "dri");
        File device = new File(base, "sys/" + major + ":" + minor + "/device");
        File drm = new File(device, "drm/" + node);
        try {
            // The tree this builds is what libdrm enumerates and what Mesa reads the node's device
            // numbers from: /dev/dri/<node> (bound from the KGSL device), and beside it a sysfs
            // view whose <major>:<minor>/device/drm/<node>/dev says which char device it is.
            //
            if ((!dri.isDirectory() && !dri.mkdirs()) || (!device.isDirectory() && !device.mkdirs())) {
                return;
            }
            // Rebuild the node's directory every session, whatever is there now.
            //
            // It must be rebuilt rather than "created if missing": `mkdirs()` succeeds for a path
            // that already exists, so a tree laid out wrong once - which an earlier version of this
            // method did, mkdir'ing the node directory and writing `dev` inside it - stayed wrong
            // for every later session, and Mesa failed with "failed to query kernel driver version
            // for device /dev/dri/renderD0" while every log said the session had started normally
            // (measured on a Galaxy S23).
            //
            // A delete that does not take (a directory owned by root, left by a session that ran
            // through the rooted path, which starts as root) is reported and the tree is left
            // alone: writing into a directory this app does not own fails anyway, and the caller
            // gets a log line rather than a silent half-tree.
            FileUtils.delete(drm);
            if (!drm.mkdirs() && !drm.isDirectory()) {
                android.util.Log.e("LinuxRuntime", "cannot rebuild the GPU tree at " + drm);
                return;
            }
            new File(dri, node).createNewFile();
            Files.write(new File(drm, "dev").toPath(),
                    (major + ":" + minor + "\n").getBytes(StandardCharsets.UTF_8));
            Files.write(new File(device, "uevent").toPath(),
                    "DRIVER=kgsl-3d0\nMODALIAS=platform:kgsl-3d0\n".getBytes(StandardCharsets.UTF_8));
            File subsystem = new File(device, "subsystem");
            if (!Files.isSymbolicLink(subsystem.toPath())) {
                Os.symlink("/sys/bus/platform", subsystem.getPath());
            }
            // And prove it, because a malformed tree is invisible from here on: the guest starts,
            // gamescope runs, and only the GPU is missing.
            File devFile = new File(drm, "dev");
            String written = FileUtils.readString(devFile);
            if (!devFile.isFile() || written == null || !written.trim().equals(major + ":" + minor)) {
                android.util.Log.e("LinuxRuntime", "GPU tree is malformed: " + devFile + " says " + written);
                return;
            }
        } catch (IOException | ErrnoException e) {
            return;
        }
        add(specs, new File(base, "sys").getPath() + ":/sys/dev/char");
        add(specs, dri.getPath() + ":/dev/dri");
        add(specs, KGSL_DEVICE + ":/dev/dri/" + node);
        // The node at its real path as well as the /dev/dri alias, and the dma-buf heap with it.
        //
        // Turnip picks its kernel backend by what it can open: with /dev/kgsl-3d0 present it uses
        // KGSL and talks to the GPU through that node; without it, it enumerates /dev/dri, and
        // there the alias above is a KGSL node wearing a DRM name, so the version ioctl it opens
        // with answers ENOTTY and the driver reports "failed to query kernel driver version for
        // device /dev/dri/renderD0" - the whole GPU is gone, and every log says the session
        // started normally (measured on a Galaxy S23). Only the rooted path needs this: proot
        // binds the host's whole /dev, so its guest always had both names.
        //
        // The heap is where Turnip's shareable memory comes from: without it every allocation
        // fails and gamescope dies at "vkAllocateMemory failed" (same device, measured the same
        // way). Bound whole rather than node by node, so a device with several heaps keeps them.
        if (new File(KGSL_DEVICE).canRead()) add(specs, KGSL_DEVICE);
        if (new File(DMA_HEAP_DIR).canRead()) add(specs, DMA_HEAP_DIR);
    }

    /**
     * Valve's mangoapp (Deck mode's performance overlay) reads an Adreno GPU's load, clock and
     * temperatures from where they are on Valve's own hardware; every Adreno under Android keeps
     * them in KGSL's sysfs, readable by the app, though which files a kernel has differs between
     * Snapdragon generations - so each value takes the first source that exists, the order other
     * Android PC emulators (GameNative, Winlator forks) read them in. A value found nowhere is left
     * alone.
     */
    private static void bindAdrenoStats(List<String> specs) {
        String kgsl = "/sys/class/kgsl/kgsl-3d0/";
        return firstReadable(kgsl + "temp", kgsl + "devfreq/temp", thermalZone("gpu"));
    }

    private static String gpuLoadSource() {
        String kgsl = "/sys/class/kgsl/kgsl-3d0/";
        return firstReadable(kgsl + "gpu_busy_percentage", kgsl + "devfreq/gpu_load");
    }

    private static void bindAdrenoStats(List<String> cmd, List<String> extraBinds) {
        String kgsl = "/sys/class/kgsl/kgsl-3d0/";
        String gpuTemp = gpuTempSource();
        String[][] stats = {
                {gpuLoadSource(), "/sys/kernel/debug/dri/0/perf_now"},
                {firstReadable(kgsl + "devfreq/cur_freq", kgsl + "gpuclk"),
                        "/sys/devices/platform/soc@0/3d00000.gpu/devfreq/3d00000.gpu/cur_freq"},
                {gpuTemp, "/sys/class/thermal/thermal_zone28/temp"},
                {gpuTemp, "/sys/class/thermal/thermal_zone26/temp"},
                {thermalZone("ddr"), "/sys/class/thermal/thermal_zone22/temp"},
        };
        for (String[] stat : stats) {
            if (stat[0] != null) add(specs, stat[0] + ":" + stat[1]);
        }
        android.util.Log.i("LinuxRuntime", "hud sources: gpu load " + orNone(stats[0][0]) + ", gpu clock "
                + orNone(stats[1][0]) + ", gpu temp " + orNone(gpuTemp) + ", ddr temp " + orNone(stats[4][0])
                + ", thermal zones " + thermalZones().size()
                + ", kgsl gpubusy " + (new File(kgsl + "gpubusy").canRead() ? "readable" : "refused"));
    }

    private static String orNone(String path) {
        return path != null ? path : "none";
    }

    /**
     * mangoapp's CPU temperature is the mean of the thermal zones named cpuN-thermal or
     * cpuN-top-thermal, as mainline kernels name them; Android kernels name the same sensors
     * cpuss-0, cpu-1-0, cpu0-silver-usr, apc1-cpu0-usr and the like, so those zones are shown
     * under the mainline name.
     */
    private static void bindCpuTemps(List<String> specs, File root) {
        File name = new File(root, "etc/bannerlator/cpu-thermal-type");
        try {
            if (!name.isFile()) Files.write(name.toPath(), "cpu0-thermal\n".getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            return;
        }
        for (java.util.Map.Entry<String, File> zone : thermalZones().entrySet()) {
            String type = zone.getKey();
            if (type.contains("cpu") && !type.contains("gpu") && !type.matches("cpu\\d-(top-)?thermal")) {
                add(specs, name.getPath() + ":" + new File(zone.getValue(), "type").getPath());
            }
        }
    }

    private static String firstReadable(String... paths) {
        for (String path : paths) {
            if (path != null && new File(path).canRead()) return path;
        }
        return null;
    }

    private static String thermalZone(String sensor) {
        java.util.Map<String, File> zones = thermalZones();
        File zone = zones.get(sensor);
        for (java.util.Map.Entry<String, File> e : new java.util.TreeMap<>(zones).entrySet()) {
            if (zone == null && e.getKey().contains(sensor)) zone = e.getValue();
        }
        return zone != null ? new File(zone, "temp").getPath() : null;
    }

    private static java.util.Map<String, File> thermalZones() {
        java.util.Map<String, File> byType = new java.util.HashMap<>();
        File[] zones = new File("/sys/class/thermal").listFiles((dir, n) -> n.startsWith("thermal_zone"));
        if (zones == null) return byType;
        for (File zone : zones) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new File(zone, "type")))) {
                byType.putIfAbsent(String.valueOf(r.readLine()).trim().toLowerCase(java.util.Locale.ROOT), zone);
            } catch (IOException ignored) {
            }
        }
        return byType;
    }

    /** A bare {@code host:guest} spec, for the rooted runner's --bind. */
    private static void add(List<String> specs, String spec) {
        specs.add(spec);
    }

    /** The same spec as proot's -b argument. */
    private static void bind(List<String> cmd, String spec) {
        cmd.add("-b");
        cmd.add(spec);
    }

    static String guestUtsname(String hostname) {
        StructUtsname host = Os.uname();
        return "\\" + host.sysname + "\\" + hostname + "\\" + host.release
                + "\\" + host.version + "\\" + host.machine + "\\localdomain\\-1\\";
    }

    public static void writeAccounts(Context context) throws IOException {
        File root = rootDir(context);
        int uid = Process.myUid();
        Files.write(new File(root, "etc/passwd").toPath(),
                ("root:x:" + uid + ":" + uid + ":root:/root:/bin/bash\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "etc/group").toPath(),
                ("root:x:" + uid + ":\n").getBytes(StandardCharsets.UTF_8));
    }
}
