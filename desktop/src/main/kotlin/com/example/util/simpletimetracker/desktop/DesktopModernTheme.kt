package com.example.util.simpletimetracker.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Card
import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Text
import androidx.compose.material.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape

internal object DesktopUiTokens {
    val Primary = Color(0xFF37474F)
    val PrimaryDark = Color(0xFF263238)
    val Accent = Color(0xFFFF4081)
    val Background = Color(0xFFF5F7F7)
    val Surface = Color(0xFFFFFFFF)
    val Text = Color(0xFF191919)
    val SecondaryText = Color(0xFF737373)
    val Divider = Color(0xFFE7ECEE)
    val Active = Color(0xFF37474F)
    val Running = Color(0xFFE53935)
    val RunningStripe = Color(0xFFC62828)
    val Tag = Color(0xFFE7EEF0)
    val TagText = Color(0xFF37474F)
    val Sidebar = Color(0xFF263238)
    val SidebarSelected = Color(0xFF455A64)
    val Light = Color(0xFFF8FAFA)
    val ScreenPadding = 28.dp
    val SectionGap = 20.dp
    val CardRadius = 16.dp
    val ControlHeight = 46.dp
}

private val DesktopColors = Colors(
    primary = DesktopUiTokens.Primary,
    primaryVariant = DesktopUiTokens.PrimaryDark,
    secondary = DesktopUiTokens.Accent,
    secondaryVariant = DesktopUiTokens.Accent,
    background = DesktopUiTokens.Background,
    surface = DesktopUiTokens.Surface,
    error = DesktopUiTokens.Running,
    onPrimary = DesktopUiTokens.Light,
    onSecondary = Color.White,
    onBackground = DesktopUiTokens.Text,
    onSurface = DesktopUiTokens.Text,
    onError = Color.White,
    isLight = true,
)

private val DesktopTypography = Typography(
    defaultFontFamily = FontFamily.SansSerif,
    h4 = androidx.compose.ui.text.TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    h5 = androidx.compose.ui.text.TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    h6 = androidx.compose.ui.text.TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    subtitle1 = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    body1 = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
    body2 = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
    caption = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
    button = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
internal fun SimpleTimeTrackerDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = DesktopColors,
        typography = DesktopTypography,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(DesktopUiTokens.CardRadius),
            large = RoundedCornerShape(22.dp),
        ),
        content = content,
    )
}

@Composable
internal fun DesktopPageHeader(
    title: String,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.h4)
            subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.body2, color = DesktopUiTokens.SecondaryText)
            }
        }
        actions()
    }
}

@Composable
internal fun DesktopSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier = modifier, style = MaterialTheme.typography.h6)
}

@Composable
internal fun DesktopTagChip(text: String, muted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        color = if (muted) DesktopUiTokens.SecondaryText else DesktopUiTokens.TagText,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (muted) DesktopUiTokens.Divider else DesktopUiTokens.Tag)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
internal fun DesktopMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.caption, color = DesktopUiTokens.SecondaryText)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.h5)
    }
}

@Composable
internal fun DesktopDialogSurface(
    title: String,
    onDismiss: () -> Unit,
    wide: Boolean = false,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .widthIn(min = if (wide) 620.dp else 440.dp, max = if (wide) 920.dp else 620.dp)
                .heightIn(max = 760.dp),
            elevation = 14.dp,
            shape = RoundedCornerShape(22.dp),
        ) {
            Column(
                modifier = Modifier.padding(28.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(title, style = MaterialTheme.typography.h5)
                content()
            }
        }
    }
}

@Composable
internal fun DesktopDialogActions(
    onCancel: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material.TextButton(onClick = onCancel) { Text("Отмена") }
        Spacer(Modifier.width(10.dp))
        androidx.compose.material.Button(onClick = onConfirm, enabled = enabled) { Text(confirmLabel) }
    }
}
