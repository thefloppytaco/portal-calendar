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
    val BG = 0xFFF4F1EA.toInt()          // warm paper background
    val CARD = 0xFFFFFFFF.toInt()        // white cards
    val CARD_SOFT = 0xFFFDFCF9.toInt()   // barely-off-white inner fills
    val TODAY_CARD = 0xFFFFF9F2.toInt()  // cream tint for "today"
    val OUT_CARD = 0xFFF8F5EE.toInt()    // outside-month cells
    val PILL = 0xFFEAE6DC.toInt()        // neutral button pills
    val INK = 0xFF333A45.toInt()         // primary text
    val MUTED = 0xFF737983.toInt()       // secondary text
    val FAINT = 0xFFA3A8B0.toInt()       // tertiary text
    val ACCENT = 0xFFF0584C.toInt()      // coral
    val LINE = 0xFFECE8DF.toInt()        // hairlines
    val FIELD = 0xFFFBFAF6.toInt()       // input fill
    val FIELD_STROKE = 0xFFDDD8CC.toInt()
    const val SCRIM = 0x59000000         // overlay dim

    const val R_CARD = 16
    const val R_ELEMENT = 12
    const val R_PILL = 20
    const val R_OVERLAY = 20
}
