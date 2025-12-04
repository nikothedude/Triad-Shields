package niko_TS.subsystems

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemSpecAPI
import com.fs.starfarer.api.util.Misc
import niko_TS.TS_triadShieldsAICore
import niko_TS.niko_TS_reflectionUtils
import niko_TS.shipsystems.TS_triadShields
import org.magiclib.subsystems.MagicShipSystemSubsystem

class TS_triadShieldSubsystem(ship: ShipAPI): MagicShipSystemSubsystem(ship) {

    lateinit var ai: TS_triadShieldsAICore

    override fun init() {
        super.init()
        ai = TS_triadShieldsAICore(ship, getSys())
    }

    override fun hasCharges(): Boolean {
        return false
    }

    override fun getShipSystemId(): String {
        return "TS_triadShields"
    }

    override fun shouldActivateAI(amount: Float): Boolean {
        if (state == State.ACTIVE || state == State.IN) {
            return ai?.getCommand() == TS_triadShieldsAICore.Command.DEACTIVATE
        }
        if (state == State.READY || state == State.OUT) {
            return ai?.getCommand() == TS_triadShieldsAICore.Command.ACTIVATE
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
    fun getSys(): TS_triadShields = niko_TS_reflectionUtils.get("system", this, MagicShipSystemSubsystem::class.java) as TS_triadShields

    override fun onShipDeath() {
        super.onShipDeath()

        if (state != State.OUT && state != State.READY && state != State.COOLDOWN) {
            setState(State.OUT)
        }
    }

    override fun advance(amount: Float, isPaused: Boolean) {
        super.advance(amount, isPaused)

        //getSys().effectLevel = effectLevel

        val spec = getSpec()
        if (state == State.ACTIVE && !isPaused) {
            Global.getSoundPlayer().playLoop(spec.loopSound, ship, 1f, 1f, ship.location, Misc.ZERO, 1f, 0f)
        }

        if (ship.fluxTracker.isVenting || ship.fluxTracker.isOverloaded) {
            if (state != State.OUT && state != State.READY && state != State.COOLDOWN) {
                setState(State.OUT)
            }
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