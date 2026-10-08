package io.github.psd2live.ui.views

/** A column of the log dock. The message takes the room the others leave and is always shown. */
internal enum class LogColumn(val defaultWidth: Float, val minWidth: Float) {
	TIME(50f, 36f),
	SOURCE(40f, 28f),
	TAG(64f, 24f),
	MESSAGE(0f, 80f),
}

private const val MAX_COLUMN_WIDTH = 400f

/**
 * The log dock's columns: their order, the width (dp) of each fixed column, and the ones hidden. An app preference,
 * kept by [encode] in one string.
 */
internal data class LogColumnLayout(
	val order: List<LogColumn> = LogColumn.entries,
	val widths: Map<LogColumn, Float> = emptyMap(),
	val hidden: Set<LogColumn> = emptySet(),
) {
	val visible: List<LogColumn> get() = order.filter { it !in hidden }

	fun width(column: LogColumn): Float = widths[column] ?: column.defaultWidth

	/** [column] at [width], within its range; a column back at its default width keeps no entry. */
	fun resized(column: LogColumn, width: Float): LogColumnLayout {
		if (column == LogColumn.MESSAGE) return this
		val clamped = width.coerceIn(column.minWidth, MAX_COLUMN_WIDTH)
		return copy(widths = if (clamped == column.defaultWidth) widths - column else widths + (column to clamped))
	}

	/** [column] moved to [index] among the visible columns; hidden ones keep their place in the order. */
	fun moved(column: LogColumn, index: Int): LogColumnLayout {
		val shown = visible.toMutableList()
		if (column !in shown) return this
		shown.remove(column)
		shown.add(index.coerceIn(0, shown.size), column)
		val iterator = shown.iterator()
		return copy(order = order.map { if (it in hidden) it else iterator.next() })
	}

	fun toggled(column: LogColumn): LogColumnLayout =
		if (column == LogColumn.MESSAGE) this
		else copy(hidden = if (column in hidden) hidden - column else hidden + column)

	/** `TIME:50,SOURCE:40,-TAG:64,MESSAGE`: the order, each fixed column's width, a minus for a hidden one. */
	fun encode(): String = order.joinToString(",") { column ->
		buildString {
			if (column in hidden) append('-')
			append(column.name)
			if (column != LogColumn.MESSAGE) append(':').append(width(column).toInt())
		}
	}

	companion object {
		/** The layout [text] stores; columns it misses or garbles fall back to their defaults, at the end. */
		fun decode(text: String?): LogColumnLayout {
			if (text.isNullOrBlank()) return LogColumnLayout()
			val order = mutableListOf<LogColumn>()
			val widths = mutableMapOf<LogColumn, Float>()
			val hidden = mutableSetOf<LogColumn>()
			for (part in text.split(',')) {
				val hide = part.startsWith('-')
				val name = part.removePrefix("-").substringBefore(':')
				val column = LogColumn.entries.firstOrNull { it.name == name } ?: continue
				if (column in order) continue
				order += column
				part.substringAfter(':', "").toFloatOrNull()?.let { widths[column] = it }
				if (hide && column != LogColumn.MESSAGE) hidden += column
			}
			order += LogColumn.entries.filter { it !in order }
			return widths.entries.fold(LogColumnLayout(order, emptyMap(), hidden)) { layout, (column, width) -> layout.resized(column, width) }
		}
	}
}
