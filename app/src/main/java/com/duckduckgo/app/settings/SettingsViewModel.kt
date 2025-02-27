/*
 * Copyright (c) 2017 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckduckgo.anvil.annotations.ContributesViewModel
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.defaultbrowsing.DefaultBrowserDetector
import com.duckduckgo.app.email.EmailManager
import com.duckduckgo.app.fire.FireAnimationLoader
import com.duckduckgo.app.icon.api.AppIcon
import com.duckduckgo.app.pixels.AppPixelName.*
import com.duckduckgo.app.settings.SettingsViewModel.NetPState.CONNECTED
import com.duckduckgo.app.settings.SettingsViewModel.NetPState.CONNECTING
import com.duckduckgo.app.settings.SettingsViewModel.NetPState.DISCONNECTED
import com.duckduckgo.app.settings.SettingsViewModel.NetPState.INVALID
import com.duckduckgo.app.settings.clear.AppLinkSettingType
import com.duckduckgo.app.settings.clear.ClearWhatOption
import com.duckduckgo.app.settings.clear.ClearWhenOption
import com.duckduckgo.app.settings.clear.FireAnimation
import com.duckduckgo.app.settings.clear.getPixelValue
import com.duckduckgo.app.settings.db.SettingsDataStore
import com.duckduckgo.app.statistics.VariantManager
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.app.statistics.pixels.Pixel.PixelName
import com.duckduckgo.app.statistics.pixels.Pixel.PixelParameter.FIRE_ANIMATION
import com.duckduckgo.appbuildconfig.api.AppBuildConfig
import com.duckduckgo.appbuildconfig.api.isInternalBuild
import com.duckduckgo.autoconsent.api.Autoconsent
import com.duckduckgo.autofill.api.AutofillCapabilityChecker
import com.duckduckgo.di.scopes.ActivityScope
import com.duckduckgo.feature.toggles.api.FeatureToggle
import com.duckduckgo.mobile.android.app.tracking.AppTrackingProtection
import com.duckduckgo.mobile.android.ui.DuckDuckGoTheme
import com.duckduckgo.mobile.android.ui.store.ThemingDataStore
import com.duckduckgo.mobile.android.vpn.AppTpVpnFeature
import com.duckduckgo.mobile.android.vpn.VpnFeaturesRegistry
import com.duckduckgo.mobile.android.vpn.state.VpnStateMonitor
import com.duckduckgo.mobile.android.vpn.state.VpnStateMonitor.VpnRunningState
import com.duckduckgo.networkprotection.impl.NetPVpnFeature
import com.duckduckgo.networkprotection.impl.waitlist.NetPWaitlistState
import com.duckduckgo.networkprotection.impl.waitlist.store.NetPWaitlistRepository
import com.duckduckgo.privacy.config.api.Gpc
import com.duckduckgo.privacy.config.api.PrivacyFeatureName
import com.duckduckgo.sync.api.DeviceSyncState
import com.duckduckgo.windows.api.WindowsWaitlist
import com.duckduckgo.windows.api.WindowsWaitlistFeature
import com.duckduckgo.windows.api.WindowsWaitlistState
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

@ContributesViewModel(ActivityScope::class)
class SettingsViewModel @Inject constructor(
    private val themingDataStore: ThemingDataStore,
    private val settingsDataStore: SettingsDataStore,
    private val defaultWebBrowserCapability: DefaultBrowserDetector,
    private val variantManager: VariantManager,
    private val fireAnimationLoader: FireAnimationLoader,
    private val appTrackingProtection: AppTrackingProtection,
    private val gpc: Gpc,
    private val featureToggle: FeatureToggle,
    private val pixel: Pixel,
    private val appBuildConfig: AppBuildConfig,
    private val emailManager: EmailManager,
    private val autofillCapabilityChecker: AutofillCapabilityChecker,
    private val vpnFeaturesRegistry: VpnFeaturesRegistry,
    private val autoconsent: Autoconsent,
    private val windowsWaitlist: WindowsWaitlist,
    private val windowsFeature: WindowsWaitlistFeature,
    private val deviceSyncState: DeviceSyncState,
    private val vpnStateMonitor: VpnStateMonitor,
    private val netpWaitlistRepository: NetPWaitlistRepository,
) : ViewModel() {

    data class ViewState(
        val loading: Boolean = true,
        val version: String = "",
        val theme: DuckDuckGoTheme = DuckDuckGoTheme.LIGHT,
        val autoCompleteSuggestionsEnabled: Boolean = true,
        val showDefaultBrowserSetting: Boolean = false,
        val isAppDefaultBrowser: Boolean = false,
        val selectedFireAnimation: FireAnimation = FireAnimation.HeroFire,
        val automaticallyClearData: AutomaticallyClearData = AutomaticallyClearData(ClearWhatOption.CLEAR_NONE, ClearWhenOption.APP_EXIT_ONLY),
        val appIcon: AppIcon = AppIcon.DEFAULT,
        val globalPrivacyControlEnabled: Boolean = false,
        val appLinksSettingType: AppLinkSettingType = AppLinkSettingType.ASK_EVERYTIME,
        val appTrackingProtectionOnboardingShown: Boolean = false,
        val appTrackingProtectionEnabled: Boolean = false,
        val emailAddress: String? = null,
        val showAutofill: Boolean = false,
        val showSyncSetting: Boolean = false,
        val syncEnabled: Boolean = false,
        val autoconsentEnabled: Boolean = false,
        @StringRes val notificationsSettingSubtitleId: Int = R.string.settingsSubtitleNotificationsDisabled,
        val windowsWaitlistState: WindowsWaitlistState? = null,
        val networkProtectionState: NetPState = DISCONNECTED,
        val networkProtectionWaitlistState: NetPWaitlistState = NetPWaitlistState.NotUnlocked,
    )

    data class AutomaticallyClearData(
        val clearWhatOption: ClearWhatOption,
        val clearWhenOption: ClearWhenOption,
        val clearWhenOptionEnabled: Boolean = true,
    )

    enum class NetPState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        INVALID,
    }

    sealed class Command {
        object LaunchDefaultBrowser : Command()
        data class LaunchEmailProtection(val url: String) : Command()
        object LaunchEmailProtectionNotSUpported : Command()
        object LaunchFeedback : Command()
        object LaunchFireproofWebsites : Command()
        object LaunchAutofillSettings : Command()
        object LaunchAccessibilitySettings : Command()
        object LaunchLocation : Command()
        object LaunchWhitelist : Command()
        object LaunchAppIcon : Command()
        object LaunchAddHomeScreenWidget : Command()
        data class LaunchFireAnimationSettings(val animation: FireAnimation) : Command()
        data class LaunchThemeSettings(val theme: DuckDuckGoTheme) : Command()
        data class LaunchAppLinkSettings(val appLinksSettingType: AppLinkSettingType) : Command()
        object LaunchGlobalPrivacyControl : Command()
        object LaunchAutoconsent : Command()
        object LaunchAppTPTrackersScreen : Command()
        object LaunchNetPManagementScreen : Command()
        object LaunchNetPWaitlist : Command()
        object LaunchAppTPOnboarding : Command()
        object UpdateTheme : Command()
        data class ShowClearWhatDialog(val option: ClearWhatOption) : Command()
        data class ShowClearWhenDialog(val option: ClearWhenOption) : Command()
        object LaunchMacOs : Command()
        object LaunchNotificationsSettings : Command()
        object LaunchWindows : Command()
        object LaunchSyncSettings : Command()
    }

    private val viewState = MutableStateFlow(ViewState())

    private val command = Channel<Command>(1, BufferOverflow.DROP_OLDEST)

    init {
        pixel.fire(SETTINGS_OPENED)
    }

    fun start(notificationsEnabled: Boolean = false) {
        val defaultBrowserAlready = defaultWebBrowserCapability.isDefaultBrowser()
        val variant = variantManager.getVariant()
        val savedTheme = themingDataStore.theme
        val automaticallyClearWhat = settingsDataStore.automaticallyClearWhatOption
        val automaticallyClearWhen = settingsDataStore.automaticallyClearWhenOption
        val automaticallyClearWhenEnabled = isAutomaticallyClearingDataWhenSettingEnabled(automaticallyClearWhat)

        viewModelScope.launch {
            viewState.emit(
                currentViewState().copy(
                    loading = false,
                    theme = savedTheme,
                    autoCompleteSuggestionsEnabled = settingsDataStore.autoCompleteSuggestionsEnabled,
                    isAppDefaultBrowser = defaultBrowserAlready,
                    showDefaultBrowserSetting = defaultWebBrowserCapability.deviceSupportsDefaultBrowserConfiguration(),
                    version = obtainVersion(variant.key),
                    automaticallyClearData = AutomaticallyClearData(automaticallyClearWhat, automaticallyClearWhen, automaticallyClearWhenEnabled),
                    appIcon = settingsDataStore.appIcon,
                    selectedFireAnimation = settingsDataStore.selectedFireAnimation,
                    globalPrivacyControlEnabled = gpc.isEnabled() && featureToggle.isFeatureEnabled(PrivacyFeatureName.GpcFeatureName.value),
                    appLinksSettingType = getAppLinksSettingsState(settingsDataStore.appLinksEnabled, settingsDataStore.showAppLinksPrompt),
                    appTrackingProtectionOnboardingShown = appTrackingProtection.isOnboarded(),
                    appTrackingProtectionEnabled = vpnFeaturesRegistry.isFeatureRegistered(AppTpVpnFeature.APPTP_VPN),
                    emailAddress = emailManager.getEmailAddress(),
                    showAutofill = autofillCapabilityChecker.canAccessCredentialManagementScreen(),
                    autoconsentEnabled = autoconsent.isSettingEnabled(),
                    notificationsSettingSubtitleId = getNotificationsSettingSubtitleId(notificationsEnabled),
                    windowsWaitlistState = windowsSettingState(),
                    showSyncSetting = deviceSyncState.isFeatureEnabled(),
                    syncEnabled = deviceSyncState.isUserSignedInOnDevice(),
                    networkProtectionWaitlistState = netpWaitlistRepository.getState(appBuildConfig.isInternalBuild()),
                ),
            )
        }
    }

    // FIXME
    // We need to fix this. This logic as inside the start method but it messes with the unit tests
    // because when doing runningBlockingTest {} there is no delay and the tests crashes because this
    // becomes a while(true) without any delay
    fun startPollingAppTpEnableState() {
        viewModelScope.launch {
            while (isActive) {
                val isDeviceShieldEnabled = vpnFeaturesRegistry.isFeatureRegistered(AppTpVpnFeature.APPTP_VPN)
                if (currentViewState().appTrackingProtectionEnabled != isDeviceShieldEnabled) {
                    viewState.value = currentViewState().copy(
                        appTrackingProtectionOnboardingShown = appTrackingProtection.isOnboarded(),
                        appTrackingProtectionEnabled = isDeviceShieldEnabled,
                    )
                }
                delay(1_000)
            }
        }
    }

    fun startPollingNetPEnableState() {
        vpnStateMonitor.getStateFlow(NetPVpnFeature.NETP_VPN)
            .onEach {
                viewState.value = currentViewState().copy(
                    networkProtectionState = when (it.state) {
                        VpnRunningState.ENABLING -> CONNECTING
                        VpnRunningState.ENABLED -> CONNECTED
                        VpnRunningState.DISABLED -> DISCONNECTED
                        else -> INVALID
                    },
                )
            }.launchIn(viewModelScope)
    }

    fun unlockNetP() {
        netpWaitlistRepository.unlock()
        viewState.value = currentViewState().copy(
            networkProtectionWaitlistState = netpWaitlistRepository.getState(appBuildConfig.isInternalBuild()),
        )
    }

    fun viewState(): StateFlow<ViewState> {
        return viewState
    }

    fun commands(): Flow<Command> {
        return command.receiveAsFlow()
    }

    fun userRequestedToSendFeedback() {
        viewModelScope.launch { command.send(Command.LaunchFeedback) }
    }

    fun userRequestedToChangeIcon() {
        viewModelScope.launch { command.send(Command.LaunchAppIcon) }
    }

    fun userRequestedToAddHomeScreenWidget() {
        viewModelScope.launch { command.send(Command.LaunchAddHomeScreenWidget) }
    }

    fun userRequestedToChangeFireAnimation() {
        viewModelScope.launch { command.send(Command.LaunchFireAnimationSettings(viewState.value.selectedFireAnimation)) }
        pixel.fire(FIRE_ANIMATION_SETTINGS_OPENED)
    }

    fun onAccessibilitySettingClicked() {
        viewModelScope.launch { command.send(Command.LaunchAccessibilitySettings) }
    }

    fun userRequestedToChangeTheme() {
        viewModelScope.launch { command.send(Command.LaunchThemeSettings(viewState.value.theme)) }
        pixel.fire(SETTINGS_THEME_OPENED)
    }

    fun userRequestedToChangeNotificationsSetting() {
        viewModelScope.launch { command.send(Command.LaunchNotificationsSettings) }
        pixel.fire(SETTINGS_NOTIFICATIONS_PRESSED)
    }

    fun userRequestedToChangeAppLinkSetting() {
        viewModelScope.launch { command.send(Command.LaunchAppLinkSettings(viewState.value.appLinksSettingType)) }
        pixel.fire(SETTINGS_APP_LINKS_PRESSED)
    }

    fun onFireproofWebsitesClicked() {
        viewModelScope.launch { command.send(Command.LaunchFireproofWebsites) }
    }

    fun onAutofillSettingsClick() {
        viewModelScope.launch { command.send(Command.LaunchAutofillSettings) }
        pixel.fire(SETTINGS_AUTOFILL_MANAGEMENT_OPENED)
    }

    fun onSitePermissionsClicked() {
        viewModelScope.launch { command.send(Command.LaunchLocation) }
    }

    fun onAutomaticallyClearWhatClicked() {
        viewModelScope.launch { command.send(Command.ShowClearWhatDialog(viewState.value.automaticallyClearData.clearWhatOption)) }
    }

    fun onAutomaticallyClearWhenClicked() {
        viewModelScope.launch { command.send(Command.ShowClearWhenDialog(viewState.value.automaticallyClearData.clearWhenOption)) }
    }

    fun onGlobalPrivacyControlClicked() {
        viewModelScope.launch { command.send(Command.LaunchGlobalPrivacyControl) }
    }

    fun onAutoconsentClicked() {
        viewModelScope.launch { command.send(Command.LaunchAutoconsent) }
    }

    fun onEmailProtectionSettingClicked() {
        viewModelScope.launch {
            val com = if (emailManager.isEmailFeatureSupported()) {
                Command.LaunchEmailProtection(EMAIL_PROTECTION_URL)
            } else {
                Command.LaunchEmailProtectionNotSUpported
            }
            command.send(com)
        }
    }

    fun onMacOsSettingClicked() {
        viewModelScope.launch { command.send(Command.LaunchMacOs) }
    }

    fun windowsSettingClicked() {
        viewModelScope.launch { command.send(Command.LaunchWindows) }
    }

    fun onDefaultBrowserToggled(enabled: Boolean) {
        Timber.i("User toggled default browser, is now enabled: $enabled")
        val defaultBrowserSelected = defaultWebBrowserCapability.isDefaultBrowser()
        if (enabled && defaultBrowserSelected) return
        viewModelScope.launch {
            viewState.emit(currentViewState().copy(isAppDefaultBrowser = enabled))
            command.send(Command.LaunchDefaultBrowser)
        }
    }

    fun onAppTPSettingClicked() {
        if (appTrackingProtection.isOnboarded()) {
            viewModelScope.launch { command.send(Command.LaunchAppTPTrackersScreen) }
        } else {
            viewModelScope.launch { command.send(Command.LaunchAppTPOnboarding) }
        }
    }

    fun onNetPSettingClicked() {
        if (netpWaitlistRepository.getState(appBuildConfig.isInternalBuild()) == NetPWaitlistState.InBeta) {
            viewModelScope.launch { command.send(Command.LaunchNetPManagementScreen) }
        } else {
            viewModelScope.launch { command.send(Command.LaunchNetPWaitlist) }
        }
    }

    fun onAutocompleteSettingChanged(enabled: Boolean) {
        Timber.i("User changed autocomplete setting, is now enabled: $enabled")
        settingsDataStore.autoCompleteSuggestionsEnabled = enabled
        viewModelScope.launch { viewState.emit(currentViewState().copy(autoCompleteSuggestionsEnabled = enabled)) }
    }

    fun onAppLinksSettingChanged(appLinkSettingType: AppLinkSettingType) {
        Timber.i("User changed app links setting, is now: ${appLinkSettingType.name}")

        val pixelName =
            when (appLinkSettingType) {
                AppLinkSettingType.ASK_EVERYTIME -> {
                    settingsDataStore.appLinksEnabled = true
                    settingsDataStore.showAppLinksPrompt = true
                    SETTINGS_APP_LINKS_ASK_EVERY_TIME_SELECTED
                }
                AppLinkSettingType.ALWAYS -> {
                    settingsDataStore.appLinksEnabled = true
                    settingsDataStore.showAppLinksPrompt = false
                    SETTINGS_APP_LINKS_ALWAYS_SELECTED
                }
                AppLinkSettingType.NEVER -> {
                    settingsDataStore.appLinksEnabled = false
                    settingsDataStore.showAppLinksPrompt = false
                    SETTINGS_APP_LINKS_NEVER_SELECTED
                }
            }
        viewModelScope.launch { viewState.emit(currentViewState().copy(appLinksSettingType = appLinkSettingType)) }

        pixel.fire(pixelName)
    }

    private fun getAppLinksSettingsState(
        appLinksEnabled: Boolean,
        showAppLinksPrompt: Boolean,
    ): AppLinkSettingType {
        return if (appLinksEnabled) {
            if (showAppLinksPrompt) {
                AppLinkSettingType.ASK_EVERYTIME
            } else {
                AppLinkSettingType.ALWAYS
            }
        } else {
            AppLinkSettingType.NEVER
        }
    }

    private fun obtainVersion(variantKey: String): String {
        val formattedVariantKey = if (variantKey.isBlank()) " " else " $variantKey "
        return "${appBuildConfig.versionName}$formattedVariantKey(${appBuildConfig.versionCode})"
    }

    fun onAutomaticallyWhatOptionSelected(clearWhatNewSetting: ClearWhatOption) {
        if (settingsDataStore.isCurrentlySelected(clearWhatNewSetting)) {
            Timber.v("User selected same thing they already have set: $clearWhatNewSetting; no need to do anything else")
            return
        }

        pixel.fire(clearWhatNewSetting.pixelEvent())

        settingsDataStore.automaticallyClearWhatOption = clearWhatNewSetting

        viewModelScope.launch {
            viewState.emit(
                currentViewState().copy(
                    automaticallyClearData = AutomaticallyClearData(
                        clearWhatOption = clearWhatNewSetting,
                        clearWhenOption = settingsDataStore.automaticallyClearWhenOption,
                        clearWhenOptionEnabled = isAutomaticallyClearingDataWhenSettingEnabled(clearWhatNewSetting),
                    ),
                ),
            )
        }
    }

    private fun isAutomaticallyClearingDataWhenSettingEnabled(clearWhatOption: ClearWhatOption?): Boolean {
        return clearWhatOption != null && clearWhatOption != ClearWhatOption.CLEAR_NONE
    }

    fun onAutomaticallyWhenOptionSelected(clearWhenNewSetting: ClearWhenOption) {
        if (settingsDataStore.isCurrentlySelected(clearWhenNewSetting)) {
            Timber.v("User selected same thing they already have set: $clearWhenNewSetting; no need to do anything else")
            return
        }

        clearWhenNewSetting.pixelEvent()?.let {
            pixel.fire(it)
        }

        settingsDataStore.automaticallyClearWhenOption = clearWhenNewSetting
        viewModelScope.launch {
            viewState.emit(
                currentViewState().copy(
                    automaticallyClearData = AutomaticallyClearData(
                        settingsDataStore.automaticallyClearWhatOption,
                        clearWhenNewSetting,
                    ),
                ),
            )
        }
    }

    private fun windowsSettingState(): WindowsWaitlistState? {
        if (!windowsFeature.self().isEnabled()) return null
        return windowsWaitlist.getWaitlistState()
    }

    fun onThemeSelected(selectedTheme: DuckDuckGoTheme) {
        Timber.d("User toggled theme, theme to set: $selectedTheme")
        if (themingDataStore.isCurrentlySelected(selectedTheme)) {
            Timber.d("User selected same theme they've already set: $selectedTheme; no need to do anything else")
            return
        }
        themingDataStore.theme = selectedTheme
        viewModelScope.launch {
            viewState.emit(currentViewState().copy(theme = selectedTheme))
            command.send(Command.UpdateTheme)
        }

        val pixelName =
            when (selectedTheme) {
                DuckDuckGoTheme.LIGHT -> SETTINGS_THEME_TOGGLED_LIGHT
                DuckDuckGoTheme.DARK -> SETTINGS_THEME_TOGGLED_DARK
                DuckDuckGoTheme.SYSTEM_DEFAULT -> SETTINGS_THEME_TOGGLED_SYSTEM_DEFAULT
            }
        pixel.fire(pixelName)
    }

    fun onFireAnimationSelected(selectedFireAnimation: FireAnimation) {
        if (settingsDataStore.isCurrentlySelected(selectedFireAnimation)) {
            Timber.v("User selected same thing they already have set: $selectedFireAnimation; no need to do anything else")
            return
        }
        settingsDataStore.selectedFireAnimation = selectedFireAnimation
        fireAnimationLoader.preloadSelectedAnimation()
        viewModelScope.launch {
            viewState.emit(currentViewState().copy(selectedFireAnimation = selectedFireAnimation))
        }
        pixel.fire(FIRE_ANIMATION_NEW_SELECTED, mapOf(FIRE_ANIMATION to selectedFireAnimation.getPixelValue()))
    }

    fun onManageWhitelistSelected() {
        pixel.fire(SETTINGS_MANAGE_WHITELIST)
        viewModelScope.launch { command.send(Command.LaunchWhitelist) }
    }

    private fun currentViewState(): ViewState {
        return viewState.value
    }

    private fun ClearWhatOption.pixelEvent(): PixelName {
        return when (this) {
            ClearWhatOption.CLEAR_NONE -> AUTOMATIC_CLEAR_DATA_WHAT_OPTION_NONE
            ClearWhatOption.CLEAR_TABS_ONLY -> AUTOMATIC_CLEAR_DATA_WHAT_OPTION_TABS
            ClearWhatOption.CLEAR_TABS_AND_DATA -> AUTOMATIC_CLEAR_DATA_WHAT_OPTION_TABS_AND_DATA
        }
    }

    private fun ClearWhenOption.pixelEvent(): PixelName? {
        return when (this) {
            ClearWhenOption.APP_EXIT_ONLY -> AUTOMATIC_CLEAR_DATA_WHEN_OPTION_APP_EXIT_ONLY
            ClearWhenOption.APP_EXIT_OR_5_MINS -> AUTOMATIC_CLEAR_DATA_WHEN_OPTION_APP_EXIT_OR_5_MINS
            ClearWhenOption.APP_EXIT_OR_15_MINS -> AUTOMATIC_CLEAR_DATA_WHEN_OPTION_APP_EXIT_OR_15_MINS
            ClearWhenOption.APP_EXIT_OR_30_MINS -> AUTOMATIC_CLEAR_DATA_WHEN_OPTION_APP_EXIT_OR_30_MINS
            ClearWhenOption.APP_EXIT_OR_60_MINS -> AUTOMATIC_CLEAR_DATA_WHEN_OPTION_APP_EXIT_OR_60_MINS
            else -> null
        }
    }

    private fun getNotificationsSettingSubtitleId(notificationsEnabled: Boolean): Int {
        return if (notificationsEnabled) {
            R.string.settingsSubtitleNotificationsEnabled
        } else {
            R.string.settingsSubtitleNotificationsDisabled
        }
    }

    fun onSyncSettingClicked() {
        viewModelScope.launch { command.send(Command.LaunchSyncSettings) }
    }

    fun onLaunchedFromNotification(pixelName: String) {
        pixel.fire(pixelName)
    }

    companion object {
        const val EMAIL_PROTECTION_URL = "https://duckduckgo.com/email"
    }
}
