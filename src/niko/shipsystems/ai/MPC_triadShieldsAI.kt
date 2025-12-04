package niko.shipsystems.ai

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.util.IntervalUtil
import niko.MPC_triadShieldsAICore
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.AIUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lwjgl.util.vector.Vector2f

class MPC_triadShieldsAI: ShipSystemAIScript {

    protected var ship: ShipAPI? = null

    lateinit var ai: MPC_triadShieldsAICore

    override fun init(ship: ShipAPI?, system: ShipSystemAPI?, flags: ShipwideAIFlags?, engine: CombatEngineAPI?) {
        ai = MPC_triadShieldsAICore(ship!!)
        this.ship = ship
    }

    override fun advance(
        amount: Float,
        missileDangerDir: Vector2f?,
        collisionDangerDir: Vector2f?,
        target: ShipAPI?
    ) {
        val command = ai.getCommand()
        if (command == MPC_triadShieldsAICore.Command.DEACTIVATE) {
            tryDeactivate()
        } else if (command == MPC_triadShieldsAICore.Command.ACTIVATE) {
            tryActivate()
        }
    }

    private fun tryDeactivate() {
        if (!ship!!.phaseCloak.isActive) return

        ship!!.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0)
    }

    private fun tryActivate() {
        if (ship!!.phaseCloak.isActive) return
        if (ship!!.fluxTracker.isOverloaded) return
        if (ship!!.fluxTracker.isVenting) return

        ship!!.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0)
    }
}