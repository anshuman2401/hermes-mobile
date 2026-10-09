package io.github.anshuman2401.hermesmobile.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compact bottom-nav sizing source contract. Compose presentation is pinned
 * here with PendingInterruptWiringTest-style source assertions because this
 * repo has no compose UI-test infrastructure; visual/device verification is
 * separate.
 *
 * - Bar height: 20% below the Material3 NavigationBar default (80dp → 64dp),
 *   set with an explicit height() so the library's internal
 *   defaultMinSize(80dp) cannot restore the default (verified against
 *   material3 bytecode: NavigationBar applies its default height only when
 *   the caller has not already constrained height).
 * - Icon size: 20% below the default 24dp icon → size(19.dp) (floor of the
 *   19.2dp exact value).
 * - Everything else (tabs, order, semantics, selection behavior) unchanged.
 */
class BottomNavSizingSourceContractTest {

    private fun navDir(): File =
        sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .map { it.resolve("io/github/anshuman2401/hermesmobile/navigation") }
            .first { it.resolve("BottomNav.kt").exists() }

    private fun bottomNavSrc(): String =
        navDir().resolve("BottomNav.kt").readText()

    /** Window following an anchor far enough to cover its parameter list. */
    private fun callWindow(src: String, anchor: String, window: Int = 240): String {
        val idx = src.indexOf(anchor)
        assertTrue("expected '$anchor' not found in BottomNav.kt", idx >= 0)
        return src.substring(idx, minOf(src.length, idx + window))
    }

    @Test
    fun barHeight_pinnedAt64dp_explicitHeightOverridesLibraryDefault() {
        val src = bottomNavSrc()
        val barCall = callWindow(src, "NavigationBar(")
        // 80dp M3 default minus 20% = 64dp, applied on the caller modifier
        // chain so the library's defaultMinSize cannot restore the default.
        assertTrue(
            "Bottom nav: NavigationBar must carry an explicit height(64.dp) (80dp default minus 20%)",
            barCall.contains("height(64.dp)")
        )
    }

    @Test
    fun iconSize_pinnedAt19dp_singleIconRenderInTabLoop() {
        val src = bottomNavSrc()
        // Exactly one Icon render exists (inside the tab loop) and it carries
        // size(19.dp): 24dp default minus 20%, floored.
        val iconCall = callWindow(src, "Icon(")
        assertTrue(
            "Bottom nav: the tab icon must render with size(19.dp) (24dp default minus 20%)",
            iconCall.contains("size(19.dp)")
        )
        // No stale default-size icon render may remain.
        assertFalse(
            "Bottom nav: no unmodified (default-size) Icon render may remain",
            src.contains("imageVector = BottomNavIcons.iconFor(tab),\n                        contentDescription = label\n")
        )
    }

    @Test
    fun semanticsAndTabs_untouched() {
        val src = bottomNavSrc()
        // TalkBack label still attached to the icon; all four tabs still in
        // the loop; selection behavior unchanged.
        assertTrue(
            "Bottom nav: contentDescription (tab label) must stay attached",
            src.contains("contentDescription = label")
        )
        assertTrue(
            "Bottom nav: all four tabs must still render in the tab loop",
            src.contains("BottomNavTab.entries.forEach")
        )
        assertTrue(
            "Bottom nav: NavigationBarItem selection/onClick must stay wired",
            src.contains("selected = selectedTab == tab") &&
                src.contains("onClick = { onTabSelected(tab) }")
        )
    }
}
