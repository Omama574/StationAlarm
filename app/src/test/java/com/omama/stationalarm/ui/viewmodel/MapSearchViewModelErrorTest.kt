package com.omama.stationalarm.ui.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.data.UserPreferences
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Covers the user-facing copy for each kind of network failure. The error
 * message branch list is short but each branch is a UX commitment — if a
 * future refactor collapses SocketTimeoutException back into IOException,
 * the snackbar starts blaming "No internet" on a flaky network and the
 * user does the wrong thing in response.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MapSearchViewModelErrorTest {

    private lateinit var vm: MapSearchViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The VM's init block reads from UserPreferences indirectly (no flow
        // collected synchronously, but better safe than NPE-on-init).
        UserPreferences.initialize(app)
        vm = MapSearchViewModel(app)
    }

    private fun httpError(code: Int): HttpException {
        val body = "".toResponseBody("text/plain".toMediaTypeOrNull())
        return HttpException(Response.error<Any>(code, body))
    }

    @Test
    fun socketTimeout_mapsToTimeoutCopy() {
        val msg = vm.errorMessageFor(SocketTimeoutException("read timed out"))
        assertTrue("got: $msg", msg.contains("timed out", ignoreCase = true))
    }

    @Test
    fun genericIO_mapsToNoInternet() {
        val msg = vm.errorMessageFor(IOException("connect failed"))
        assertTrue("got: $msg", msg.contains("No internet", ignoreCase = true))
    }

    @Test
    fun http429_mapsToRateLimited() {
        val msg = vm.errorMessageFor(httpError(429))
        assertTrue("got: $msg", msg.contains("Too many", ignoreCase = true))
    }

    @Test
    fun http503_mapsToServiceDown() {
        val msg = vm.errorMessageFor(httpError(503))
        assertTrue("got: $msg", msg.contains("down", ignoreCase = true))
    }

    @Test
    fun http500_mapsToServiceDown() {
        val msg = vm.errorMessageFor(httpError(500))
        assertTrue("got: $msg", msg.contains("down", ignoreCase = true))
    }

    @Test
    fun http418_mapsToGenericCode() {
        val msg = vm.errorMessageFor(httpError(418))
        assertTrue("got: $msg", msg.contains("418"))
    }

    @Test
    fun unknownThrowable_mapsToGenericUnavailable() {
        val msg = vm.errorMessageFor(RuntimeException("???"))
        assertEquals("Search unavailable.", msg)
    }
}
