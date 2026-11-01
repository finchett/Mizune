package app.mizune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Rectangle

class WindowPlaceTest {
    private val laptop = Rectangle(0, 0, 1366, 728) // 1366×768 under a 40-high taskbar
    private val big = Rectangle(0, 0, 2560, 1400)

    @Test
    fun aFirstWindowFitsASmallScreen() {
        // 1440×860 would run off this one (the issue): it's made smaller and centred instead
        assertEquals(WindowPlace(Rectangle(32, 32, 1302, 664)), WindowPlace.onLaunch(null, listOf(laptop)))
    }

    @Test
    fun aFirstWindowOnABigScreenIsTheDefaultSizeCentred() {
        assertEquals(WindowPlace(Rectangle(560, 270, 1440, 860)), WindowPlace.onLaunch(null, listOf(big)))
    }

    @Test
    fun theWindowOpensWhereItWasLeft() {
        val saved = WindowPlace(Rectangle(100, 50, 1000, 600), maximized = true)
        assertEquals(saved, WindowPlace.onLaunch(saved, listOf(big)))
    }

    @Test
    fun aWindowLeftOnAScreenThatsSmallerNowIsBroughtOntoIt() {
        val saved = WindowPlace(Rectangle(1200, 600, 1600, 900))
        assertEquals(WindowPlace(Rectangle(0, 0, 1366, 728)), WindowPlace.onLaunch(saved, listOf(laptop)))
    }

    @Test
    fun aWindowLeftOnAScreenThatsGoneOpensOnTheMainOne() {
        // it was on a screen to the right of this one, unplugged since
        val saved = WindowPlace(Rectangle(3000, 100, 1200, 700))
        assertEquals(WindowPlace(Rectangle(83, 14, 1200, 700)), WindowPlace.onLaunch(saved, listOf(laptop)))
    }

    @Test
    fun theSecondScreenKeepsAWindowLeftOnIt() {
        val left = Rectangle(-1920, 0, 1920, 1040)
        val saved = WindowPlace(Rectangle(-1800, 100, 1200, 700))
        assertEquals(saved, WindowPlace.onLaunch(saved, listOf(laptop, left)))
    }

    @Test
    fun encodesAndDecodes() {
        val place = WindowPlace(Rectangle(-1800, 100, 1200, 700), maximized = true)
        assertEquals(place, WindowPlace.decode(place.encode()))
        assertEquals(WindowPlace(Rectangle(1, 2, 3, 4)), WindowPlace.decode("1,2,3,4"))
        assertNull(WindowPlace.decode("1,2,3"))
        assertNull(WindowPlace.decode("1,2,0,4"))
        assertNull(WindowPlace.decode("junk"))
    }

    @Test
    fun noScreensKnownLeavesItToTheSystem() {
        assertNull(WindowPlace.onLaunch(null, emptyList()))
    }
}
