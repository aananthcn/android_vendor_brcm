package com.brcm.mapsshim;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

// Registers android.intent.category.APP_MAPS (priority 0) so the system resolves
// to this shim rather than car-maps-placeholder (priority -1000) in contexts
// outside the CarLauncher (e.g. navigation intents from other apps).
//
// The CarLauncher itself does NOT embed this shim.  It uses the Organic Maps
// activity directly, configured via config_homeCardPreferredMapActivities in
// CarLauncherUtils, which bypasses the APP_MAPS resolver for the home-screen
// maps panel.
//
// When this shim IS launched (non-launcher context), it forwards immediately to
// Organic Maps and finishes.
public class MapsShimActivity extends Activity {
    private static final String TAG = "MapsShim";
    // fetch_apk.sh downloads the -web- flavour whose applicationId is app.organicmaps.web.
    // The activity class name is unchanged across flavours.
    private static final String ORGANIC_MAPS_PKG = "app.organicmaps.web";
    private static final String ORGANIC_MAPS_ACTIVITY = "app.organicmaps.MwmActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PackageManager pm = getPackageManager();

        // getLaunchIntentForPackage queries for CATEGORY_LAUNCHER; on AAOS the
        // main activity may register only CATEGORY_CAR_LAUNCHER, returning null.
        // Fall back to an explicit component so both cases work.
        Intent forward = pm.getLaunchIntentForPackage(ORGANIC_MAPS_PKG);
        if (forward == null) {
            Intent explicit = new Intent(Intent.ACTION_MAIN);
            explicit.setComponent(new ComponentName(ORGANIC_MAPS_PKG, ORGANIC_MAPS_ACTIVITY));
            if (pm.resolveActivity(explicit, 0) != null) {
                forward = explicit;
            }
        }

        if (forward == null) {
            Log.e(TAG, "Organic Maps not installed or not visible");
            finish();
            return;
        }

        // Preserve any geo: URI passed by the caller (e.g. navigation requests).
        Uri data = getIntent().getData();
        if (data != null) {
            forward.setAction(Intent.ACTION_VIEW);
            forward.setData(data);
        }

        forward.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            Log.i(TAG, "forwarding to Organic Maps" + (data != null ? " with " + data : ""));
            startActivity(forward);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "failed to start Organic Maps", e);
        }
        finish();
    }
}
