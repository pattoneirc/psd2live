package io.github.psd2live.targets.spine

/** A minimal JSON reader for the tests: objects, arrays, strings, numbers, booleans and null. */
public class MiniJson(private val text: String) {
	private var i = 0
	public fun value(): Any? {
		skip()
		return when (val c = text[i]) {
			'{' -> { i++; val map = LinkedHashMap<String, Any?>(); skip(); if (text[i] == '}') { i++; return map }
				while (true) { skip(); val key = string(); skip(); i++; map[key] = value(); skip(); if (text[i++] == '}') break }; map }
			'[' -> { i++; val list = ArrayList<Any?>(); skip(); if (text[i] == ']') { i++; return list }
				while (true) { list += value(); skip(); if (text[i++] == ']') break }; list }
			'"' -> string()
			't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
			else -> { val start = i; while (i < text.length && (text[i].isDigit() || text[i] in "-+.eE")) i++; check(i > start) { "bad json at $i: $c" }; text.substring(start, i).toDouble() }
		}
	}
	private fun skip() { while (i < text.length && text[i].isWhitespace()) i++ }
	private fun string(): String { i++; val out = StringBuilder(); while (text[i] != '"') { if (text[i] == '\\') { i++; out.append(when (text[i]) { 'n' -> '\n'; 't' -> '\t'; else -> text[i] }) } else out.append(text[i]); i++ }; i++; return out.toString() }
}
