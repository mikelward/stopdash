package app.stopdash.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.core.net.toUri
import app.stopdash.R
import app.stopdash.domain.AlertLinks
import kotlinx.coroutines.withContext

/**
 * Disruption text (TfL's or National Rail's) with each web link in it tappable (maintainer, 2026-10-07:
 * National Rail often gives a page and nothing else). The links are found on [LocalWorker], never in
 * composition (AGENTS.md *Main thread: read and dispatch only*): the text shows plain on its first
 * frame and gains its links when they're found, the same characters in the same place, so nothing moves.
 */
@Composable
internal fun LinkedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val onOpen = rememberLinkOpener()
    val worker = LocalWorker.current
    // Keyed on the text so a changed alert shows its own words at once, never the last one's
    // links while the worker runs.
    var linked by remember(text) { mutableStateOf(AnnotatedString(text)) }
    LaunchedEffect(text, onOpen, worker) {
        linked = withContext(worker) { linkified(text, onOpen) }
    }
    Text(text = linked, modifier = modifier, style = style, color = color, maxLines = maxLines, overflow = overflow)
}

/**
 * Opens a tapped link in the browser. Guarded rather than the default URI handler, which throws on a
 * device with no browser: says so instead of crashing (as LicensesScreen does).
 */
@Composable
internal fun rememberLinkOpener(): LinkInteractionListener {
    val context = LocalContext.current
    val linkFailed = stringResource(R.string.link_open_failed)
    return remember(context, linkFailed) {
        LinkInteractionListener { annotation ->
            val url = (annotation as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            } catch (e: ActivityNotFoundException) {
                // The URL is the operator's own alert text, not user data; the log still omits it.
                Log.w("Alerts", "No activity to open an alert link", e)
                Toast.makeText(context, linkFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * [text] with each web link ([AlertLinks]) made a tappable, underlined link. Opening goes through
 * Compose's [LinkAnnotation.Url] with [onOpen] — only `http`/`https` links are produced, so a tap can't
 * open any other scheme. A link is also an accessibility action, so a screen reader can open it. Walks
 * the text: on a worker only.
 */
@WorkerThread
internal fun linkified(text: String, onOpen: LinkInteractionListener): AnnotatedString {
    val links = AlertLinks.find(text)
    if (links.isEmpty()) return AnnotatedString(text)
    val style = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var at = 0
        for (link in links) {
            append(text, at, link.range.first)
            withLink(LinkAnnotation.Url(link.url, style, onOpen)) { append(text.substring(link.range)) }
            at = link.range.last + 1
        }
        append(text, at, text.length)
    }
}
