package com.portionspot.pos.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Shared building blocks for the POS design language (see PARITY_AUDIT §4). The rules
 * they encode once, so screens can't drift: contained cards, dense fields, a small muted
 * label above a bold value, one brand accent, and a form container that scales from a
 * phone sheet to a centred modal on a tablet/till. Built on [LocalPosTokens] so every
 * colour follows the active theme.
 */

/**
 * Scales a surface down while it is held. `Modifier.clickable` already draws a
 * Material ripple, which is enough on a small control — but on a large surface
 * (a product card, a cart row) the ripple is diffuse and easy to miss mid-shift,
 * and the tile ends up feeling dead. A 3% scale is felt rather than seen.
 *
 * Runs inside [graphicsLayer] deliberately: the lambda re-reads `scale` on the
 * layer pass, so a press does NOT recompose or re-measure the subtree. Doing the
 * same thing with `Modifier.scale(s)` would recompose the card on every frame of
 * the press — on a 2-column grid of 40 products that is the difference between
 * free and janky.
 *
 * Pass the SAME [interactionSource] you gave to `clickable`, or the scale will
 * never fire.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressed: Float = defaultPressScale(),
): Modifier {
    if (LocalReduceMotion.current) return this
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = tween(PosMotion.Press, easing = PosMotion.EnterEasing),
        label = "pressScale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Press depth scaled to the screen. A 3% squeeze is a *proportional* change, so on the
 * Sunmi's 116dp card it moves ~3.5dp of edge — below the threshold where a thumb
 * registers it, especially on that low-DPI panel. Compact goes to 6%; roomier screens
 * keep the subtler 3% because the same percentage travels further on a bigger card.
 */
@Composable
private fun defaultPressScale(): Float =
    if (LocalPosDimens.current.widthClass == PosWidthClass.Compact) 0.94f else 0.97f

/** Small uppercase section label — muted, for grouping. */
@Composable
fun PosSectionLabel(text: String, modifier: Modifier = Modifier) {
    val t = LocalPosTokens.current
    Text(
        text.uppercase(),
        modifier = modifier,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = t.inkTertiary,
    )
}

/**
 * Compact labelled input: an 11sp muted label above a bordered value box. Denser than a
 * Material `OutlinedTextField` (no 56dp floating-label height) and gives the label→value
 * hierarchy the web form has. Place two-up in a Row with `Modifier.weight(1f)`.
 */
@Composable
fun PosField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = LocalPosTokens.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = t.inkTertiary, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(t.surface2)
                .border(
                    width = if (focused) 1.5.dp else 1.dp,
                    color = if (focused) t.brand.s500 else t.surfaceBorder,
                    shape = RoundedCornerShape(9.dp),
                )
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, color = t.inkTertiary.copy(alpha = 0.55f), fontSize = 14.sp, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    textStyle = TextStyle(color = t.inkPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(t.brand.s600),
                    visualTransformation = visualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

/** A white card that groups related fields, with even internal spacing. */
@Composable
fun PosFormCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalPosTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface1)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * Contained form container that replaces a raw `AlertDialog` for heavy forms: a titled,
 * scrollable card with a sticky header and footer. Responsive — near-full-screen on a
 * phone, a centred 560dp modal on a wide screen (tablet/till). The primary action is the
 * one brand-accent control.
 */
@Composable
fun PosContainedForm(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    dismissLabel: String = "Cancel",
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalPosTokens.current
    val cfg = LocalConfiguration.current
    val wide = cfg.screenWidthDp >= 600
    // Wrap content, but cap the scroll area so a 1-field form stays compact while a long
    // form (the item editor) caps and scrolls with the header + footer pinned.
    val maxBody = (cfg.screenHeightDp * 0.68f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier
                .then(if (wide) Modifier.width(560.dp) else Modifier.fillMaxWidth(0.96f))
                .clip(RoundedCornerShape(22.dp))
                .background(t.canvas)
                .border(1.dp, t.surfaceBorder, RoundedCornerShape(22.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = t.inkPrimary, maxLines = 1, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = t.inkSecondary)
                }
            }
            HorizontalDivider(color = t.surfaceBorder)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBody)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            HorizontalDivider(color = t.surfaceBorder)
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(dismissLabel) }
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = t.brand.s600, contentColor = t.inkOnBrand,
                    ),
                ) { Text(confirmLabel) }
            }
        }
    }
}

/**
 * Contained dialog for detail / info / picker views: a titled card with a close button
 * and a scrollable body, but NO Save/Cancel footer (actions live in the content). The
 * dialog wraps its content and caps the scroll area, so a short dialog stays short.
 * Responsive width, same chrome as [PosContainedForm].
 */
@Composable
fun PosDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalPosTokens.current
    val cfg = LocalConfiguration.current
    val wide = cfg.screenWidthDp >= 600
    val maxBody = (cfg.screenHeightDp * 0.72f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier
                .then(if (wide) Modifier.width(520.dp) else Modifier.fillMaxWidth(0.94f))
                .clip(RoundedCornerShape(22.dp))
                .background(t.canvas)
                .border(1.dp, t.surfaceBorder, RoundedCornerShape(22.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = t.inkPrimary, maxLines = 1, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = t.inkSecondary)
                }
            }
            HorizontalDivider(color = t.surfaceBorder)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBody)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** Summary tile: muted uppercase label over a big value, with optional sub-line and an
 *  accent colour for the value. Flat bordered card. Use in grids of 2–4. */
@Composable
fun PosMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    sub: String? = null,
) {
    val t = LocalPosTokens.current
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(t.surface1)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp, color = t.inkTertiary, maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Black, color = accent ?: t.inkPrimary, maxLines = 1)
        if (sub != null) {
            Spacer(Modifier.height(1.dp))
            Text(sub, fontSize = 10.sp, color = t.inkTertiary, maxLines = 1)
        }
    }
}

/**
 * Segmented control — a pill group where one option is filled with the brand accent.
 * [options] are (key, label) pairs; the selected [selected] key fills. Two accents max
 * on a screen, so use this for the primary view switch, not everywhere.
 */
@Composable
fun PosSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val t = LocalPosTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(t.surface2)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (key, label) ->
            val sel = key == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sel) t.brand.s600 else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    color = if (sel) t.inkOnBrand else t.inkSecondary, maxLines = 1,
                )
            }
        }
    }
}
