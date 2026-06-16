/*
 * DataStore<Preferences> extension property. v0.1.1: top-level val, single declaration.
 *
 * The classic DataStore pitfall: calling `preferencesDataStore("settings")` twice
 * (once in the repository, once in the Hilt module) creates two DataStore instances
 * that silently diverge — one writes succeed and the other returns stale data.
 * The fix is to declare the property exactly once at top level and reference that
 * single instance from every consumer.
 *
 * Visibility: public (not `private`). AppModule in :app needs to call
 * `context.settingsDataStore` to @Provides the DataStore<Preferences>. If the
 * extension were `private` to this file, the Hilt module in :app could not
 * resolve it. The single-declaration property is what prevents the duplication
 * bug, not the visibility modifier.
 *
 * On-disk path: `<app filesDir>/datastore/settings.preferences_pb`.
 */
package au.com.ausroads.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)
