package dev.nixi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nixi.NixiState
import dev.nixi.ui.theme.NixiErr
import dev.nixi.ui.theme.NixiGlass
import dev.nixi.ui.theme.NixiGlassDeep
import dev.nixi.ui.theme.NixiGlassDeepSoft
import dev.nixi.ui.theme.NixiGlassEdge
import dev.nixi.ui.theme.NixiGlassEdgeStrong
import dev.nixi.ui.theme.NixiGlassSoft
import dev.nixi.ui.theme.NixiOk
import dev.nixi.ui.theme.NixiPurple
import dev.nixi.ui.theme.NixiPurpleDeep
import dev.nixi.ui.theme.NixiText
import dev.nixi.ui.theme.NixiTextDim
import dev.nixi.ui.theme.NixiWarn

/**
 * SZKŁO — jeden wspólny wygląd dla całej aplikacji.
 *
 * Wszystkie panele (pigułka NIXI, pasek nawigacji, karty ustawień) rysujemy
 * w ten sam sposób: ciemne, półprzezroczyste tło z delikatnym gradientem
 * (jaśniejsze u góry, jak odbicie światła), 1 dp jasnej krawędzi i miękki
 * cień. Dzięki temu nic nie jest „przyciemniane" — tło pod spodem zostaje
 * widoczne, a treść jest czytelna nad dowolnym ekranem.
 */
object Glass {

    /** Tło panelu: góra jaśniejsza, dół ciemniejszy — daje wrażenie szkła. */
    fun fill(strong: Boolean = false): Brush = Brush.verticalGradient(
        listOf(
            if (strong) NixiGlass else NixiGlassSoft,
            if (strong) NixiGlassDeep else NixiGlassDeepSoft,
        )
    )

    /** Krawędź: jaśniejsza u góry (odbicie), ciemniejsza u dołu. */
    fun edge(strong: Boolean = false): Brush = Brush.verticalGradient(
        listOf(
            if (strong) NixiGlassEdgeStrong else NixiGlassEdge,
            if (strong) NixiGlassEdge else Color(0x14FFFFFF),
        )
    )
}

/**
 * Nakłada wygląd szkła na dowolny kontener.
 * [elevation] zostaw 0 dp dla pasków przyklejonych do krawędzi ekranu.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    elevation: Dp = 0.dp,
    edgeWidth: Dp = 1.dp,
): Modifier = this
    .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false) else Modifier)
    .clip(shape)
    .background(Glass.fill(strong), shape)
    .border(edgeWidth, Glass.edge(strong), shape)

/** Panel szklany (dowolna zawartość). */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    elevation: Dp = 0.dp,
    padding: PaddingValues = PaddingValues(14.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .glass(shape = shape, strong = strong, elevation = elevation)
            .padding(padding)
    ) { content() }
}

/**
 * KARTA SEKCJI — używana na ekranie głównym i w ustawieniach.
 * Tytuł + opcjonalny opis, pod spodem dowolna treść.
 */
@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    accent: Color = NixiPurple,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape = RoundedCornerShape(22.dp), strong = true, elevation = 0.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(10.dp))
            Text(title, color = NixiText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = NixiTextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * Nagłówek ekranu: tytuł + (opcjonalnie) status po prawej.
 * Trzyma równy górny odstęp na wszystkich ekranach.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = NixiText)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = NixiTextDim, fontSize = 12.sp)
            }
        }
        trailing()
    }
}

/**
 * PIGUŁKA ROZMOWY — szklany kontener o mocno zaokrąglonych rogach.
 * W niej mieści się całe wywołanie NIXI: kula, status i sterowanie.
 */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .glass(shape = shape, strong = true)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) { content() }
}

/** Mała pastylka informacyjna (chip) w stylu szkła. */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    accent: Color = NixiText,
) {
    Row(
        modifier = modifier
            .glass(shape = RoundedCornerShape(999.dp), strong = false)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = accent, fontSize = 11.sp, maxLines = 1)
    }
}

/**
 * WYBÓR KATEGORII — rząd pastylek, jedna aktywna (akcent NIXI).
 * Użyty w Ustawieniach do pogrupowania sekcji.
 */
@Composable
fun GlassTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (active) Brush.horizontalGradient(
                            listOf(NixiPurple, NixiPurpleDeep)
                        ) else Brush.verticalGradient(listOf(NixiGlassSoft, NixiGlassSoft))
                    )
                    .border(
                        1.dp,
                        if (active) Color(0x66FFFFFF) else NixiGlassEdge,
                        RoundedCornerShape(999.dp)
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (active) Color.White else NixiTextDim,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Przycisk okrągły w stylu szkła — używany w pigułce rozmowy i na ekranie głównym.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    label: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    accent: Color? = null,
    contentDescription: String? = null,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    if (accent != null) Brush.linearGradient(
                        listOf(accent, accent.copy(alpha = 0.72f))
                    ) else Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x1FFFFFFF)))
                )
                .border(1.dp, if (accent != null) Color(0x59FFFFFF) else NixiGlassEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription ?: label,
                tint = if (accent != null) Color.White else NixiText,
                modifier = Modifier.size(size * 0.44f),
            )
        }
        if (label != null) {
            Spacer(Modifier.height(4.dp))
            Text(label, color = NixiTextDim, fontSize = 10.sp)
        }
    }
}

/** Główny przycisk akcji (pełny kolor NIXI) — w stylu szkła/pastylki. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = NixiPurple,
    filled: Boolean = true,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (filled) Brush.horizontalGradient(
                    listOf(accent, NixiPurpleDeep)
                ) else Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x14FFFFFF)))
            )
            .border(1.dp, if (filled) Color(0x59FFFFFF) else NixiGlassEdge, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (filled) Color.White else NixiText, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = if (filled) Color.White else NixiText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Wiersz listy wewnątrz karty: etykieta + dowolny element po prawej. */
@Composable
fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = NixiText, fontSize = 13.sp)
            if (!hint.isNullOrBlank()) {
                Text(hint, color = NixiTextDim, fontSize = 11.sp)
            }
        }
        trailing()
    }
}

/** Delikatny separator wewnątrz karty (zamiast ciężkich linii Material). */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0x00FFFFFF), Color(0x1FFFFFFF), Color(0x00FFFFFF))
                )
            )
    )
}

/** Tekst i kolor statusu wynikający ze stanu kuli (jedno źródło prawdy). */
fun statusOf(state: NixiState.OrbState): Pair<String, Color> = when (state) {
    NixiState.OrbState.IDLE -> "gotowa" to NixiTextDim
    NixiState.OrbState.LISTENING -> "słucham…" to NixiOk
    NixiState.OrbState.THINKING -> "myślę…" to NixiPurple
    NixiState.OrbState.SPEAKING -> "odpowiadam" to NixiPurple
    NixiState.OrbState.MANUAL -> "tryb ręczny — steruję ekranem" to NixiPurple
    NixiState.OrbState.TPM_LIMIT -> "limit tokenów — pauza" to NixiWarn
    NixiState.OrbState.ERROR -> "błąd" to NixiErr
}

/** Wariant dla stanu kuli (okno rozmowy, ekran główny). */
@Composable
fun StatusPill(state: NixiState.OrbState, modifier: Modifier = Modifier) {
    val (text, color) = statusOf(state)
    StatusPill(text = text, color = color, modifier = modifier)
}

/** Pigułka statusu — używana w oknie rozmowy i na ekranie głównym. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x66000000))
            .border(1.dp, NixiGlassEdge, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = NixiText, fontSize = 12.sp, maxLines = 1)
    }
}
