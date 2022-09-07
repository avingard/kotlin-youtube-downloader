package com.avingard.youtube_video_downloader

fun jsToJson(code: String, strict: Boolean = false): String {
    val commentsRegex = "/\\*(?:(?!\\*/).)*?\\*/|//[^\\n]*\\n"
    val skipRegex = "\\s*(?:$commentsRegex)?\\s*"
    val integerTable = listOf(
        16 to "(?s)^(0[xX][0-9a-fA-F]+)$skipRegex:?\$",
        8 to "(?s)^(0+[0-7]+)$skipRegex:?\$"
    )

    fun fixKv(matchResult: MatchResult): String {
        var v = matchResult.value

        if (v == "true" || v == "false" || v == "null") {
            return v
        } else if (v == "undefined" || v == "void   0") {
            return "null"
        } else if (v.startsWith("/*") || v.startsWith("//") || v.startsWith("!") || v == ",") {
            return ""
        }

        if (v[0] == '\'' || v[0] == '"') {
            val regex = Regex("(?s)\\\\.|\"")
            val substring = v.substring(1..v.length - 2)
            v = substring.replace(regex) {
                val mapping = mapOf(
                    "\"" to "\\\\\"",
                    "\\\\'" to "'",
                    "\\\\\\n" to "",
                    "\\\\x" to "\\\\u00"
                )
                mapping[it.value]!!
            }
        } else {
            for ((base, regex) in integerTable) {
                val matchGroups = Regex(regex).find(v)?.groups
                val group = matchGroups?.get(1)?.value

                if (group != null) {
                    val i = group.toInt(base)
                    return if (v.endsWith(":")) "\"$i\":" else i.toString()
                }
            }
        }

        return "\"$v\""
    }

    var modifiedCode = code
    if (!strict) {
        modifiedCode = modifiedCode.replace(Regex("new Date\\((\".+\")\\)"), "\\g<1>")
    }

    val regex = Regex("(?sx)\"(?:[^\"\\\\]*(?:\\\\\\\\|\\\\['\"nurtbfx/\\n]))*[^\"\\\\]*\"|" +
            "'(?:[^'\\\\]*(?:\\\\\\\\|\\\\['\"nurtbfx/\\n]))*[^'\\\\]*'|" +
            "$commentsRegex|,(?=$skipRegex[\\]}}])|" +
            "void\\s0|(?:(?<![0-9])[eE]|[a-df-zA-DF-Z_\$])[.a-zA-Z_\$0-9]*|" +
            "\\b(?:0[xX][0-9a-fA-F]+|0+[0-7]+)(?:$skipRegex:)?|" +
            "[0-9]+(?=$skipRegex:)|" +
            "!+"
    )

    return modifiedCode.replace(regex, ::fixKv)
}