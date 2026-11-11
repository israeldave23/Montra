package com.dga.montra

internal fun categoryIcon(id: String): String = when (id) {
    "dining" -> "🍴"
    "groceries" -> "🛒"
    "transport" -> "🚌"
    "utilities" -> "⚡"
    "rent" -> "⌂"
    "health" -> "+"
    "fun" -> "♪"
    "salary" -> "₱"
    "freelance" -> "✎"
    "transfer" -> "↔"
    else -> "•"
}
