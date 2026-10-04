package org.example.memosm.ui.component.setting

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.example.memosm.R
import org.example.memosm.data.DataStoreManager
import org.example.memosm.model.AppFont
import org.example.memosm.model.AppearancePreferences
import org.example.memosm.model.ColorTheme
import org.example.memosm.model.Memo
import org.example.memosm.model.ThemeMode
import org.example.memosm.ui.component.item.MemoItem
import org.example.memosm.ui.theme.MemosMTheme
import org.example.memosm.ui.theme.appearanceColorScheme
import org.example.memosm.ui.theme.fontFamily
import org.example.memosm.ui.theme.systemColorScheme
import org.koin.core.context.GlobalContext
import kotlin.math.abs
import kotlin.time.Clock

@Composable
internal fun AppearanceSettingsContent(
    headerScale: Float,
    modifier: Modifier = Modifier,
    dataStore: DataStoreManager = remember { GlobalContext.get().get() },
    otherPreferences: @Composable () -> Unit
) {
    val appearance by dataStore.appearance.collectAsStateWithLifecycle(initialValue = AppearancePreferences())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val saveError = stringResource(R.string.appearance_save_error)
    val isDark = appearance.themeMode.isDark(isSystemInDarkTheme())
    val wallpaperColors = systemColorScheme(isDark)
    val lightWallpaperColors = systemColorScheme(isDark = false)
    val wallpaperHue = remember(lightWallpaperColors.primary) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(lightWallpaperColors.primary.toArgb(), it) }[0]
    }
    var dialog by rememberSaveable { mutableStateOf<AppearanceDialog?>(null) }

    fun save(action: suspend () -> Unit) {
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, saveError, Toast.LENGTH_SHORT).show() }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MemoAppearancePreview(headerScale = headerScale)
            SettingsSurface {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(when (appearance.colorTheme) {
                            ColorTheme.SYSTEM -> R.string.appearance_device_colors
                            ColorTheme.MONOCHROME -> R.string.appearance_monochrome
                            ColorTheme.MEMOS -> R.string.appearance_memos
                            ColorTheme.CUSTOM -> R.string.appearance_custom_colors
                        }),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { dialog = AppearanceDialog.COLOR }) {
                        Icon(Icons.Outlined.Palette, contentDescription = stringResource(R.string.appearance_choose_color))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                        .horizontalScroll(rememberScrollState()).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PaletteSwatch(
                        colors = wallpaperColors,
                        label = stringResource(R.string.appearance_device_colors),
                        selected = appearance.colorTheme == ColorTheme.SYSTEM,
                        onClick = { save { dataStore.saveColorTheme(ColorTheme.SYSTEM) } }
                    )
                    val monochrome = remember(wallpaperColors, isDark) {
                        appearanceColorScheme(appearance.copy(colorTheme = ColorTheme.MONOCHROME), wallpaperColors, isDark)
                    }
                    PaletteSwatch(
                        colors = monochrome,
                        label = stringResource(R.string.appearance_monochrome),
                        selected = appearance.colorTheme == ColorTheme.MONOCHROME,
                        onClick = { save { dataStore.saveColorTheme(ColorTheme.MONOCHROME) } }
                    )
                    val memosColors = remember(isDark) {
                        appearanceColorScheme(appearance.copy(colorTheme = ColorTheme.MEMOS), wallpaperColors, isDark)
                    }
                    PaletteSwatch(
                        colors = memosColors,
                        label = stringResource(R.string.appearance_memos),
                        selected = appearance.colorTheme == ColorTheme.MEMOS,
                        onClick = { save { dataStore.saveColorTheme(ColorTheme.MEMOS) } }
                    )
                    repeat(5) { index ->
                        val hue = (wallpaperHue + index * 60f) % 360f
                        val colors = remember(hue, isDark, wallpaperColors) {
                            appearanceColorScheme(appearance.copy(colorTheme = ColorTheme.CUSTOM, customHue = hue), wallpaperColors, isDark)
                        }
                        PaletteSwatch(
                            colors = colors,
                            label = stringResource(R.string.appearance_color_option, index + 1),
                            selected = appearance.colorTheme == ColorTheme.CUSTOM && appearance.customHue?.let { abs(it - hue) < 0.01f } == true,
                            onClick = { save { dataStore.saveColorTheme(ColorTheme.CUSTOM, hue) } }
                        )
                    }
                }
            }
            SettingsGroup {
                SettingsNavigationRow(
                    title = stringResource(R.string.appearance_theme_mode),
                    summary = stringResource(appearance.themeMode.labelRes),
                    onClick = { dialog = AppearanceDialog.MODE }
                )
                SettingsNavigationRow(
                    title = stringResource(R.string.appearance_font),
                    summary = stringResource(appearance.font.labelRes),
                    onClick = { dialog = AppearanceDialog.FONT }
                )
                otherPreferences()
            }
        }
    }
    when (dialog) {
        AppearanceDialog.MODE -> {
            var selected by rememberSaveable { mutableStateOf(appearance.themeMode) }
            AppearancePreferenceDialog(
                title = stringResource(R.string.appearance_theme_mode),
                onDismiss = { dialog = null },
                onSave = { dataStore.saveThemeMode(selected) }
            ) {
                Column(Modifier.selectableGroup()) {
                    listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM).forEach { mode ->
                        ChoiceRow(selected = selected == mode, onClick = { selected = mode }) {
                            Text(stringResource(mode.labelRes))
                        }
                    }
                }
            }
        }
        AppearanceDialog.FONT -> {
            var selected by rememberSaveable { mutableStateOf(appearance.font) }
            AppearancePreferenceDialog(
                title = stringResource(R.string.appearance_font),
                onDismiss = { dialog = null },
                onSave = { dataStore.saveFont(selected) }
            ) {
                MemosMTheme(appearance = appearance.copy(font = selected)) {
                    MemoAppearancePreview(headerScale, compact = true)
                }
                Column(Modifier.selectableGroup()) {
                    AppFont.entries.forEach { font ->
                        ChoiceRow(selected = selected == font, onClick = { selected = font }) {
                            Text(stringResource(font.labelRes), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = font.fontFamily))
                        }
                    }
                }
            }
        }
        AppearanceDialog.COLOR -> {
            var selected by rememberSaveable { mutableStateOf(appearance.colorTheme) }
            var hue by rememberSaveable { mutableStateOf(appearance.customHue ?: wallpaperHue) }
            AppearancePreferenceDialog(
                title = stringResource(R.string.appearance_choose_color),
                onDismiss = { dialog = null },
                onSave = { dataStore.saveColorTheme(selected, hue.takeIf { selected == ColorTheme.CUSTOM }) }
            ) {
                MemosMTheme(appearance = appearance.copy(colorTheme = selected, customHue = hue)) {
                    MemoAppearancePreview(headerScale, compact = true)
                }
                Column(Modifier.selectableGroup()) {
                    ColorTheme.entries.forEach { theme ->
                        ChoiceRow(selected = selected == theme, onClick = { selected = theme }) {
                            Text(stringResource(theme.labelRes))
                        }
                    }
                }
                if (selected == ColorTheme.CUSTOM) {
                    Text(stringResource(R.string.appearance_color_hint))
                    val sliderState = remember { SliderState(value = hue, trackRange = 0f..360f) }
                    LaunchedEffect(hue) { sliderState.value = hue }
                    Slider(state = sliderState, onValueChange = { hue = it })
                }
            }
        }
        null -> Unit
    }
}

@Composable
private fun MemoAppearancePreview(headerScale: Float, compact: Boolean = false) {
    val content = stringResource(R.string.appearance_preview_memo)
    val memo = remember(content) { Memo(content = content, displayTime = Clock.System.now(), pinned = true) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact) {
            Text(
                stringResource(R.string.appearance_preview),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
        MemoItem(
            memo = memo,
            token = "",
            headerScale = headerScale,
            maxHeight = if (compact) 150.dp else androidx.compose.ui.unit.Dp.Unspecified,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        )
    }
}

@Composable
private fun PaletteSwatch(
    colors: ColorScheme,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }.padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(36.dp).clip(CircleShape)) {
            drawRect(colors.primary)
            drawRect(colors.secondaryContainer, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height / 2))
            drawRect(colors.tertiaryContainer, topLeft = Offset(size.width / 2, size.height / 2), size = Size(size.width / 2, size.height / 2))
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Box(Modifier.weight(1f)) { label() }
    }
}

@Composable
private fun AppearancePreferenceDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: suspend () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
                if (failed) Text(stringResource(R.string.appearance_save_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    try { onSave(); onDismiss() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { saving = false }
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

private enum class AppearanceDialog { MODE, FONT, COLOR }

private val ColorTheme.labelRes: Int
    get() = when (this) {
        ColorTheme.SYSTEM -> R.string.appearance_device_colors
        ColorTheme.MONOCHROME -> R.string.appearance_monochrome
        ColorTheme.MEMOS -> R.string.appearance_memos
        ColorTheme.CUSTOM -> R.string.appearance_custom
    }

private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.appearance_mode_system
        ThemeMode.LIGHT -> R.string.appearance_mode_light
        ThemeMode.DARK -> R.string.appearance_mode_dark
    }

private val AppFont.labelRes: Int
    get() = when (this) {
        AppFont.SYSTEM -> R.string.appearance_font_system
        AppFont.SANS_SERIF -> R.string.appearance_font_sans
        AppFont.SERIF -> R.string.appearance_font_serif
        AppFont.MONOSPACE -> R.string.appearance_font_mono
    }
