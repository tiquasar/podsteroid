package com.tiquasar.podsteroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens

/**
 * Expressive section heading: a short rounded accent bar followed by a
 * medium-weight, title-case label. Replaces the old tiny uppercase tracked text.
 */
@Composable
fun PodsteroidSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(top = PodsteroidTokens.Spacing.XL2, bottom = PodsteroidTokens.Spacing.SM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(PodsteroidTokens.Spacing.SM))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = PodsteroidTokens.ui(),
            fontWeight = FontWeight.SemiBold,
        )
    }
}
