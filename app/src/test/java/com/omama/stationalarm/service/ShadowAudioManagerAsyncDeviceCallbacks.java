package com.omama.stationalarm.service;

import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAudioManager;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * Robolectric's ShadowAudioManager calls {@code onAudioDevicesAdded(currentDevices)} synchronously, inside
 * {@code registerAudioDeviceCallback()}, and ignores the Handler. The real AudioManager posts that first
 * notification to the Handler, i.e. it arrives AFTER the registering method returns. AlarmAudioController
 * registers the callback before it starts any player, so under the stock shadow its first notification finds
 * "no route yet" and swaps in the default ringtone, which hides every custom-URI code path. This shadow restores
 * the real timing for the registration notification (later add/remove notifications are left as they are).
 */
@Implements(AudioManager.class)
public class ShadowAudioManagerAsyncDeviceCallbacks extends ShadowAudioManager {

    @Override
    @Implementation
    @SuppressWarnings("unchecked")
    protected void registerAudioDeviceCallback(final AudioDeviceCallback callback, Handler handler) {
        try {
            Field field = ShadowAudioManager.class.getDeclaredField("audioDeviceCallbacks");
            field.setAccessible(true);
            ((Set<AudioDeviceCallback>) field.get(this)).add(callback);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        final AudioDeviceInfo[] current = getDevices(AudioManager.GET_DEVICES_ALL);
        Handler target = handler != null ? handler : new Handler(Looper.getMainLooper());
        target.post(new Runnable() {
            @Override
            public void run() {
                callback.onAudioDevicesAdded(current);
            }
        });
    }
}
