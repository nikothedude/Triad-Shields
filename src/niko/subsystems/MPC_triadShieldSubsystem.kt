package niko.subsystems

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemSpecAPI
import com.fs.starfarer.api.util.Misc
import niko.MPC_triadShieldsAICore
import niko.MPC_triadShieldsCore
import niko.ReflectionUtilsTwo
import niko.shipsystems.MPC_triadShields
import org.magiclib.subsystems.MagicShipSystemSubsystem
import org.magiclib.subsystems.MagicSubsystem

class MPC_triadShieldSubsystem(ship: ShipAPI): MagicShipSystemSubsystem(ship) {

    val ai = MPC_triadShieldsAICore(ship)

    override fun hasCharges(): Boolean {
        return false
    }

    override fun getShipSystemId(): String {
        return "MPC_triadShields"
    }

    override fun shouldActivateAI(amount: Float): Boolean {
        if (state == State.ACTIVE || state == State.IN) {
            return ai.getCommand() == MPC_triadShieldsAICore.Command.DEACTIVATE
        }
        if (state == State.READY || state == State.OUT) {
            return ai.getCommand() == MPC_triadShieldsAICore.Command.ACTIVATE
        }
        return false
    }

    override fun isToggle(): Boolean {
        return true
    }

    override fun onActivate() {
        super.onActivate()

        val spec = getSpec()
        if (state == State.READY) {
            Global.getSoundPlayer().playSound(spec.useSound, 1f, 1f, ship.location, Misc.ZERO)
        } else if (state == State.ACTIVE) {
            Global.getSoundPlayer().playSound(spec.deactivateSound, 1f, 1f, ship.location, Misc.ZERO)
        }
    }

    override fun onFinished() {
        super.onFinished()

        //val spec = getSpec()
        //Global.getSoundPlayer().playSound(spec.deactivateSound, 1f, 1f, ship.location, Misc.ZERO)
    }

    fun getSpec(): ShipSystemSpecAPI = Global.getSettings().getShipSystemSpec(shipSystemId)
    fun getSys(): MPC_triadShields = ReflectionUtilsTwo.get("system", this) as MPC_triadShields

    override fun advance(amount: Float, isPaused: Boolean) {
        super.advance(amount, isPaused)

        //getSys().effectLevel = effectLevel

        val spec = getSpec()
        if (state == State.ACTIVE && !isPaused) {
            Global.getSoundPlayer().playLoop(spec.loopSound, ship, 1f, 1f, ship.location, Misc.ZERO, 1f, 0f)
        }
    }

    override fun getFluxCostFlatOnActivation(): Float {
        return getSpec().fluxPerUse
    }

    override fun getFluxCostFlatPerSecondWhileActive(): Float {
        return getSpec().fluxPerSecond
    }

    override fun isHardFluxForActivation(): Boolean {
        return getSpec().generatesHardFlux()
    }

    override fun isHardFluxPerSecondWhileActive(): Boolean {
        return getSpec().generatesHardFlux()
    }
}