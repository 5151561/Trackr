package com.trackr.ui.screens.more

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.trackr.data.remote.update.IUpdateChecker
import com.trackr.domain.model.AppUpdateInfo
import com.trackr.domain.model.UpdateCheckResult
import com.trackr.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AboutViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
    }

    private class FakeUpdateChecker(
        var result: UpdateCheckResult
    ) : IUpdateChecker {
        var callCount = 0
        override suspend fun checkForUpdates(
            currentVersionCode: Int,
            currentVersionName: String
        ): UpdateCheckResult {
            callCount++
            return result
        }
    }

    @Test
    fun initialState_isIdle() {
        val fakeChecker = FakeUpdateChecker(UpdateCheckResult.UpToDate("0.0.2"))
        val viewModel = AboutViewModel(
            application = app,
            updateChecker = fakeChecker,
            versionProvider = { 2 to "0.0.2" }
        )

        assertEquals(AboutUpdateUiState.Idle, viewModel.updateState.value)
    }

    @Test
    fun checkForUpdates_whenHasUpdate_updatesStateToHasUpdate() = runTest {
        val updateInfo = AppUpdateInfo(
            versionCode = 3,
            versionName = "0.0.3",
            changelog = "全新发布",
            downloadUrl = "https://github.com/5151561/Trackr/releases",
            forceUpdate = false
        )
        val fakeChecker = FakeUpdateChecker(UpdateCheckResult.HasUpdate(updateInfo, isForceUpdate = false))
        val viewModel = AboutViewModel(
            application = app,
            updateChecker = fakeChecker,
            versionProvider = { 2 to "0.0.2" }
        )

        viewModel.checkForUpdates()
        advanceUntilIdle()

        val state = viewModel.updateState.value
        assertTrue(state is AboutUpdateUiState.HasUpdate)
        val hasUpdate = state as AboutUpdateUiState.HasUpdate
        assertEquals("0.0.3", hasUpdate.updateInfo.versionName)
        assertEquals(false, hasUpdate.isForce)
        assertEquals(1, fakeChecker.callCount)
    }

    @Test
    fun checkForUpdates_whenUpToDate_updatesStateToUpToDate() = runTest {
        val fakeChecker = FakeUpdateChecker(UpdateCheckResult.UpToDate("0.0.2"))
        val viewModel = AboutViewModel(
            application = app,
            updateChecker = fakeChecker,
            versionProvider = { 2 to "0.0.2" }
        )

        viewModel.checkForUpdates()
        advanceUntilIdle()

        val state = viewModel.updateState.value
        assertTrue(state is AboutUpdateUiState.UpToDate)
        assertEquals("0.0.2", (state as AboutUpdateUiState.UpToDate).currentVersionName)
    }

    @Test
    fun checkForUpdates_whenError_updatesStateToError() = runTest {
        val fakeChecker = FakeUpdateChecker(UpdateCheckResult.Error("网络连接超时"))
        val viewModel = AboutViewModel(
            application = app,
            updateChecker = fakeChecker,
            versionProvider = { 2 to "0.0.2" }
        )

        viewModel.checkForUpdates()
        advanceUntilIdle()

        val state = viewModel.updateState.value
        assertTrue(state is AboutUpdateUiState.Error)
        assertEquals("网络连接超时", (state as AboutUpdateUiState.Error).message)
    }

    @Test
    fun dismissUpdateDialog_resetsStateToIdle() = runTest {
        val fakeChecker = FakeUpdateChecker(UpdateCheckResult.UpToDate("0.0.2"))
        val viewModel = AboutViewModel(
            application = app,
            updateChecker = fakeChecker,
            versionProvider = { 2 to "0.0.2" }
        )

        viewModel.checkForUpdates()
        advanceUntilIdle()
        assertTrue(viewModel.updateState.value is AboutUpdateUiState.UpToDate)

        viewModel.dismissUpdateDialog()
        assertEquals(AboutUpdateUiState.Idle, viewModel.updateState.value)
    }
}
