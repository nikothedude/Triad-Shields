package niko_TS

import com.fs.starfarer.api.BaseModPlugin
import com.fs.starfarer.api.Global

class TS_modPlugin: BaseModPlugin() {

    companion object {
        var graphicsLibEnabled = false
    }

    override fun onApplicationLoad() {
        super.onApplicationLoad()

        graphicsLibEnabled = Global.getSettings().modManager.isModEnabled("shaderLib")
    }

    override fun onGameLoad(newGame: Boolean) {
        super.onGameLoad(newGame)

        Global.getSector().addTransientListener(TS_lootListener())
    }

}