package com.portal.calendar

/**
 * The design language, in one place. Warm-paper board, white cards with soft
 * shadows, one coral accent, solid per-person color fills with white text.
 *
 * Patterns the screens follow:
 *  - Section headers: 13sp ACCENT sans-serif-medium MICROCAPS (letterSpacing .14)
 *  - Containers (columns, panels): CARD fill, radius [R_CARD], elevation 1–2dp
 *  - Elements inside containers (rows, kid cards): radius [R_ELEMENT]
 *  - Buttons: pills — PILL fill + INK text, or ACCENT fill + white text for
 *    the primary action; radius [R_PILL]
 *  - Overlays: CARD card radius [R_OVERLAY] on a [SCRIM] scrim, elevation 10dp
 */
object Palette {
    /**
     * Whether the board renders dark. Set ONCE per board build (the board
     * rebuilds on a theme change), before any view is created. Every color
     * below is a getter so it reflects the current value live — the companion
     * color aliases in the screens are getters too, for the same reason.
     */
    var dark = false

    // light value ... dark value. Never pure #000/#FFF (harsh on this panel).
    val BG get() = pick(0xFFF4F1EA, 0xFF12141A)          // background
    val CARD get() = pick(0xFFFFFFFF, 0xFF1E2229)        // cards
    val CARD_SOFT get() = pick(0xFFFDFCF9, 0xFF242A33)   // inner fills
    val TODAY_CARD get() = pick(0xFFFFF9F2, 0xFF2A2119)  // "today" tint
    val OUT_CARD get() = pick(0xFFF8F5EE, 0xFF181B21)    // outside-month cells
    val PILL get() = pick(0xFFEAE6DC, 0xFF2A2F38)        // neutral button pills
    val INK get() = pick(0xFF333A45, 0xFFEDEFF2)         // primary text
    val MUTED get() = pick(0xFF737983, 0xFF9AA0AA)       // secondary text
    val FAINT get() = pick(0xFFA3A8B0, 0xFF6B7079)       // tertiary text
    val ACCENT = 0xFFF0584C.toInt()                      // coral — same on both
    val LINE get() = pick(0xFFECE8DF, 0xFF2A2E36)        // hairlines
    val FIELD get() = pick(0xFFFBFAF6, 0xFF20242B)       // input fill
    val FIELD_STROKE get() = pick(0xFFDDD8CC, 0xFF333842)
    val SCRIM get() = if (dark) 0x99000000.toInt() else 0x59000000

    private fun pick(light: Long, dark2: Long): Int = (if (dark) dark2 else light).toInt()

    const val R_CARD = 16
    const val R_ELEMENT = 12
    const val R_PILL = 20
    const val R_OVERLAY = 20
}
