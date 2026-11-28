package io.github.keplersj.selkie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.keplersj.selkie.service.RootChoice
import io.github.keplersj.selkie.service.RootRequest
import kotlinx.coroutines.delay

/** How long the answers stay asleep, so a tap meant for the app underneath can't land on one. */
internal const val ROOT_BUTTONS_WAKE_MS = 800L

/**
 * A guest command that wants root, on a Selkie screen of its own with Android's bars showing: never drawn
 * inside a session. It names the container, the program as the container will find it and every argument;
 * the calling program's name is only the container's word. The answers wake after [ROOT_BUTTONS_WAKE_MS].
 */
@Composable
fun RootRequestScreen(
    request: RootRequest,
    /** The container's image, as Selkie names it, if it has one. */
    image: String?,
    /** Further requests waiting after this one. */
    waiting: Int,
    onAnswer: (RootChoice) -> Unit,
    /** Starts with the buttons awake, for previews and screenshots. */
    awake: Boolean = false,
) {
    var ready by remember(request.container, request.id) { mutableStateOf(awake) }
    LaunchedEffect(request.container, request.id) {
        if (!ready) { delay(ROOT_BUTTONS_WAKE_MS); ready = true }
    }
    val c = request.container
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mark(size = 40.dp) { Icon(SelkieIcons.Containers, contentDescription = null, Modifier.size(24.dp)) }
                Text("Selkie", style = MaterialTheme.typography.titleLarge)
            }
            Mark(style = MarkStyle.Tertiary, size = 72.dp) {
                Icon(SelkieIcons.Root, contentDescription = null, Modifier.size(32.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Let the container \"$c\" run this as root?", Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall)
                MutedText("As root it can change anything in $c, not only what this command is about.",
                    style = MaterialTheme.typography.bodyLarge)
                if (waiting > 0) MutedText("$waiting more waiting after this one.")
            }
            Group {
                DetailItem("Program", request.program ?: request.argv[0], mono = true)
                val args = request.argv.drop(1)
                if (args.isNotEmpty()) DetailItem(if (args.size == 1) "Argument" else "Arguments",
                    args.joinToString("\n"), mono = true)
                DetailItem("Runs in", request.cwd, mono = true)
                DetailItem("Container", listOfNotNull(c, image).joinToString(" · "))
                GroupItem("Asked through ${request.via}",
                    supporting = request.from?.let { "\"$it\" · a name the container gave. Selkie can't verify it." }
                        ?: "The container didn't say which program asked.",
                    muted = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val big = 24.dp; val small = 6.dp
                RootAnswer("While $c is running", RoundedCornerShape(big, big, small, small), ready) { onAnswer(RootChoice.WhileRunning) }
                RootAnswer("Only this time", RoundedCornerShape(small), ready) { onAnswer(RootChoice.Once) }
                RootAnswer("Don't run", RoundedCornerShape(small, small, big, big), ready) { onAnswer(RootChoice.Deny) }
            }
            MutedText("\"While $c is running\" lets every root command in $c run without asking until its " +
                "terminals and desktop stop.")
        }
    }
}

@Composable
private fun RootAnswer(text: String, shape: RoundedCornerShape, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick, Modifier.fillMaxWidth().height(56.dp), enabled = enabled, shape = shape) { Text(text) }
}
