package app.smallthingz.timetravel

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AppTopBar(
    selectionActive: Boolean,
    onBrandClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    if (selectionActive) return

    val brandInteraction = remember { MutableInteractionSource() }
    val brandPressed by brandInteraction.collectIsPressedAsState()
    val brandScale by animateFloatAsState(
        targetValue = if (brandPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "brandPress",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = brandScale
                    scaleY = brandScale
                }
                .clickable(
                    interactionSource = brandInteraction,
                    indication = null,
                    onClick = onBrandClick,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_brand_mark),
                    contentDescription = stringResource(R.string.app_name),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.brand_time),
                fontSize = 25.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.045).sp,
                lineHeight = 33.sp,
                color = MaterialTheme.colorScheme.tertiary,
                fontFamily = FontFamily.SansSerif,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
            Text(
                text = stringResource(R.string.brand_travel),
                fontSize = 25.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.045).sp,
                lineHeight = 33.sp,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.SansSerif,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onSettingsClick,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.open_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
