/*
 * Copyright (c) 2023 DuckDuckGo
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

package com.duckduckgo.app.installer

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.lifecycle.LifecycleOwner
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.app.lifecycle.MainProcessLifecycleObserver
import com.duckduckgo.app.pixels.AppPixelName.APP_INSTALLER_PACKAGE_NAME
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesMultibinding
import dagger.SingleInstanceIn
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

@ContributesMultibinding(
    scope = AppScope::class,
    boundType = MainProcessLifecycleObserver::class,
)
@SingleInstanceIn(AppScope::class)
class InstallSourceLifecycleObserver @Inject constructor(
    private val installSourceExtractor: InstallSourceExtractor,
    private val context: Context,
    private val pixel: Pixel,
    private val dispatchers: DispatcherProvider,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) : MainProcessLifecycleObserver {

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)

        appCoroutineScope.launch(dispatchers.io()) {
            if (!hasAlreadyProcessed()) {
                val installationSource = installSourceExtractor.extract()
                Timber.i("Installation source extracted: $installationSource")

                val params = mapOf(PIXEL_PARAMETER_INSTALLER to installationSource.toString())
                pixel.fire(APP_INSTALLER_PACKAGE_NAME, params)

                recordInstallSourceProcessed()
            } else {
                Timber.v("Already processed")
            }
        }
    }

    @VisibleForTesting
    fun recordInstallSourceProcessed() {
        sharedPreferences().edit {
            putBoolean(SHARED_PREFERENCES_PROCESSED_KEY, true)
        }
    }

    private fun hasAlreadyProcessed(): Boolean {
        return sharedPreferences().getBoolean(SHARED_PREFERENCES_PROCESSED_KEY, false)
    }

    private fun sharedPreferences(): SharedPreferences {
        return context.getSharedPreferences(SHARED_PREFERENCES_FILENAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val SHARED_PREFERENCES_FILENAME = "com.duckduckgo.app.installer.InstallSource"
        private const val SHARED_PREFERENCES_PROCESSED_KEY = "processed"
        private const val PIXEL_PARAMETER_INSTALLER = "installer"
    }
}
