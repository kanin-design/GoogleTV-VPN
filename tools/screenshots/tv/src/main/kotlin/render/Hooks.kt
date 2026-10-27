package render

import dev.shieldvpn.ui.tv.Nav
import dev.shieldvpn.vpn.SpeedTest

/** What the patched app reads from the renderer, set before each shot. */
object Hooks {
    /** Where the app is: the screen, the column, the sheet. */
    var nav: Nav? = null

    /** The phone server's address, for the code on Import: there is no home network here. */
    var address: String? = null

    /** What the speed test measures, at once, instead of asking Cloudflare. */
    var speed: SpeedTest.Result? = null
}
