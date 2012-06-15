/* (C) 2012 Pragmatic Software
   This Source Code Form is subject to the terms of the Mozilla Public
   License, v. 2.0. If a copy of the MPL was not distributed with this
   file, You can obtain one at http://mozilla.org/MPL/2.0/
 */

package com.googlecode.networklog;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;
import android.app.ProgressDialog;
import android.os.Handler;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;

public class ApplicationsTracker {
  public static ArrayList<AppEntry> installedApps;
  public static HashMap<String, AppEntry> installedAppsHash;
  public static HashMap<String, Object> loadingIcon = new HashMap<String, Object>();
  public static ProgressDialog dialog;
  public static int appCount;
  public static Object dialogLock = new Object();
  public static Object installedAppsLock = new Object();
  public static PackageManager pm = null;

  public static class AppEntry {
    String name;
    String nameLowerCase;
    String packageName;
    Drawable icon;
    int uid;
    String uidString;

    public String toString() {
      return "(" + uidString + ") " + name;
    }
  }

  public static class AppCache {
    public HashMap<String, String> labelCache;
    private Context context;
    private boolean isDirty = false;

    private AppCache() {}

    public AppCache(Context context) {
      this.context = context;
      loadCache();
    }

    public String getLabel(PackageManager pm, ApplicationInfo app) {
      String label = labelCache.get(app.packageName);

      if(label == null) {
        label = StringPool.get(pm.getApplicationLabel(app).toString());
        labelCache.put(app.packageName, label);
        isDirty = true;
      }

      return label;
    }

    public void loadCache() {
      try {
        File file = new File(context.getDir("data", Context.MODE_PRIVATE), "applabels.cache");    
        ObjectInputStream inputStream = new ObjectInputStream(new FileInputStream(file));
        labelCache = (HashMap<String, String>) inputStream.readObject();
        isDirty = false;
        inputStream.close();
      } catch (Exception e) {
        Log.d("[NetworkLog]", "Exception loading app cache", e);
        labelCache = new HashMap<String, String>();
      }
    }

    public void saveCache() {
      if(isDirty == false) {
        return;
      }

      try {
        File file = new File(context.getDir("data", Context.MODE_PRIVATE), "applabels.cache");    
        ObjectOutputStream outputStream = new ObjectOutputStream(new FileOutputStream(file));
        outputStream.writeObject(labelCache);
        outputStream.flush();
        outputStream.close();
      } catch (Exception e) {
        Log.d("[NetworkLog]", "Exception saving app cache" , e);
      }
    }
  }

  public static void restoreData(RetainInstanceData data) {
    synchronized(installedAppsLock) {
      installedApps = data.applicationsTrackerInstalledApps;
    }

    installedAppsHash = data.applicationsTrackerInstalledAppsHash;
  }

  public static Drawable loadIcon(final Context context, final String packageName) {
    AppEntry item = null;

    for(AppEntry app : installedApps) {
      if(app.packageName.equals(packageName)) {
        item = app;
        break;
      }
    }

    if(item == null) {
      MyLog.d("Failed to find item for " + packageName);
      return null;
    }

    if(item.icon != null) {
      return item.icon;
    }

    Object loading;
    synchronized(loadingIcon) {
      loading = loadingIcon.get(packageName);
    }

    if(loading == null) {
      synchronized(loadingIcon) {
        loadingIcon.put(packageName, packageName);
      }

      final AppEntry entry = item;
      new Thread(new Runnable() {
        public void run() {
          MyLog.d("Loading icon for " + entry);
          try {
            if(pm == null) {
              pm = context.getPackageManager();
            }

            entry.icon = pm.getApplicationIcon(packageName);

            synchronized(loadingIcon) {
              loadingIcon.remove(packageName);
            }

            NetworkLog.handler.post(new Runnable() {
              public void run() {
                NetworkLog.logFragment.refreshAdapter();
                NetworkLog.appFragment.refreshAdapter();
                MyLog.d("Icon loaded, adapters refreshed");
              }
            });
          } catch(Exception e) {
            // ignored
          }
        }
      }, "LoadIcon:" + packageName).start();

      return null;
    }

    return null;
  }

  public static void getInstalledApps(final Context context, final Handler handler) {
    MyLog.d("Loading installed apps");

    synchronized(installedAppsLock) {
      if(NetworkLog.data == null) {
        installedApps = new ArrayList<AppEntry>();
        installedAppsHash = new HashMap<String, AppEntry>();
      } else {
        restoreData(NetworkLog.data);
        installedApps.clear();
        installedAppsHash.clear();
      }

      if(pm == null) {
        pm = context.getPackageManager();
      }

      List<ApplicationInfo> apps = pm.getInstalledApplications(0);

      appCount = apps.size();

      handler.post(new Runnable() {
        public void run() {
          MyLog.d("Showing progress dialog; size: " + appCount);

          synchronized(dialogLock) {
            dialog = new ProgressDialog(context);
            dialog.setIndeterminate(false);
            dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            dialog.setMax(appCount);
            dialog.setCancelable(false);
            dialog.setTitle("");
            dialog.setMessage("Loading apps");
            dialog.show();
          }
        }
      });

      int count = 0;

      AppCache appCache = new AppCache(context);

      for(final ApplicationInfo app : apps) {
        MyLog.d("Processing app " + app);

        if(NetworkLog.initRunner.running == false) {
          MyLog.d("[ApplicationsTracker] Initialization aborted");
          return;
        }

        final int progress = ++count;
        handler.post(new Runnable() {
          public void run() {
            synchronized(dialogLock) {
              if(dialog != null) {
                dialog.setProgress(progress);
              }
            }
          }
        });

        int uid = app.uid;
        String sUid = Integer.toString(uid);

        AppEntry entryHash = installedAppsHash.get(sUid);

        AppEntry entry = new AppEntry();

        entry.name = appCache.getLabel(pm, app);
        entry.nameLowerCase = StringPool.get(StringPool.getLowerCase(entry.name));
        entry.icon = null;
        entry.uid = uid;
        entry.uidString = StringPool.get(String.valueOf(uid));
        entry.packageName = StringPool.get(app.packageName);

        installedApps.add(entry);

        if(entryHash != null) {
          entryHash.name.concat("; " + entry.name);
        } else {
          installedAppsHash.put(sUid, entry);
        }
      }

      appCache.saveCache();

      AppEntry entry = new AppEntry();
      entry.name = StringPool.get("Kernel");
      entry.nameLowerCase = StringPool.getLowerCase("Kernel");
      entry.icon = context.getResources().getDrawable(R.drawable.linux_icon);
      entry.packageName = StringPool.get(entry.nameLowerCase);
      entry.uid = -1;
      entry.uidString = StringPool.get("-1");

      installedApps.add(entry);
      installedAppsHash.put("-1", entry);

      String[] systemUids = { "root", "system", "radio", "bluetooth", "nobody", "misc",
        "graphics", "input", "audio", "camera", "log", "compass", "mount", "wifi", "dhcp",
        "adb", "install", "media", "nfc", "shell", "cache", "diag", "vpn", "keystore", "usb",
        "gps", "inet", "net_raw", "net_admin", "net_bt_admin", "net_bt",
        /* motorola */
        "mot_accy", "mot_pwric", "mot_usb", "mot_drm", "mot_tcmd", "mot_sec_rtc", "mot_tombstone",
        "mot_tpapi", "mot_secclkd" };

      for (String name : systemUids) {
        int uid = android.os.Process.getUidForName(name);

        if(uid == -1) {
          continue;
        }

        String uidString = StringPool.get(String.valueOf(uid));
        AppEntry entryHash = installedAppsHash.get(uidString);

        if(entryHash == null) {
          entry = new AppEntry();
          entry.name = StringPool.get(name);
          entry.nameLowerCase = StringPool.getLowerCase(name);
          entry.icon = context.getResources().getDrawable(R.drawable.android_icon);
          entry.packageName = StringPool.get(entry.nameLowerCase);
          entry.uid = uid;
          entry.uidString = StringPool.get(uidString);

          installedApps.add(entry);
          installedAppsHash.put(uidString, entry);
        }
      }

      handler.post(new Runnable() {
        public void run() {
          MyLog.d("Dismissing dialog");

          synchronized(dialogLock) {
            if(dialog != null) {
              dialog.dismiss();
              dialog = null;
            }
          }
        }
      });
      MyLog.d("Done getting installed apps");
    }
  }
}