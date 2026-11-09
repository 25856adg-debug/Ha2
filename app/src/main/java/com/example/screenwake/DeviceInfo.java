package com.example.screenwake;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

public final class DeviceInfo {

    private static long previousIdle = -1;
    private static long previousTotal = -1;

    private DeviceInfo() {}

    // ================= 总入口 =================

    public static synchronized JSONObject collect(Context context) {

        JSONObject data = new JSONObject();

        try {
            data.put("timestamp", System.currentTimeMillis());
            data.put("device_id", ConfigManager.getDeviceId(context));

            data.put("brand", Build.BRAND);
            data.put("manufacturer", Build.MANUFACTURER);
            data.put("model", Build.MODEL);
            data.put("device", Build.DEVICE);
            data.put("android_version", Build.VERSION.RELEASE);
            data.put("sdk_version", Build.VERSION.SDK_INT);

            data.put("battery", getBattery(context));
            data.put("cpu", getCpu());
            data.put("memory", getMemory(context));
            data.put("storage", getStorage());
            data.put("wifi", getWifi(context));
            data.put("bluetooth", getBluetooth());
            data.put("screen", getScreen(context));
            data.put("network", getNetwork(context));

        } catch (Exception e) {
            try {
                data.put("collection_error", e.toString());
            } catch (Exception ignored) {}
        }

        return data;
    }

    // ================= 电池 =================

    private static JSONObject getBattery(Context context) {

        JSONObject result = new JSONObject();

        try {

            Intent battery = context.registerReceiver(
                    null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            );

            if (battery == null) {
                result.put("available", false);
                return result;
            }

            int level = battery.getIntExtra(
                    BatteryManager.EXTRA_LEVEL, -1);

            int scale = battery.getIntExtra(
                    BatteryManager.EXTRA_SCALE, -1);

            int percent = -1;

            if (level >= 0 && scale > 0) {
                percent = Math.round(level * 100f / scale);
            }

            result.put("available", true);
            result.put("percent", percent);

            result.put("temperature_c",
                    battery.getIntExtra(
                            BatteryManager.EXTRA_TEMPERATURE, -1
                    ) / 10.0);

            result.put("voltage_mv",
                    battery.getIntExtra(
                            BatteryManager.EXTRA_VOLTAGE, -1
                    ));

            int status = battery.getIntExtra(
                    BatteryManager.EXTRA_STATUS, -1);

            String statusText = "unknown";

            if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                statusText = "charging";
            } else if (status == BatteryManager.BATTERY_STATUS_DISCHARGING) {
                statusText = "discharging";
            } else if (status == BatteryManager.BATTERY_STATUS_FULL) {
                statusText = "full";
            } else if (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) {
                statusText = "not_charging";
            }

            result.put("status", statusText);

            int plugged = battery.getIntExtra(
                    BatteryManager.EXTRA_PLUGGED, 0);

            result.put("plugged", plugged);

        } catch (Exception e) {
            try {
                result.put("available", false);
                result.put("error", e.toString());
            } catch (Exception ignored) {}
        }

        return result;
    }

    // ================= CPU =================

    private static JSONObject getCpu() {

        JSONObject result = new JSONObject();

        try {

            int cores = Runtime.getRuntime()
                    .availableProcessors();

            result.put("cores", cores);

            long[] values = readCpuStat();

            if (values != null) {

                long idle = values[3] + values[4];

                long total = 0;

                for (long value : values) {
                    total += value;
                }

                double usage = -1;

                if (previousTotal >= 0 &&
                        total > previousTotal) {

                    long totalDiff = total - previousTotal;
                    long idleDiff = idle - previousIdle;

                    usage = (totalDiff - idleDiff)
                            * 100.0 / totalDiff;

                    usage = Math.max(0, Math.min(100, usage));
                }

                previousTotal = total;
                previousIdle = idle;

                result.put("usage_percent",
                        usage < 0 ? JSONObject.NULL : usage);

            } else {
                result.put("usage_percent", JSONObject.NULL);
            }

            result.put("frequency_mhz", readCpuFrequency());

        } catch (Exception e) {
            try {
                result.put("error", e.toString());
            } catch (Exception ignored) {}
        }

        return result;
    }

    private static long[] readCpuStat() {

        try (BufferedReader reader =
                     new BufferedReader(new FileReader("/proc/stat"))) {

            String line = reader.readLine();

            if (line == null || !line.startsWith("cpu ")) {
                return null;
            }

            String[] parts = line.trim().split("\\s+");

            long[] values = new long[parts.length - 1];

            for (int i = 1; i < parts.length; i++) {
                values[i - 1] = Long.parseLong(parts[i]);
            }

            return values;

        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject readCpuFrequency() {

        JSONObject result = new JSONObject();

        try {

            File file = new File(
                    "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"
            );

            if (file.canRead()) {

                try (BufferedReader reader =
                             new BufferedReader(new FileReader(file))) {

                    String value = reader.readLine();

                    if (value != null) {
                        result.put("cpu0", Long.parseLong(value.trim()) / 1000);
                    }
                }

            } else {
                result.put("cpu0", JSONObject.NULL);
            }

        } catch (Exception e) {
            try {
                result.put("cpu0", JSONObject.NULL);
            } catch (Exception ignored) {}
        }

        return result;
    }

    // ================= 内存 =================

    private static JSONObject getMemory(Context context) {

        JSONObject result = new JSONObject();

        try {

            ActivityManager manager =
                    (ActivityManager) context.getSystemService(
                            Context.ACTIVITY_SERVICE
                    );

            ActivityManager.MemoryInfo info =
                    new ActivityManager.MemoryInfo();

            manager.getMemoryInfo(info);

            result.put("total_bytes", info.totalMem);
            result.put("available_bytes", info.availMem);
            result.put("used_bytes",
                    info.totalMem - info.availMem);

            result.put("low_memory", info.lowMemory);

        } catch (Exception e) {
            try {
                result.put("error", e.toString());
            } catch (Exception ignored) {}
        }

        return result;
    }

    // ================= 存储 =================

    private static JSONObject getStorage() {

        JSONObject result = new JSONObject();

        try {

            File path = Environment.getDataDirectory();

            StatFs stat = new StatFs(path.getPath());

            long total = stat.getTotalBytes();
            long available = stat.getAvailableBytes();

            result.put("total_bytes", total);
            result.put("available_bytes", available);
            result.put("used_bytes", total - available);

        } catch (Exception e) {
            try {
                result.put("error", e.toString());
            } catch (Exception ignored) {}
        }

        return result;
    }

    // ================= Wi-Fi =================

    private static JSONObject getWifi(Context context) {

        JSONObject result = new JSONObject();

        try {

            WifiManager manager =
                    (WifiManager) context.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);

            boolean enabled = manager != null && manager.isWifiEnabled();

            result.put("enabled", enabled);

            if (manager != null && enabled) {

                WifiInfo info = manager.getConnectionInfo();

                if (info != null) {

                    String ssid = info.getSSID();

                    if (ssid != null &&
                            !ssid.equals("<unknown ssid>")) {

                        result.put("ssid", ssid.replace("\"", ""));
                    } else {
                        result.put("ssid", JSONObject.NULL);
                    }

                    int ip = info.getIpAddress();

                    String ipAddress = String.format(
                            Locale.US,
                            "%d.%d.%d.%d",
                            ip & 0xff,
                            (ip >> 8) & 0xff,
                            (ip >> 16) & 0xff,
                            (ip >> 24) & 0xff
                    );

                    result.put("ip", ip == 0 ? JSONObject.NULL : ipAddress);
                }
            }

        } catch (Exception e) {
            try {
                result.put("error", e.toString());
            } catch (Exception ignored) {}
        }

        return result;
    }

    // ================= 蓝牙 =================

    private static JSONObject getBluetooth() {

        JSONObject result = new JSONObject();

        try {

            BluetoothAdapter adapter =
                    BluetoothAdapter.getDefaultAdapter();

            if (adapter == null) {
                result.put("supported", false);
                result.put("enabled", false);
            } else {
                result.put("supported", true);
                result.put("enabled", adapter.isEnabled());
            }

        } catch (Exception e) {
            try {
                result.put("supported", true);
                result.put("enabled", JSONObject.NULL);
                result.put("error", e.toString());
            } catch (Exception ignored) {}
        }

        return result;
    }

    // ================= 屏幕 =================

    private static JSONObject getScreen(Context context) {

        JSONObject result = new JSONObject();

        try {

            PowerManager manager =
                    (PowerManager) context.getSystemService(
                            Context.POWER_SERVICE
                    );

            boolean interactive =
                    manager != null && manager.isInteractive();

            result.put("interactive", interactive);
            result.put("screen_on", interactive);

        } catch (Exception e) {
            try {
                result.put("error", e.toString());
            } catch (Exception ignored) {}
        }

        return result;
    }

    // ================= 网络 =================

    private static JSONObject getNetwork(Context context) {

        JSONObject result = new JSONObject();

        try {

            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(
                            Context.CONNECTIVITY_SERVICE
                    );

            NetworkInfo info = manager == null
                    ? null
                    : manager.getActiveNetworkInfo();

            boolean connected =
                    info != null && info.isConnected();

            result.put("connected", connected);

            result.put("type",
                    connected ? info.getTypeName() : "none");

            result.put("ip", getLocalIp());

        } catch (Exception e) {
            try {
                result.put("error", e.toString());
            } catch (Exception ignored) {}
        }

        return result;
    }

    private static String getLocalIp() {

        try {

            for (NetworkInterface network :
                    Collections.list(NetworkInterface.getNetworkInterfaces())) {

                for (java.net.InetAddress address :
                        Collections.list(network.getInetAddresses())) {

                    if (!address.isLoopbackAddress() &&
                            address instanceof Inet4Address) {

                        return address.getHostAddress();
                    }
                }
            }

        } catch (Exception ignored) {}

        return null;
    }
}

