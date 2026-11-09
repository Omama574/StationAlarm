package com.omama.stationalarm.geofence;

import android.content.Context;

import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.LocationServices;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Routes {@code LocationServices.getGeofencingClient(Context)} to {@link FakeGeofencing#getClient()} so that
 * GeofenceManager (an object with no injection seam) can be exercised without Google Play Services.
 * Register with {@code @Config(shadows = [ShadowLocationServicesGeofencing::class])}.
 */
@Implements(LocationServices.class)
public class ShadowLocationServicesGeofencing {

    @Implementation
    public static GeofencingClient getGeofencingClient(Context context) {
        return FakeGeofencing.getClient();
    }
}
