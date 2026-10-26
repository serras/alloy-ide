package com.serranofp.alloy.viewer

import junit.framework.TestCase

/** Where the toolbar line puts the strip, on a line 400 wide, with a gap of 8. */
class ToolbarLinePlacementTest : TestCase() {
    private fun place(left: Int, strip: Int?, right: Int = 60, width: Int = 400) = toolbarLinePlacement(width, left, strip, right, GAP)

    fun testCenteredWhenEverythingFitsAroundTheCenter() {
        assertEquals(ToolbarLinePlacement.Centered(150), place(left = 100, strip = 100))
    }

    fun testBetweenWhenTheCenterOverlapsAWideLeftGroup() {
        // Centered at 150, over the left group and its gap; the free space is 400 - 60 - 200 - 16 = 124
        assertEquals(ToolbarLinePlacement.Between(200 + GAP + 12), place(left = 200, strip = 100))
    }

    fun testBetweenWhenTheCenterOverlapsAWideRightGroup() {
        // Centered at 150, its end at 250, past 400 - 200 - 8; the free space is 400 - 200 - 50 - 16 = 134
        assertEquals(ToolbarLinePlacement.Between(50 + GAP + 17), place(left = 50, strip = 100, right = 200))
    }

    fun testBelowWhenTheStripIsWiderThanTheFreeSpace() {
        assertEquals(ToolbarLinePlacement.Below, place(left = 200, strip = 200))
    }

    fun testNoneWithoutAStrip() {
        assertEquals(ToolbarLinePlacement.None, place(left = 100, strip = null))
        assertEquals(ToolbarLinePlacement.None, place(left = 1000, strip = null))
    }

    fun testFitsBeforeTheFirstLayout() {
        assertTrue(place(left = 100, strip = 1000, width = 0) is ToolbarLinePlacement.Centered)
    }

    /** Centered at 150, the strip starts right after the left group's gap at a left group of 142. */
    fun testCenteredLeftThreshold() {
        assertEquals(ToolbarLinePlacement.Centered(150), place(left = 150 - GAP, strip = 100))
        assertTrue(place(left = 150 - GAP + 1, strip = 100) is ToolbarLinePlacement.Between)
    }

    /** Centered at 150, the strip ends at 250, right before the right group's gap at a right group of 142. */
    fun testCenteredRightThreshold() {
        assertEquals(ToolbarLinePlacement.Centered(150), place(left = 50, strip = 100, right = 150 - GAP))
        assertTrue(place(left = 50, strip = 100, right = 150 - GAP + 1) is ToolbarLinePlacement.Between)
    }

    /** Between the groups, the strip fits exactly when it is as wide as the free space. */
    fun testBetweenThreshold() {
        val free = 400 - 60 - 200 - 2 * GAP
        assertEquals(ToolbarLinePlacement.Between(200 + GAP), place(left = 200, strip = free))
        assertEquals(ToolbarLinePlacement.Below, place(left = 200, strip = free + 1))
    }

    private companion object {
        const val GAP = 8
    }
}
