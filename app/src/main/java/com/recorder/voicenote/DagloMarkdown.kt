package com.recorder.voicenote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 요약·보드챗 답변에 쓰는 아주 작은 마크다운 렌더러.
 *
 * 웹은 marked.js 로 HTML 을 만들어 뿌리지만, 앱에서 그 한 줄을 위해 WebView 를 띄우면
 * 스크롤·선택·테마가 전부 따로 놀게 된다. Gemini 가 실제로 쓰는 문법(제목, 굵게, 기울임,
 * 불릿/번호 목록, 인라인 코드)과 타임스탬프 링크만 직접 그린다.
 */

private val TIMESTAMP_RE = Regex("""\[(\d{1,2}:\d{2}(?::\d{2})?)\]""")
private const val TS_TAG = "daglo-ts"

@Composable
fun DagloMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    onTimestampClick: (Long) -> Unit = {}
) {
    Column(modifier = modifier) {
        text.split("\n").forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(modifier = Modifier.height(6.dp))

                line.startsWith("### ") -> MarkdownLine(
                    line.removePrefix("### "), 15.sp, FontWeight.Bold, onTimestampClick,
                    Modifier.padding(top = 10.dp, bottom = 2.dp)
                )

                line.startsWith("## ") -> MarkdownLine(
                    line.removePrefix("## "), 17.sp, FontWeight.Bold, onTimestampClick,
                    Modifier.padding(top = 12.dp, bottom = 3.dp)
                )

                line.startsWith("# ") -> MarkdownLine(
                    line.removePrefix("# "), 19.sp, FontWeight.Bold, onTimestampClick,
                    Modifier.padding(top = 12.dp, bottom = 4.dp)
                )

                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                    BulletLine("•", line.trimStart().drop(2), onTimestampClick)

                Regex("""^\s*\d+\.\s""").containsMatchIn(line) -> {
                    val marker = Regex("""^\s*(\d+)\.\s""").find(line)?.groupValues?.get(1) ?: "1"
                    BulletLine("$marker.", line.substringAfter(". "), onTimestampClick)
                }

                line.startsWith("---") || line.startsWith("***") ->
                    Spacer(modifier = Modifier.height(10.dp))

                else -> MarkdownLine(line, 14.sp, FontWeight.Normal, onTimestampClick)
            }
        }
    }
}

@Composable
private fun BulletLine(marker: String, content: String, onTimestampClick: (Long) -> Unit) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            text = marker,
            fontSize = 14.sp,
            color = DagloColors.TextMuted,
            modifier = Modifier.padding(start = 4.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        MarkdownLine(content, 14.sp, FontWeight.Normal, onTimestampClick)
    }
}

@Composable
private fun MarkdownLine(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight,
    onTimestampClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val annotated = remember(text) { inlineMarkdown(text) }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(
            fontSize = fontSize,
            fontWeight = weight,
            color = DagloColors.TextMain,
            lineHeight = fontSize * 1.6f
        ),
        onClick = { offset ->
            annotated.getStringAnnotations(TS_TAG, offset, offset).firstOrNull()?.let {
                onTimestampClick(it.item.toLongOrNull() ?: 0L)
            }
        }
    )
}

/** `**굵게**`, `*기울임*`, `` `코드` `` 와 [MM:SS] 링크를 처리한다. */
private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val stripped = text
    while (i < stripped.length) {
        val rest = stripped.substring(i)

        val bold = Regex("""^\*\*(.+?)\*\*""").find(rest)
        if (bold != null) {
            withStyleSpan(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold.groupValues[1]) }
            i += bold.value.length
            continue
        }

        val italic = Regex("""^\*(?!\*)(.+?)\*""").find(rest)
        if (italic != null) {
            withStyleSpan(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic.groupValues[1]) }
            i += italic.value.length
            continue
        }

        val code = Regex("""^`([^`]+)`""").find(rest)
        if (code != null) {
            withStyleSpan(
                SpanStyle(fontFamily = FontFamily.Monospace, background = DagloColors.BgMain)
            ) { append(code.groupValues[1]) }
            i += code.value.length
            continue
        }

        val ts = TIMESTAMP_RE.find(rest)
        if (ts != null && ts.range.first == 0) {
            val ms = timestampToMs(ts.groupValues[1])
            pushStringAnnotation(TS_TAG, ms.toString())
            withStyleSpan(
                SpanStyle(color = DagloColors.Primary, textDecoration = TextDecoration.Underline)
            ) { append(ts.value) }
            pop()
            i += ts.value.length
            continue
        }

        append(stripped[i])
        i++
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleSpan(
    style: SpanStyle,
    block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit
) {
    pushStyle(style)
    block()
    pop()
}

/** "12:34" / "1:02:03" → 밀리초 */
fun timestampToMs(ts: String): Long {
    val parts = ts.split(":").mapNotNull { it.toLongOrNull() }
    return when (parts.size) {
        2 -> (parts[0] * 60 + parts[1]) * 1000
        3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
        else -> 0L
    }
}
