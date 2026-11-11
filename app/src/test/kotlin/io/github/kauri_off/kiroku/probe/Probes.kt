package io.github.kauri_off.kiroku.probe

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Calendar

// Probe screens stand in for app code: this package is instrumented as the app is.

fun wallClockReadings(): Map<String, String> {
    val zone = ZoneId.systemDefault()
    return mapOf(
        "System.currentTimeMillis" to Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
        "Instant.now" to Instant.now().toString(),
        "Clock.systemDefaultZone" to Clock.systemDefaultZone().instant().toString(),
        "Clock.systemUTC" to Clock.systemUTC().instant().toString(),
        "ZonedDateTime.now" to ZonedDateTime.now().toInstant().toString(),
        "OffsetDateTime.now" to OffsetDateTime.now(ZoneOffset.UTC).toInstant().toString(),
        "LocalDateTime.now" to
            LocalDateTime
                .now()
                .atZone(zone)
                .toInstant()
                .toString(),
        "LocalDate.now" to LocalDate.now().toString(),
        "Calendar.getInstance" to Calendar.getInstance().toInstant().toString(),
        "DateUtils" to DateUtils.getRelativeTimeSpanString(FixtureTime.reference.minus(Duration.ofDays(3)).toEpochMilli()).toString(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockProbe(onDisplayedMonth: (Long) -> Unit = {}) {
    val picker = rememberDatePickerState()
    onDisplayedMonth(picker.displayedMonthMillis)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            wallClockReadings().forEach { (source, value) -> Text("$source $value") }
            DatePicker(picker)
        }
    }
}

@Composable
fun SmallTarget(label: String) {
    Box(
        Modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.error)
            .semantics { contentDescription = label }
            .clickable {},
    )
}

@Composable
fun CentredSmallTarget() {
    Surface(Modifier.fillMaxSize()) { Box(contentAlignment = Alignment.Center) { SmallTarget("centred") } }
}

@Composable
fun LongListEndingInSmallTarget() {
    LazyColumn(Modifier.fillMaxSize()) {
        items((1..40).toList()) { ListItem { Text("Row $it") } }
        item {
            Row {
                Text("Last row")
                SmallTarget("below the fold")
            }
        }
    }
}

@Composable
fun DialogProbe(smallTarget: Boolean) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) { items((1..40).toList()) { ListItem { Text("Row $it") } } }
        AlertDialog(
            onDismissRequest = {},
            confirmButton = { TextButton(onClick = {}) { Text("OK") } },
            title = { Text("Dialog") },
            text = {
                Row {
                    Text("Text")
                    if (smallTarget) SmallTarget("in a dialog")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetProbe(
    rows: Int,
    rowsBehind: Int,
    smallTarget: Boolean = false,
    skipPartiallyExpanded: Boolean = true,
    onEndMarker: (Int) -> Unit = {},
    onContainer: (Int) -> Unit = {},
) {
    Surface(Modifier.fillMaxSize()) {
        LazyColumn { items((1..rowsBehind).toList()) { ListItem { Text("Behind $it") } } }
        val container = BottomSheetDefaults.ContainerColor
        onContainer(container.toArgb())
        val marker = MaterialTheme.colorScheme.tertiary
        onEndMarker(marker.toArgb())
        ModalBottomSheet(
            onDismissRequest = {},
            sheetState =
                rememberBottomSheetState(
                    SheetValue.Hidden,
                    enabledValues =
                        SheetValue.entries
                            .filter {
                                !skipPartiallyExpanded ||
                                    it != SheetValue.PartiallyExpanded
                            }.toSet(),
                ),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                repeat(rows) { Text("Sheet row ${it + 1}", Modifier.fillMaxWidth().height(56.dp)) }
                Row {
                    Text("Sheet end")
                    if (smallTarget) SmallTarget("in a sheet")
                }
                Box(Modifier.fillMaxWidth().height(8.dp).background(marker))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverflowingSheet() {
    Surface(Modifier.fillMaxSize()) {
        ModalBottomSheet(onDismissRequest = {
        }, sheetState = rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))) {
            Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true)) {
                repeat(60) { Text("Line ${it + 1}", Modifier.fillMaxWidth().height(56.dp)) }
            }
        }
    }
}

@Composable
fun SqueezedRow() {
    val russian = LocalConfiguration.current.locales[0].language == "ru"
    Surface(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            Text(if (russian) "Сейчас смотрю: Провожающая в последний путь Фрирен" else "Watching: Frieren")
            IconButton(onClick = {}) { Text("+") }
        }
    }
}

@Composable
fun InlineLinkProbe() {
    Surface(Modifier.fillMaxSize()) {
        Text(
            buildAnnotatedString {
                append("Report it on ")
                withLink(LinkAnnotation.Url("https://example.org")) { append("the tracker") }
            },
        )
    }
}

@Composable
fun WidthOnlyPoster(onSize: (IntSize) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column {
            AsyncImage(
                model = "https://cdn.myanimelist.net/images/anime/1/1.jpg",
                contentDescription = null,
                modifier = Modifier.width(100.dp).onGloballyPositioned { onSize(it.size) },
            )
        }
    }
}

@Composable
fun StripProbe(smallTargetInLastCard: Boolean = false) {
    Surface(Modifier.fillMaxSize()) {
        LazyColumn {
            item { Text("Strip") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((1..12).toList()) { card ->
                        Column(Modifier.width(STRIP_CARD_DP.dp)) {
                            Box(Modifier.size(STRIP_CARD_DP.dp, 156.dp).background(MaterialTheme.colorScheme.secondaryContainer))
                            Text(if (card == 12) "Card twelve, whose caption is the longest" else "Card $card")
                            if (card == 12 && smallTargetInLastCard) SmallTarget("in the last card")
                        }
                    }
                }
            }
        }
    }
}

const val STRIP_CARD_DP = 104

@Composable
fun KeyboardProbe(
    onImeBottom: (Int) -> Unit,
    onFieldBottom: (Float) -> Unit,
) {
    onImeBottom(WindowInsets.ime.getBottom(LocalDensity.current))
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(MaterialTheme.colorScheme.primary)
                    .onGloballyPositioned { onFieldBottom(it.positionInWindow().y + it.size.height) },
            )
        }
    }
}

@Composable
fun StockControls() {
    Surface(Modifier.fillMaxSize()) {
        Column {
            Button(onClick = {}) { Text("Save") }
            TextButton(onClick = {}) { Text("See all") }
            IconButton(onClick = {}) { Text("+") }
            Checkbox(checked = true, onCheckedChange = {}, modifier = Modifier.semantics { contentDescription = "Checkbox" })
            Switch(checked = true, onCheckedChange = {}, modifier = Modifier.semantics { contentDescription = "Switch" })
            RadioButton(selected = true, onClick = {}, modifier = Modifier.semantics { contentDescription = "Radio" })
            FilterChip(selected = true, onClick = {}, label = { Text("Airing") })
            ListItem(onClick = {}) { Text("Row") }
        }
    }
}

@Composable
fun RemoteImageProbe() {
    Surface(Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = "https://cdn.myanimelist.net/images/anime/1/1.jpg",
                contentDescription = null,
                modifier = Modifier.size(56.dp, 84.dp),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
