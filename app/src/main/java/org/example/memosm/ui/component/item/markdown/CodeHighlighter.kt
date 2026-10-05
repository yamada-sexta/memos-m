package org.example.memosm.ui.component.item.markdown

import androidx.collection.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import java.util.Locale

object CodeHighlighter {
    private data class Key(val code: String, val language: SyntaxLanguage, val dark: Boolean)
    private val cache = object : LruCache<Key, AnnotatedString>(250_000) {
        override fun sizeOf(key: Key, value: AnnotatedString): Int = key.code.length.coerceAtLeast(1)
    }

    fun highlightCode(code: String, language: String, isDarkMode: Boolean = true): AnnotatedString {
        val syntaxLanguage = getSyntaxLanguage(language)
        val key = Key(code, syntaxLanguage, isDarkMode)
        cache[key]?.let { return it }
        val theme = SyntaxThemes.default(isDarkMode)

        val highlights =
            Highlights.Builder().code(code).theme(theme).language(syntaxLanguage).build()

        val structure = highlights.getHighlights()

        return buildAnnotatedString {
            append(code)
            structure.forEach { highlight ->
                val start = highlight.location.start
                val end = highlight.location.end

                // Ensure range is valid
                if (start in code.indices && end <= code.length) {
                    when (highlight) {
                        is ColorHighlight -> {
                            addStyle(
                                style = SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f)),
                                start = start,
                                end = end
                            )
                        }

                        is BoldHighlight -> {
                            addStyle(
                                style = SpanStyle(fontWeight = FontWeight.Bold),
                                start = start,
                                end = end
                            )
                        }
                    }
                }
            }
        }.also { cache.put(key, it) }
    }

    private fun getSyntaxLanguage(alias: String): SyntaxLanguage {
        return when (alias.trim().lowercase(Locale.ROOT)) {
            "c" -> SyntaxLanguage.C
            "cpp", "c++" -> SyntaxLanguage.CPP
            "csharp", "c#" -> SyntaxLanguage.CSHARP
            "dart" -> SyntaxLanguage.DART
            "go", "golang" -> SyntaxLanguage.GO
            "java" -> SyntaxLanguage.JAVA
            "js", "javascript" -> SyntaxLanguage.JAVASCRIPT
            "kotlin", "kt" -> SyntaxLanguage.KOTLIN
            "php" -> SyntaxLanguage.PHP
            "perl" -> SyntaxLanguage.PERL
            "python", "py" -> SyntaxLanguage.PYTHON
            "ruby", "rb" -> SyntaxLanguage.RUBY
            "rust", "rs" -> SyntaxLanguage.RUST
            "swift" -> SyntaxLanguage.SWIFT
            "ts", "typescript" -> SyntaxLanguage.TYPESCRIPT
            "coffee", "coffeescript" -> SyntaxLanguage.COFFEESCRIPT
            "shell", "sh", "bash", "zsh" -> SyntaxLanguage.SHELL
            // Fallbacks for unsupported languages
// -----------------------------------------------------------------
            // 2. HEURISTIC FALLBACKS (Approximations)
            // -----------------------------------------------------------------

            // C-Style approximations (Shaders, Arduino, Objective-C)
            "glsl", "hlsl", "shader", "frag", "vert",
            "arduino", "ino",
            "objc", "objective-c" -> SyntaxLanguage.CPP

            // JVM approximations (Gradle/Groovy use C-style syntax similar to Java)
            "groovy", "gradle", "scala" -> SyntaxLanguage.JAVA
            // JavaScript approximations (JSON is valid JS object syntax)
            "json" -> SyntaxLanguage.JAVASCRIPT
            // Python approximations (Build systems often use Python dialects)
            "starlark", "bazel", "gyp" -> SyntaxLanguage.PYTHON

            // Shell/Script approximations (Config files with '#' comments look like Shell)
            "bat", "batch", "cmd", "ps1", "powershell",
            "toml", "ini", "conf", "properties" -> SyntaxLanguage.SHELL
            // -----------------------------------------------------------------
            // 3. UNSUPPORTED / DEFAULT
            // -----------------------------------------------------------------
            // Markup and Styles (HTML/XML/YAML/CSS usually look bad in C/Java highlighters)
            "xml", "html", "xhtml", "rss", "atom", "plist",
            "yaml", "yml",
            "css", "scss", "sass", "less", "styl",
            "sql" -> SyntaxLanguage.DEFAULT

            else -> SyntaxLanguage.DEFAULT
        }
    }
}
