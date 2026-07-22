package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.ui.res.stringResource
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.actions.translate.TRANSLATE_ADDON_ENABLED
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem

val AddonsMenu = UserSettingsMenu(
    title = R.string.addons_settings_title,
    navPath = "addons", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly {
            ScreenTitle(stringResource(R.string.addons_settings_title))
        },
        userSettingNavigationItem(
            title = R.string.translate_addon_title,
            subtitle = R.string.translate_addon_subtitle,
            style = NavigationItemStyle.HomePrimary,
            navigateTo = "addons/translate",
            icon = R.drawable.ic_translate
        )
    )
)
