package ee.forgr.capacitor_updater;

import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.getcapacitor.JSObject;
import com.getcapacitor.plugin.WebView;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

interface Callback {
    void callback(JSONObject jsonObject);
}

interface CallbackChannel {
    void callback(JSObject jsoObject);
}

public class CapacitorUpdater {

    private static final String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom rnd = new SecureRandom();

    private static final String INFO_SUFFIX = "_info";

    private static final String FALLBACK_VERSION = "pastVersion";
    private static final String NEXT_VERSION = "nextVersion";
    private static final String bundleDirectory = "versions";

    public static final String TAG = "Capacitor-updater";
    public static final String pluginVersion = "4.11.3";

    public SharedPreferences.Editor editor;
    public SharedPreferences prefs;

    public RequestQueue requestQueue;

    public File documentsDir;
    public String versionBuild = "";
    public String versionCode = "";
    public String versionOs = "";

    public String customId = "";
    public String statsUrl = "";
    public String channelUrl = "";
    public String appId = "";
    public String deviceID = "";

    private final FilenameFilter filter = new FilenameFilter() {
        @Override
        public boolean accept(final File f, final String name) {
            // ignore directories generated by mac os x
            return !name.startsWith("__MACOSX") && !name.startsWith(".") && !name.startsWith(".DS_Store");
        }
    };

    private boolean isProd() {
        return !BuildConfig.DEBUG;
    }

    private boolean isEmulator() {
        return (
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
            Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.PRODUCT.contains("sdk_google") ||
            Build.PRODUCT.contains("google_sdk") ||
            Build.PRODUCT.contains("sdk") ||
            Build.PRODUCT.contains("sdk_x86") ||
            Build.PRODUCT.contains("sdk_gphone64_arm64") ||
            Build.PRODUCT.contains("vbox86p") ||
            Build.PRODUCT.contains("emulator") ||
            Build.PRODUCT.contains("simulator")
        );
    }

    private int calcTotalPercent(final int percent, final int min, final int max) {
        return (percent * (max - min)) / 100 + min;
    }

    void notifyDownload(final String id, final int percent) {
        return;
    }

    private String randomString(final int len) {
        final StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(AB.charAt(rnd.nextInt(AB.length())));
        return sb.toString();
    }

    private File unzip(final String id, final File zipFile, final String dest) throws IOException {
        final File targetDirectory = new File(this.documentsDir, dest);
        final ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
        try {
            int count;
            final int bufferSize = 8192;
            final byte[] buffer = new byte[bufferSize];
            final long lengthTotal = zipFile.length();
            long lengthRead = bufferSize;
            int percent = 0;
            this.notifyDownload(id, 75);

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                final File file = new File(targetDirectory, entry.getName());
                final String canonicalPath = file.getCanonicalPath();
                final String canonicalDir = (new File(String.valueOf(targetDirectory))).getCanonicalPath();
                final File dir = entry.isDirectory() ? file : file.getParentFile();

                if (!canonicalPath.startsWith(canonicalDir)) {
                    throw new FileNotFoundException(
                        "SecurityException, Failed to ensure directory is the start path : " + canonicalDir + " of " + canonicalPath
                    );
                }

                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new FileNotFoundException("Failed to ensure directory: " + dir.getAbsolutePath());
                }

                if (entry.isDirectory()) {
                    continue;
                }

                try (final FileOutputStream outputStream = new FileOutputStream(file)) {
                    while ((count = zis.read(buffer)) != -1) outputStream.write(buffer, 0, count);
                }

                final int newPercent = (int) ((lengthRead * 100) / lengthTotal);
                if (lengthTotal > 1 && newPercent != percent) {
                    percent = newPercent;
                    this.notifyDownload(id, this.calcTotalPercent(percent, 75, 90));
                }

                lengthRead += entry.getCompressedSize();
            }
            return targetDirectory;
        } finally {
            try {
                zis.close();
            } catch (final IOException e) {
                Log.e(TAG, "Failed to close zip input stream", e);
            }
        }
    }

    private void flattenAssets(final File sourceFile, final String dest) throws IOException {
        if (!sourceFile.exists()) {
            throw new FileNotFoundException("Source file not found: " + sourceFile.getPath());
        }
        final File destinationFile = new File(this.documentsDir, dest);
        destinationFile.getParentFile().mkdirs();
        final String[] entries = sourceFile.list(this.filter);
        if (entries == null || entries.length == 0) {
            throw new IOException("Source file was not a directory or was empty: " + sourceFile.getPath());
        }
        if (entries.length == 1 && !"index.html".equals(entries[0])) {
            final File child = new File(sourceFile, entries[0]);
            child.renameTo(destinationFile);
        } else {
            sourceFile.renameTo(destinationFile);
        }
        sourceFile.delete();
    }

    private File downloadFile(final String id, final String url, final String dest) throws IOException {
        final URL u = new URL(url);
        final URLConnection connection = u.openConnection();
        final InputStream is = u.openStream();
        final DataInputStream dis = new DataInputStream(is);

        final File target = new File(this.documentsDir, dest);
        target.getParentFile().mkdirs();
        target.createNewFile();
        final FileOutputStream fos = new FileOutputStream(target);

        final long totalLength = connection.getContentLength();
        final int bufferSize = 1024;
        final byte[] buffer = new byte[bufferSize];
        int length;

        int bytesRead = bufferSize;
        int percent = 0;
        this.notifyDownload(id, 10);
        while ((length = dis.read(buffer)) > 0) {
            fos.write(buffer, 0, length);
            final int newPercent = (int) ((bytesRead * 100) / totalLength);
            if (totalLength > 1 && newPercent != percent) {
                percent = newPercent;
                this.notifyDownload(id, this.calcTotalPercent(percent, 10, 70));
            }
            bytesRead += length;
        }
        return target;
    }

    private void deleteDirectory(final File file) throws IOException {
        if (file.isDirectory()) {
            final File[] entries = file.listFiles();
            if (entries != null) {
                for (final File entry : entries) {
                    this.deleteDirectory(entry);
                }
            }
        }
        if (!file.delete()) {
            throw new IOException("Failed to delete: " + file);
        }
    }

    private void setCurrentBundle(final File bundle) {
        this.editor.putString(WebView.CAP_SERVER_PATH, bundle.getPath());
        Log.i(TAG, "Current bundle set to: " + bundle);
        this.editor.commit();
    }

    private String getChecksum(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(bytes);
        }
        CRC32 crc = new CRC32();
        crc.update(bytes);
        String enc = String.format("%08X", crc.getValue());
        return enc.toLowerCase();
    }

    public BundleInfo download(final String url, final String version) throws IOException {
        final String id = this.randomString(10);
        this.saveBundleInfo(id, new BundleInfo(id, version, BundleStatus.DOWNLOADING, new Date(System.currentTimeMillis()), ""));
        this.notifyDownload(id, 0);
        final String idName = bundleDirectory + "/" + id;
        this.notifyDownload(id, 5);
        final File downloaded = this.downloadFile(id, url, this.randomString(10));
        final String checksum = this.getChecksum(downloaded);
        this.notifyDownload(id, 71);
        final File unzipped = this.unzip(id, downloaded, this.randomString(10));
        downloaded.delete();
        this.notifyDownload(id, 91);
        this.flattenAssets(unzipped, idName);
        this.notifyDownload(id, 100);
        this.saveBundleInfo(id, null);
        BundleInfo info = new BundleInfo(id, version, BundleStatus.PENDING, new Date(System.currentTimeMillis()), checksum);
        this.saveBundleInfo(id, info);
        return info;
    }

    public List<BundleInfo> list() {
        final List<BundleInfo> res = new ArrayList<>();
        final File destHot = new File(this.documentsDir, bundleDirectory);
        Log.d(TAG, "list File : " + destHot.getPath());
        if (destHot.exists()) {
            for (final File i : destHot.listFiles()) {
                final String id = i.getName();
                res.add(this.getBundleInfo(id));
            }
        } else {
            Log.i(TAG, "No versions available to list" + destHot);
        }
        return res;
    }

    public Boolean delete(final String id, final Boolean removeInfo) throws IOException {
        final BundleInfo deleted = this.getBundleInfo(id);
        if (deleted.isBuiltin() || this.getCurrentBundleId().equals(id)) {
            Log.e(TAG, "Cannot delete " + id);
            return false;
        }
        final File bundle = new File(this.documentsDir, bundleDirectory + "/" + id);
        if (bundle.exists()) {
            this.deleteDirectory(bundle);
            if (removeInfo) {
                this.removeBundleInfo(id);
            } else {
                this.saveBundleInfo(id, deleted.setStatus(BundleStatus.DELETED));
            }
            return true;
        }
        Log.e(TAG, "Directory not removed: " + bundle.getPath());
        this.sendStats("delete", deleted.getVersionName());
        return false;
    }

    public Boolean delete(final String id) throws IOException {
        return this.delete(id, true);
    }

    private File getBundleDirectory(final String id) {
        return new File(this.documentsDir, bundleDirectory + "/" + id);
    }

    private boolean bundleExists(final String id) {
        final File bundle = this.getBundleDirectory(id);
        final BundleInfo bundleInfo = this.getBundleInfo(id);
        if (bundle == null || !bundle.exists() || bundleInfo.isDeleted()) {
            return false;
        }
        return new File(bundle.getPath(), "/index.html").exists();
    }

    public Boolean set(final BundleInfo bundle) {
        return this.set(bundle.getId());
    }

    public Boolean set(final String id) {
        final BundleInfo newBundle = this.getBundleInfo(id);
        if (newBundle.isBuiltin()) {
            this.reset();
            return true;
        }
        final File bundle = this.getBundleDirectory(id);
        Log.i(TAG, "Setting next active bundle: " + id);
        if (this.bundleExists(id)) {
            this.setCurrentBundle(bundle);
            this.setBundleStatus(id, BundleStatus.PENDING);
            this.sendStats("set", newBundle.getVersionName());
            return true;
        }
        this.sendStats("set_fail", newBundle.getVersionName());
        return false;
    }

    public void reset() {
        this.reset(false);
    }

    public void setSuccess(final BundleInfo bundle, Boolean autoDeletePrevious) {
        this.setBundleStatus(bundle.getId(), BundleStatus.SUCCESS);
        final BundleInfo fallback = this.getFallbackBundle();
        Log.d(CapacitorUpdater.TAG, "Fallback bundle is: " + fallback);
        Log.i(CapacitorUpdater.TAG, "Version successfully loaded: " + bundle.getVersionName());
        if (autoDeletePrevious && !fallback.isBuiltin()) {
            try {
                final Boolean res = this.delete(fallback.getId());
                if (res) {
                    Log.i(CapacitorUpdater.TAG, "Deleted previous bundle: " + fallback.getVersionName());
                }
            } catch (final IOException e) {
                Log.e(CapacitorUpdater.TAG, "Failed to delete previous bundle: " + fallback.getVersionName(), e);
            }
        }
        this.setFallbackBundle(bundle);
    }

    public void setError(final BundleInfo bundle) {
        this.setBundleStatus(bundle.getId(), BundleStatus.ERROR);
    }

    public void reset(final boolean internal) {
        Log.d(CapacitorUpdater.TAG, "reset: " + internal);
        this.setCurrentBundle(new File("public"));
        this.setFallbackBundle(null);
        this.setNextBundle(null);
        if (!internal) {
            this.sendStats("reset", this.getCurrentBundle().getVersionName());
        }
    }

    private JSONObject createInfoObject() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("platform", "android");
        json.put("device_id", this.deviceID);
        json.put("app_id", this.appId);
        json.put("custom_id", this.customId);
        json.put("version_build", this.versionBuild);
        json.put("version_code", this.versionCode);
        json.put("version_os", this.versionOs);
        json.put("version_name", this.getCurrentBundle().getVersionName());
        json.put("plugin_version", this.pluginVersion);
        json.put("is_emulator", this.isEmulator());
        json.put("is_prod", this.isProd());
        return json;
    }

    public void getLatest(final String updateUrl, final Callback callback) {
        try {
            JSONObject json = this.createInfoObject();

            Log.i(CapacitorUpdater.TAG, "Auto-update parameters: " + json);
            // Building a request
            JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                updateUrl,
                json,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        callback.callback(response);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "Error getting Latest", error);
                    }
                }
            );
            this.requestQueue.add(request);
        } catch (JSONException ex) {
            // Catch if something went wrong with the params
            Log.e(TAG, "Error getLatest JSONException", ex);
        }
    }

    public void setChannel(final String channel, final CallbackChannel callback) {
        String channelUrl = this.channelUrl;
        if (channelUrl == null || "".equals(channelUrl) || channelUrl.length() == 0) {
            return;
        }
        try {
            JSONObject json = this.createInfoObject();
            json.put("channel", channel);

            // Building a request
            JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                channelUrl,
                json,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject res) {
                        final JSObject ret = new JSObject();
                        Iterator<String> keys = res.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            if (res.has(key)) {
                                try {
                                    ret.put(key, res.get(key));
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                        Log.i(TAG, "Channel set to \"" + channel);
                        callback.callback(ret);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "Error set channel: " + error);
                    }
                }
            );
            this.requestQueue.add(request);
        } catch (JSONException ex) {
            // Catch if something went wrong with the params
            Log.e(TAG, "Error setChannel JSONException", ex);
        }
    }

    public void getChannel(final CallbackChannel callback) {
        String channelUrl = this.channelUrl;
        if (channelUrl == null || "".equals(channelUrl) || channelUrl.length() == 0) {
            return;
        }
        try {
            JSONObject json = this.createInfoObject();

            // Building a request
            JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.PUT,
                channelUrl,
                json,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject res) {
                        final JSObject ret = new JSObject();
                        Iterator<String> keys = res.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            if (res.has(key)) {
                                try {
                                    ret.put(key, res.get(key));
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                        Log.i(TAG, "Channel get to \"" + ret);
                        callback.callback(ret);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "Error get channel: " + error);
                    }
                }
            );
            this.requestQueue.add(request);
        } catch (JSONException ex) {
            // Catch if something went wrong with the params
            Log.e(TAG, "Error getChannel JSONException", ex);
        }
    }

    public void sendStats(final String action, final String versionName) {
        String statsUrl = this.statsUrl;
        if (statsUrl == null || "".equals(statsUrl) || statsUrl.length() == 0) {
            return;
        }
        try {
            JSONObject json = this.createInfoObject();
            json.put("action", action);

            // Building a request
            JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                statsUrl,
                json,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        Log.i(TAG, "Stats send for \"" + action + "\", version " + versionName);
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "Error sending stats: " + error);
                    }
                }
            );
            this.requestQueue.add(request);
        } catch (JSONException ex) {
            // Catch if something went wrong with the params
            Log.e(TAG, "Error sendStats JSONException", ex);
        }
    }

    public BundleInfo getBundleInfo(final String id) {
        String trueId = BundleInfo.VERSION_UNKNOWN;
        if (id != null) {
            trueId = id;
        }
        Log.d(TAG, "Getting info for bundle [" + trueId + "]");
        BundleInfo result;
        if (BundleInfo.ID_BUILTIN.equals(trueId)) {
            result = new BundleInfo(trueId, null, BundleStatus.SUCCESS, "", "");
        } else if (BundleInfo.VERSION_UNKNOWN.equals(trueId)) {
            result = new BundleInfo(trueId, null, BundleStatus.ERROR, "", "");
        } else {
            try {
                String stored = this.prefs.getString(trueId + INFO_SUFFIX, "");
                result = BundleInfo.fromJSON(stored);
            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse info for bundle [" + trueId + "] ", e);
                result = new BundleInfo(trueId, null, BundleStatus.PENDING, "", "");
            }
        }

        Log.d(TAG, "Returning info [" + trueId + "] " + result);
        return result;
    }

    public BundleInfo getBundleInfoByName(final String versionName) {
        final List<BundleInfo> installed = this.list();
        for (final BundleInfo i : installed) {
            if (i.getVersionName().equals(versionName)) {
                return i;
            }
        }
        return null;
    }

    private void removeBundleInfo(final String id) {
        this.saveBundleInfo(id, null);
    }

    private void saveBundleInfo(final String id, final BundleInfo info) {
        if (id == null || (info != null && (info.isBuiltin() || info.isUnknown()))) {
            Log.d(TAG, "Not saving info for bundle: [" + id + "] " + info);
            return;
        }

        if (info == null) {
            Log.d(TAG, "Removing info for bundle [" + id + "]");
            this.editor.remove(id + INFO_SUFFIX);
        } else {
            final BundleInfo update = info.setId(id);
            Log.d(TAG, "Storing info for bundle [" + id + "] " + update.toString());
            this.editor.putString(id + INFO_SUFFIX, update.toString());
        }
        this.editor.commit();
    }

    public void setVersionName(final String id, final String name) {
        if (id != null) {
            Log.d(TAG, "Setting name for bundle [" + id + "] to " + name);
            BundleInfo info = this.getBundleInfo(id);
            this.saveBundleInfo(id, info.setVersionName(name));
        }
    }

    private void setBundleStatus(final String id, final BundleStatus status) {
        if (id != null && status != null) {
            BundleInfo info = this.getBundleInfo(id);
            Log.d(TAG, "Setting status for bundle [" + id + "] to " + status);
            this.saveBundleInfo(id, info.setStatus(status));
        }
    }

    private String getCurrentBundleId() {
        if (this.isUsingBuiltin()) {
            return BundleInfo.ID_BUILTIN;
        } else {
            final String path = this.getCurrentBundlePath();
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    public BundleInfo getCurrentBundle() {
        return this.getBundleInfo(this.getCurrentBundleId());
    }

    public String getCurrentBundlePath() {
        String path = this.prefs.getString(WebView.CAP_SERVER_PATH, "public");
        if ("".equals(path.trim())) {
            return "public";
        }
        return path;
    }

    public Boolean isUsingBuiltin() {
        return this.getCurrentBundlePath().equals("public");
    }

    public BundleInfo getFallbackBundle() {
        final String id = this.prefs.getString(FALLBACK_VERSION, BundleInfo.ID_BUILTIN);
        return this.getBundleInfo(id);
    }

    private void setFallbackBundle(final BundleInfo fallback) {
        this.editor.putString(FALLBACK_VERSION, fallback == null ? BundleInfo.ID_BUILTIN : fallback.getId());
        this.editor.commit();
    }

    public BundleInfo getNextBundle() {
        final String id = this.prefs.getString(NEXT_VERSION, null);
        if (id == null) return null;
        return this.getBundleInfo(id);
    }

    public boolean setNextBundle(final String next) {
        if (next == null) {
            this.editor.remove(NEXT_VERSION);
        } else {
            final BundleInfo newBundle = this.getBundleInfo(next);
            if (!newBundle.isBuiltin() && !this.bundleExists(next)) {
                return false;
            }
            this.editor.putString(NEXT_VERSION, next);
            this.setBundleStatus(next, BundleStatus.PENDING);
        }
        this.editor.commit();
        return true;
    }
}
