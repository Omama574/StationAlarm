package com.omama.stationalarm.repository;

import android.content.Context;

import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.LocationServices;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Routes GeofenceManager's LocationServices.getGeofencingClient() to {@link FakeGms#CLIENT}. */
@Implements(LocationServices.class)
public class ShadowLocationServices {
    @Implementation
    public static GeofencingClient getGeofencingClient(Context context) {
        return FakeGms.CLIENT;
    }
}
