package com.omama.stationalarm.repository;

import android.app.Activity;
import android.app.PendingIntent;

import com.google.android.gms.common.api.Api;
import com.google.android.gms.common.api.internal.ApiKey;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.RuntimeExecutionException;
import com.google.android.gms.tasks.Task;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process stand-in for Google Play Services' GeofencingClient. GeofenceManager (behaviour-locked, an
 * `object`) fetches its client from LocationServices.getGeofencingClient(), which {@link ShadowLocationServices}
 * redirects here. Tasks complete inline so no main-looper idling is needed.
 *
 * State is static because the shadow is static; tests call {@link #reset()} in setUp and use unique station
 * ids so a straggler coroutine from an earlier test cannot be mistaken for the current one.
 */
public final class FakeGms {
    public static final String BOOM = "gms-boom";

    /** Request ids currently registered with the fake "OS". */
    public static final Set<String> registered = ConcurrentHashMap.newKeySet();
    /** Radius in metres per registered request id. */
    public static final Map<String, Float> radius = new ConcurrentHashMap<>();
    /** One entry per addGeofences() call (request ids in that call), including failed calls. */
    public static final List<List<String>> addCalls = new CopyOnWriteArrayList<>();
    /** One entry per removeGeofences(List) call, including failed calls. */
    public static final List<List<String>> removeCalls = new CopyOnWriteArrayList<>();

    public static volatile boolean failAdds;
    public static volatile boolean failRemoves;
    /** Fail this many upcoming addGeofences() calls, then succeed. */
    public static final AtomicInteger failNextAdds = new AtomicInteger();

    public static final GeofencingClient CLIENT = new Client();

    private FakeGms() {}

    public static void reset() {
        registered.clear();
        radius.clear();
        addCalls.clear();
        removeCalls.clear();
        failAdds = false;
        failRemoves = false;
        failNextAdds.set(0);
    }

    private static final class Client implements GeofencingClient {
        @Override
        public Task<Void> addGeofences(GeofencingRequest request, PendingIntent pi) {
            List<String> ids = new ArrayList<>();
            for (Geofence g : request.getGeofences()) ids.add(g.getRequestId());
            boolean fail = failAdds || failNextAdds.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
            if (!fail) {
                for (Geofence g : request.getGeofences()) {
                    registered.add(g.getRequestId());
                    radius.put(g.getRequestId(), g.getRadius());
                }
            }
            addCalls.add(ids);
            return new ImmediateTask(fail ? new IOException(BOOM) : null);
        }

        @Override
        public Task<Void> removeGeofences(List<String> ids) {
            boolean fail = failRemoves;
            if (!fail) {
                registered.removeAll(ids);
                for (String id : ids) radius.remove(id);
            }
            removeCalls.add(new ArrayList<>(ids));
            return new ImmediateTask(fail ? new IOException(BOOM) : null);
        }

        @Override
        public Task<Void> removeGeofences(PendingIntent pi) {
            registered.clear();
            radius.clear();
            return new ImmediateTask(null);
        }

        @Override
        public ApiKey<Api.ApiOptions.NoOptions> getApiKey() {
            throw new UnsupportedOperationException();
        }
    }

    /** A completed Task whose listeners fire synchronously on the calling thread. */
    static final class ImmediateTask extends Task<Void> {
        private final Exception error;

        ImmediateTask(Exception error) {
            this.error = error;
        }

        @Override public Task<Void> addOnFailureListener(OnFailureListener l) {
            if (error != null) l.onFailure(error);
            return this;
        }
        @Override public Task<Void> addOnFailureListener(Activity a, OnFailureListener l) { return addOnFailureListener(l); }
        @Override public Task<Void> addOnFailureListener(Executor e, OnFailureListener l) { return addOnFailureListener(l); }

        @Override public Task<Void> addOnSuccessListener(OnSuccessListener<? super Void> l) {
            if (error == null) l.onSuccess(null);
            return this;
        }
        @Override public Task<Void> addOnSuccessListener(Activity a, OnSuccessListener<? super Void> l) { return addOnSuccessListener(l); }
        @Override public Task<Void> addOnSuccessListener(Executor e, OnSuccessListener<? super Void> l) { return addOnSuccessListener(l); }

        @Override public Exception getException() { return error; }
        @Override public Void getResult() {
            if (error != null) throw new RuntimeExecutionException(error);
            return null;
        }
        @Override public <X extends Throwable> Void getResult(Class<X> type) throws X {
            if (error != null) throw new RuntimeExecutionException(error);
            return null;
        }
        @Override public boolean isCanceled() { return false; }
        @Override public boolean isComplete() { return true; }
        @Override public boolean isSuccessful() { return error == null; }
    }
}
