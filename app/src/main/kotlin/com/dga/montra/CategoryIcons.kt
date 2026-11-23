package com.dga.montra

fun categoryIconDrawable(id: String): Int = when (id) {
    "dining" -> R.drawable.ic_cat_dining
    "groceries" -> R.drawable.ic_cat_groceries
    "transport" -> R.drawable.ic_cat_transport
    "utilities" -> R.drawable.ic_cat_utilities
    "rent" -> R.drawable.ic_cat_rent
    "health" -> R.drawable.ic_cat_health
    "fun" -> R.drawable.ic_cat_fun
    "salary" -> R.drawable.ic_cat_salary
    "freelance" -> R.drawable.ic_cat_freelance
    "transfer" -> R.drawable.ic_cat_transfer
    else -> R.drawable.ic_cat_other
}

fun categoryColor(id: String): Int = when (id) {
    "dining" -> 0xFFD97706.toInt()      // amber
    "groceries" -> 0xFF059669.toInt()   // emerald
    "transport" -> 0xFF2563EB.toInt()   // blue
    "utilities" -> 0xFFCA8A04.toInt()   // golden yellow
    "rent" -> 0xFF7C3AED.toInt()        // violet
    "health" -> 0xFFDC2626.toInt()      // red
    "fun" -> 0xFFDB2777.toInt()         // pink
    "salary" -> 0xFF059669.toInt()      // emerald
    "freelance" -> 0xFF0891B2.toInt()   // cyan
    "transfer" -> 0xFFC49A5A.toInt()    // highlight gold
    else -> 0xFF176B55.toInt()          // default accent
}

fun categoryBgColor(id: String): Int = when (id) {
    "dining" -> 0xFFFEF3C7.toInt()      // light amber
    "groceries" -> 0xFFD1FAE5.toInt()   // light emerald
    "transport" -> 0xFFDBEAFE.toInt()   // light blue
    "utilities" -> 0xFFFEF9C3.toInt()   // light yellow
    "rent" -> 0xFFEDE9FE.toInt()        // light violet
    "health" -> 0xFFFEE2E2.toInt()      // light red
    "fun" -> 0xFFFCE7F3.toInt()         // light pink
    "salary" -> 0xFFD1FAE5.toInt()      // light emerald
    "freelance" -> 0xFFCFFAFE.toInt()   // light cyan
    "transfer" -> 0xFFF5EEDA.toInt()    // light gold
    else -> 0xFFE6F2EE.toInt()          // light accent
}

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
