package io.github.psd2live.targets.gltf

/** A small JSON value tree with deterministic output, enough for a glTF document. */
internal sealed interface J {
	data class Obj(val entries: List<Pair<String, J>>) : J
	data class Arr(val items: List<J>) : J
	data class Str(val value: String) : J
	data class Num(val value: Number) : J
	data class Bool(val value: Boolean) : J
	data object Null : J

	fun write(out: StringBuilder) {
		when (this) {
			is Obj -> {
				out.append('{')
				entries.forEachIndexed { i, (key, value) -> if (i > 0) out.append(','); quote(key, out); out.append(':'); value.write(out) }
				out.append('}')
			}
			is Arr -> { out.append('['); items.forEachIndexed { i, item -> if (i > 0) out.append(','); item.write(out) }; out.append(']') }
			is Str -> quote(value, out)
			is Num -> out.append(number(value))
			is Bool -> out.append(value)
			Null -> out.append("null")
		}
	}

	companion object {
		fun obj(vararg entries: Pair<String, J>) = Obj(entries.toList())
		fun arr(items: List<J>) = Arr(items)
		fun floats(values: FloatArray) = Arr(values.map { Num(it) })
		fun vec(vararg values: Float) = Arr(values.map { Num(it) })

		private fun number(value: Number): String = when (value) {
			is Float -> {
				require(value.isFinite()) { "Non-finite number in the glTF document" }
				// Plain decimals: some readers reject exponents.
				if (value == value.toLong().toFloat() && kotlin.math.abs(value) < 1e9f) value.toLong().toString()
				else java.math.BigDecimal(value.toString()).toPlainString()
			}
			is Double -> number(value.toFloat())
			else -> value.toString()
		}

		private fun quote(text: String, out: StringBuilder) {
			out.append('"')
			for (c in text) when (c) {
				'"' -> out.append("\\\"")
				'\\' -> out.append("\\\\")
				'\n' -> out.append("\\n")
				'\r' -> out.append("\\r")
				'\t' -> out.append("\\t")
				else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
			}
			out.append('"')
		}
	}
}
