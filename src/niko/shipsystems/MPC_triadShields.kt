package niko.shipsystems

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import com.fs.starfarer.api.graphics.SpriteAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript.StatusData
import com.fs.starfarer.api.util.FaderUtil
import com.fs.starfarer.api.util.FlickerUtilV2
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.combat.entities.DamagingExplosion
import com.fs.starfarer.combat.entities.MovingRay
import com.fs.starfarer.combat.entities.Ship
import niko.ReflectionUtilsTwo
import niko.niko_MPC_reflectionUtils
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.ext.rotateAroundPivot
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MPC_triadShields: BaseShipSystemScript(), DamageTakenModifier {

    companion object {
        var SIDE_LENGTH: Float = 20f
        const val INSIDE_ALPHA: Float = 0.25f
        const val SHIELD_RADIUS = 20f
        const val SHIELD_SIZE_INVERSE = 1f
        const val BEAM_DAMAGE_TAKEN_MULT = 0.75f
        const val EXPLOSION_DAMAGE_TAKEN_MULT = 0.4f

        val sizesToStrength = hashMapOf<ShipAPI.HullSize, Float>(
            Pair(ShipAPI.HullSize.FRIGATE, 250f),
            Pair(ShipAPI.HullSize.DESTROYER, 400f),
            Pair(ShipAPI.HullSize.CRUISER, 500f),
            Pair(ShipAPI.HullSize.CAPITAL_SHIP, 600f),
            Pair(ShipAPI.HullSize.FIGHTER, 20f),
        )
        val sizesToDiss = hashMapOf<ShipAPI.HullSize, Float>(
            Pair(ShipAPI.HullSize.FRIGATE, 40f),
            Pair(ShipAPI.HullSize.DESTROYER, 80f),
            Pair(ShipAPI.HullSize.CRUISER, 150f),
            Pair(ShipAPI.HullSize.CAPITAL_SHIP, 200f),
            Pair(ShipAPI.HullSize.FIGHTER, 5f),
        )
    }

    class ShieldPieceConnection(var from: ShieldPiece, var to: ShieldPiece) {
        var baseLength: Float = 0f

        init {
            baseLength = Misc.getDistance(from.offset, to.offset)
            baseLength *= 0.9f + Math.random().toFloat() * 0.2f
        }

        fun advance(amount: Float) {
            val fLoc = from.adjustedOffset
            val tLoc = to.adjustedOffset
            val length = Misc.getDistance(fLoc, tLoc)
            val diff = length - baseLength

            val k = 1f
            val accel = diff * k

            val dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(fLoc, tLoc))

            dir.scale(accel * amount)
            Vector2f.add(from.vel, dir, from.vel)
            dir.negate()
            Vector2f.add(to.vel, dir, to.vel)


            val maxOff = 20f
            from.off.x += from.vel.x * amount
            from.off.y += from.vel.y * amount
            if (from.off.length() > maxOff) from.off.scale(maxOff / from.off.length())

            to.off.x += to.vel.x * amount
            to.off.y += to.vel.y * amount
            if (to.off.length() > maxOff) to.off.scale(maxOff / to.off.length())
        }
    }

    class ShieldPiece(var ship: ShipAPI, val system: MPC_triadShields, upsideDown: Boolean, x: Float, y: Float, var side: Float) {
        var drone: ShipAPI? = null

        val interval = IntervalUtil(1f, 1f)

        var offset: Vector2f = Vector2f()

        var off: Vector2f = Vector2f() // secondary offset due to movement of individual triangles
        var vel: Vector2f = Vector2f()

        var sprite: SpriteAPI
        var upsideDown: Boolean = false

        var p1: Vector2f? = null
        var p2: Vector2f? = null
        var p3: Vector2f? = null
        var baseAlphaMult: Float = 1f
        var p1Alpha: Float = 1f
        var p2Alpha: Float = 1f
        var p3Alpha: Float = 1f

        var fader: FaderUtil
        var flicker: FlickerUtilV2?
        var forceAdvance = false

        init {

            offset.set(x, y)
            this.upsideDown = upsideDown

            fader = FaderUtil(0f, 0.25f, 0.25f)
            fader.brightness = Math.random().toFloat() * 1f
            fader.setBounce(true, true)
            fader.fadeIn()

            flicker = FlickerUtilV2()


            //sprite = Global.getSettings().getSprite("misc", "fx_shield_piece");
            sprite = Global.getSettings().getSprite("graphics/hud/line8x8.png")

            //sprite = Global.getSettings().getSprite("graphics/hud/line32x32.png");

            // updside down means the flat side is on the left
            // p1 is always the lone point, p2->p3 the flat side on the left/right
            // triangles are arranged as if ship is pointed at a 0 degree angle, i.e. facing right
            val height = (side * sqrt(3.0) / 2f).toFloat()
            if (upsideDown) {
                p1 = Vector2f(x + height / 2f, y)
                p2 = Vector2f(x - height / 2f - 1, y - side / 2f)
                p3 = Vector2f(x - height / 2f - 1, y + side / 2f)
            } else {
                p1 = Vector2f(x - height / 2f, y)
                p2 = Vector2f(x + height / 2f, y - side / 2f)
                p3 = Vector2f(x + height / 2f, y + side / 2f)
            }

            updatePointAlpha()

            if (!isUseless()) {

                val fleetManager = Global.getCombatEngine().getFleetManager(ship.owner)
                val old = fleetManager.isSuppressDeploymentMessages
                fleetManager.isSuppressDeploymentMessages = true

                drone = Global.getCombatEngine().createFXDrone(Global.getSettings().getVariant("MPC_triadDrone_shield"))
                val notNullDrone = drone!!
                //drone = fleetManager.spawnShipOrWing("MPC_triadDrone_shield", Vector2f(999999f, 999999f), 0f)
                //drone.addTag(Tags.VARIANT_FX_DRONE)
                //ReflectionUtilsTwo.set("id", drone, "")
                notNullDrone.setShield(ShieldAPI.ShieldType.OMNI, 0f, ship.shield?.fluxPerPointOfDamage ?: 1f, 360f)
                notNullDrone.collisionClass = CollisionClass.FIGHTER
                notNullDrone.shipAI = null
                notNullDrone.aiFlags.setFlag(ShipwideAIFlags.AIFlags.KEEP_SHIELDS_ON)
                notNullDrone.location.set(999999f, 999999f)
                notNullDrone.collisionRadius = SHIELD_RADIUS * 1.1f
                notNullDrone.shield.radius = SHIELD_RADIUS
                ship.isAlly = ship.isAlly
                ship.isHoldFire = true

                notNullDrone.mutableStats.fluxCapacity.modifyFlat("MPC_triadShield_${this}", sizesToStrength[ship.hullSize] ?: 50f)
                notNullDrone.mutableStats.fluxDissipation.modifyFlat("MPC_triadShield_${this}", sizesToStrength[ship.hullSize] ?: 10f)
                notNullDrone.mutableStats.overloadTimeMod.modifyMult("MPC_triadShield_${this}", 1.5f)
                notNullDrone.mutableStats.hardFluxDissipationFraction.modifyFlat("MPC_triadShield_${this}", 0.1f)
                notNullDrone.mutableStats.engineDamageTakenMult.modifyMult("MPC_triadShield_${this}", 0f)
                notNullDrone.mutableStats.dynamic.getStat(Stats.SHIELD_PIERCED_MULT).modifyMult("MPC_triadShield_${this}", 0f)
                notNullDrone.shieldCenterEvenIfNoShield.set(0f, 0f)
                notNullDrone.hullSize = ShipAPI.HullSize.FIGHTER
                niko_MPC_reflectionUtils.invoke("updateFluxValuesFromStats", notNullDrone.fluxTracker)
                for (layer in notNullDrone.activeLayers.toMutableSet()) {
                    notNullDrone.activeLayers.remove(layer)
                }
                notNullDrone.extraAlphaMult2 = 0f

                fleetManager.isSuppressDeploymentMessages = old
                /*for (seg in drone.exactBounds.segments) {
                seg.p1.scale(1.1f)
                seg.p2.scale(1.1f)
            }*/
                notNullDrone.exactBounds.clear()
                notNullDrone.exactBounds.addSegment(p1!!.x, p1!!.y)
                notNullDrone.exactBounds.addSegment(p2!!.x, p2!!.y)
                notNullDrone.exactBounds.addSegment(p3!!.x, p3!!.y)
            }
        }

        fun checkCollision(loc: Vector2f, entity: DamagingProjectileAPI? = null): Boolean {
            val realLoc = getRealLoc()

            if (getShieldStrength() <= 0.01f) return false

            if (entity is DamagingExplosion) {
                val radius = entity.explosionSpecIfExplosion.radius
                val dist = MathUtils.getDistance(realLoc, loc)
                if (dist <= radius) {
                    return true
                }
            }

            val colRadius = entity?.collisionRadius ?: 0f
            var dist = MathUtils.getDistance(realLoc, loc) - colRadius
            if (entity?.tailEnd != null && entity is MovingRay) {
                dist += (MathUtils.getDistance(entity.tailEnd, loc))
            }
            //val checkLoc = MathUtils.getPointOnCircumference(check.location, check.collisionRadius, VectorUtils.getAngle(check.location, realLoc))
            //if (!CollisionUtils.isPointWithinCollisionCircle(checkLoc, drone)) continue
            if (dist > drone!!.shield.radius) return false
            return true
        }

        fun isUseless(): Boolean {
            return (p1Alpha == 0f && p2Alpha == 0f && p3Alpha == 3f)
        }

        fun updatePointAlpha() {
            //if (true) return;
            val bounds = ship.exactBounds
            bounds.update(Vector2f(0f, 0f), 0f)
            p1Alpha = getPointAlpha(p1!!)
            p2Alpha = getPointAlpha(p2!!)
            p3Alpha = getPointAlpha(p3!!)

            //p1Alpha = 1f
            //p2Alpha = 1f
            //p3Alpha = 1f

            baseAlphaMult = max(p1Alpha, p2Alpha)
            baseAlphaMult = max(baseAlphaMult, p3Alpha)
            //			if (baseAlphaMult > 0) {
//				p1Alpha = p2Alpha = p3Alpha = 1f;
//			}
        }

        fun getPointAlpha(p: Vector2f): Float {
            val bounds = ship.exactBounds

            var minDist = Float.Companion.MAX_VALUE
            val boundsPoints: MutableList<Vector2f?> = ArrayList<Vector2f?>()
            for (segment in bounds.segments) {
                val n = Misc.closestPointOnSegmentToPoint(segment.p1, segment.p2, p)
                val dist = Misc.getDistance(n, p) * SHIELD_SIZE_INVERSE
                if (dist < minDist) minDist = dist

                boundsPoints.add(segment.p1)
            }
            boundsPoints.add(bounds.segments[bounds.segments.size - 1].p2)

            var minAlphaAt: Float = SIDE_LENGTH * 1f
            val minAlpha = 0f
            val inBounds = Misc.isPointInBounds(p, boundsPoints)
            if (inBounds) {
                minAlphaAt = 0f
            }

            if (minDist > minAlphaAt) {
                return minAlpha
            }


            return max(minAlpha, 1f - min(1f, minDist / (minAlphaAt * 2f)))


            //return Math.max(minAlpha, 1f - minDist / minAlphaAt);
        }

        val adjustedOffset: Vector2f
            get() = Vector2f.add(offset, off, Vector2f())

        val center: Vector2f
            get() {
                val result = Vector2f(offset)
                Misc.rotateAroundOrigin(result, ship.facing)
                Vector2f.add(ship.location, result, result)
                return result
            }

        fun advance(amount: Float) {

            fader.advance(amount * (0.5f + 0.5f * Math.random().toFloat()))
            if (drone == null) return
            val notNullDrone = drone!!
            ReflectionUtilsTwo.invoke("advance", notNullDrone.fluxTracker, amount)
            if (!notNullDrone.shield.isOn && !notNullDrone.fluxTracker.isOverloaded) {
                notNullDrone.shield.toggleOn()
            }
            notNullDrone.facing = ship.facing

            val engine = Global.getCombatEngine()

            repositionDrone()
            if (getShieldStrength() > 0f && !ship.isPhased) {
                val length = SHIELD_RADIUS
                var realLoc = getRealLoc()
                val objs = engine.projectiles.filter { (MathUtils.getDistance(realLoc, it.location) - it.collisionRadius) <= length }.iterator()
                while (objs.hasNext()) {
                    val check = objs.next()
                    if (check is DamagingProjectileAPI) {
                        if (check.source == ship) continue
                        if (check.customData["MPC_triadShieldsNOCHECK"] == true) continue

                        //if (!notNullDrone.shield.isWithinArc(check.location)) continue

                        if (!checkCollision(realLoc, check)) continue

                        /*val end = check.tailEnd
                        if (end != null) {
                            if (notNullDrone.checkCollisionVsRay(end, check) == null) {
                                continue
                            }
                        }*/

                        val asShip = drone as Ship
                        //val hitLoc = MathUtils.getPointOnCircumference(realLoc, length, VectorUtils.getAngle(realLoc, check.location))
                        //asShip.shield.shieldHit(hitLoc, 5f, false, 0f)
                        /*asShip.applyDamage(
                            hitLoc,
                            F
                        )*/
                        takeDamage(check.damage, check)
                        break
                    }
                }
            }
            /*if (reset) {
                notNullDrone.location.set(999999f, 999999f)
                forceAdvance = false
                notNullDrone.variant.addTag(Tags.VARIANT_FX_DRONE)
            }*/
        }

        fun takeDamage(damage: DamageAPI?, source: DamagingProjectileAPI? = null) {
            var damage = damage
            if (damage == null && source != null) damage = source.damage ?: return
            var realDamage = damage!!.damage
            realDamage *= when (damage!!.type) {
                DamageType.KINETIC -> 2f
                DamageType.HIGH_EXPLOSIVE -> 0.5f
                DamageType.FRAGMENTATION -> 0.25f
                DamageType.ENERGY -> 1f
                DamageType.OTHER -> 1f
            }
            val notNullDrone = drone!!
            val realLoc = getRealLoc()
            val result = ReflectionUtilsTwo.invoke("applyDamage", notNullDrone, realLoc, damage, true, realDamage, Any()) as ApplyDamageResultAPI
            source?.let { ReflectionUtilsTwo.invoke("notifyDealtDamage", it, realLoc, result, drone, ignoreParams = true) }

            val type = if (realDamage >= 250f) "solid" else if (realDamage >= 600f) "heavy" else "light"

            Global.getSoundPlayer().playSound(
                "hit_shield_${type}_gun",
                1.5f,
                1f,
                realLoc,
                Misc.ZERO
            )

            source?.setCustomData("MPC_triadShieldsNOCHECK", true)
            source?.let { Global.getCombatEngine().removeEntity(it) }
        }

        private fun repositionDrone() {
            drone?.location?.set(getRealLoc())
            drone?.variant?.removeTag(Tags.VARIANT_FX_DRONE)
            forceAdvance = true
        }

        fun getRealLoc(): Vector2f = Vector2f(ship.location).translate(offset.x, offset.y).rotateAroundPivot(ship.location,  ship.facing)

        fun getShieldStrength(): Float {
            if (drone == null || !drone!!.shield.isOn || drone!!.fluxTracker.isOverloaded) return 0f
            if (system.effectLevel <= 0.1f) return 0f
            return 1 - (drone!!.fluxLevel)
        }

        /**
         * Assumes translated to ship location and rotated, i.e. offset is the actual location to render at.
         * @param alphaMult
         */
        fun render(alphaMult: Float) {

            if (isUseless()) return

            var alphaMult = alphaMult
            var color = Color(255, 165, 100, 255)
            color = Color(100, 165, 255, 255)

            GL11.glPushMatrix()
            GL11.glTranslatef(off.x, off.y, 0f)

            for (i in 0..1) {
                val origW = 8f
                val origH = 8f

                GL11.glEnable(GL11.GL_TEXTURE_2D)
                sprite.bindTexture()
                sprite.setSize(origW * (20f / SIDE_LENGTH), origH * (20f / SIDE_LENGTH))
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)

                //				if (i == 0) {
//					GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
//				}

//				float sin = (float) Math.sin(Math.toRadians(30f));
//				float cos = (float) Math.sin(Math.toRadians(30f));
                var t = 9f
                var a = 0.5f * getSystemStatusAlphaMult()
                if (i == 1) {
                    t = 4f
                    a = 1f
                }

                val damageColor = Color.RED
                val strength = getShieldStrength()
                color = Misc.interpolateColor(color, damageColor, 1 - strength)


                //float in = (float) (Math.sin(Math.toRadians(30f)) * t);
                if (upsideDown) {
                    GL11.glBegin(GL11.GL_QUAD_STRIP)

                    Misc.setColor(color, alphaMult * p1Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p1!!.x, p1!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p1!!.x - t, p1!!.y)

                    Misc.setColor(color, alphaMult * p2Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p2!!.x, p2!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p2!!.x + t * 0.5f, p2!!.y + t)

                    Misc.setColor(color, alphaMult * p3Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p3!!.x, p3!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p3!!.x + t * 0.5f, p3!!.y - t)

                    Misc.setColor(color, alphaMult * p1Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p1!!.x, p1!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p1!!.x - t, p1!!.y)

                    GL11.glEnd()
                } else {
                    GL11.glBegin(GL11.GL_QUAD_STRIP)

                    Misc.setColor(color, alphaMult * p1Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p1!!.x, p1!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p1!!.x + t, p1!!.y)

                    Misc.setColor(color, alphaMult * p2Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p2!!.x, p2!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p2!!.x - t * 0.5f, p2!!.y + t)

                    Misc.setColor(color, alphaMult * p3Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p3!!.x, p3!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p3!!.x - t * 0.5f, p3!!.y - t)

                    Misc.setColor(color, alphaMult * p1Alpha * a * getShieldStrength())
                    GL11.glTexCoord2f(0f, 0f)
                    GL11.glVertex2f(p1!!.x, p1!!.y)
                    GL11.glTexCoord2f(0f, 1f)
                    GL11.glVertex2f(p1!!.x + t, p1!!.y)

                    GL11.glEnd()
                }
            }

            GL11.glPopMatrix()
            return
        }

        private fun getSystemStatusAlphaMult(): Float {
            return (system.effectLevel)
        }
    }

    class TriadShieldVisuals(var ship: ShipAPI, var script: MPC_triadShields) :
        com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin() {
        var pieces: MutableList<ShieldPiece> = ArrayList<ShieldPiece>()
        var connections: MutableList<ShieldPieceConnection> = ArrayList<ShieldPieceConnection>()

        init {
            addShieldPieces()
        }

        fun addShieldPieces() {

            SIDE_LENGTH = 20f

            pieces.clear()
            //SIDE_LENGTH = 10f;
            //SIDE_LENGTH = 120f;
            val side: Float = SIDE_LENGTH
            val height = (side * sqrt(3.0) / 2f).toFloat()
            val centerFromBottom = (sin(Math.toRadians(30.0)) * height).toFloat()
            //centerFromBottom = side/2f;
            var gridHeight = (ship.collisionRadius / side).toInt() * 2
            if (gridHeight / 2 != 0) gridHeight++
            if (gridHeight < 6) gridHeight = 6
            var gridWidth = (ship.collisionRadius / height).toInt() * 2
            if (gridWidth / 2 != 0) gridWidth++
            if (gridWidth < 6) gridWidth = 6
            for (i in -gridWidth / 2..<gridWidth / 2) {
                for (j in -gridHeight / 2..<gridHeight / 2) {

                    val lowX = i * height + height / 2f
                    val highX = (i + 1) * height + height / 2f
                    var centerY = j * side + side / 2f
                    var piece = ShieldPiece(ship, script,true, lowX + centerFromBottom, centerY, side - 2f)
                    if (piece.baseAlphaMult > 0) {
                        pieces.add(piece)
                    }

                    if (j != gridHeight / 2 - 1) {
                        centerY += side / 2f
                        piece = ShieldPiece(ship, script,false, highX - centerFromBottom, centerY, side - 2f)
                        if (piece.baseAlphaMult > 0) {
                            pieces.add(piece)
                        }
                    }
                }
            }

            val maxDist: Float = SIDE_LENGTH * 1.2f
            for (i in 0..<pieces.size - 1) {
                val curr = pieces.get(i)
                for (j in i + 1..<pieces.size) {
                    val other = pieces.get(j)
                    if (curr === other) continue
                    if (Misc.getDistance(curr.offset, other.offset) > maxDist) continue

                    val conn = ShieldPieceConnection(curr, other)
                    connections.add(conn)
                }
            }
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers?> {
            return EnumSet.of<CombatEngineLayers?>(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER)
        }

        override fun isExpired(): Boolean {
            return false
        }

        override fun getRenderRadius(): Float {
            return ship.collisionRadius + 100f
        }

        override fun advance(amount: Float) {
            entity.location.set(ship.location)
            if (Global.getCombatEngine().isPaused) return

            for (piece in pieces) {
                piece.advance(amount)
            }

            for (conn in connections) {
                conn.advance(amount)
            }
        }

        override fun render(layer: CombatEngineLayers?, viewport: ViewportAPI) {
            var alphaMult = viewport.alphaMult

            var system = ship.phaseCloak
            if (system == null) system = ship.system
            if (ship.isDoNotRenderShield) return
            alphaMult *= system.effectLevel

            alphaMult *= ship.alphaMult
            alphaMult *= ship.extraAlphaMult2
            alphaMult *= ship.extraAlphaMult

            if (alphaMult <= 0f) return

            GL11.glPushMatrix()
            GL11.glTranslatef(ship.location.x, ship.location.y, 0f)
            GL11.glRotatef(ship.facing, 0f, 0f, 1f)

            for (piece in pieces) {
                piece.render(alphaMult)
            }
            GL11.glPopMatrix()


//			Color color = Color.red;
//			color = Misc.scaleAlpha(color, 0.5f);
//
//			float x = ship.getLocation().x - ship.getCollisionRadius() * 0.5f;
//			float y = ship.getLocation().y - ship.getCollisionRadius() * 0.5f;
//			float w = ship.getCollisionRadius();
//			float h = ship.getCollisionRadius();
//
//			GL11.glDisable(GL11.GL_TEXTURE_2D);
//			GL11.glEnable(GL11.GL_BLEND);
//			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
//
//
//			GL11.glColor4ub((byte)color.getRed(),
//							(byte)color.getGreen(),
//							(byte)color.getBlue(),
//							(byte)(color.getAlpha() * alphaMult));
//
//			GL11.glBegin(GL11.GL_QUADS);
//			{
//				GL11.glVertex2f(x, y);
//				GL11.glVertex2f(x, y + h);
//				GL11.glVertex2f(x + w, y + h);
//				GL11.glVertex2f(x + w, y);
//			}
//			GL11.glEnd();
        }
    }

    protected var visuals: TriadShieldVisuals? = null
    var effectLevel = 1f

    override fun apply(
        stats: MutableShipStatsAPI,
        id: String?,
        state: ShipSystemStatsScript.State?,
        effectLevel: Float
    ) {
        this.effectLevel = effectLevel

        var id = id
        var ship: ShipAPI? = null
        var player = false
        if (stats.entity is ShipAPI) {
            ship = stats.entity as ShipAPI?
            player = ship === Global.getCombatEngine().playerShip
            id = id + "_" + ship!!.id
        } else {
            return
        }

        if (!ship.hasListener(this)) {
            ship.addListener(this)
        }

        if (visuals == null) {
            visuals = TriadShieldVisuals(ship, this)
            Global.getCombatEngine().addLayeredRenderingPlugin(visuals)
            ship.addListener(this)
        }

        if (Global.getCombatEngine().isPaused) {
            return
        }

        if (state == ShipSystemStatsScript.State.COOLDOWN || state == ShipSystemStatsScript.State.IDLE) {
            unapply(stats, id)
            return
        }

        var system = ship.phaseCloak
        if (system == null) system = ship.system


        if (state == ShipSystemStatsScript.State.IN || state == ShipSystemStatsScript.State.ACTIVE) {
        } else if (state == ShipSystemStatsScript.State.OUT) {
        }
    }


    override fun unapply(stats: MutableShipStatsAPI, id: String?) {
        var id = id
        var ship: ShipAPI? = null
        var player = false
        if (stats.entity is ShipAPI) {
            ship = stats.entity as ShipAPI?
            player = ship === Global.getCombatEngine().playerShip
            id = id + "_" + ship!!.id
        } else {
            return
        }
    }

    override fun getStatusData(index: Int, state: ShipSystemStatsScript.State?, effectLevel: Float): StatusData? {
        return null
    }
    override fun modifyDamageTaken(
        param: Any?,
        target: CombatEntityAPI?,
        damage: DamageAPI?,
        point: Vector2f?,
        shieldHit: Boolean
    ): String? {

        if (point == null) return null
        if (damage == null) return null
        if (shieldHit) return null
        if (param is DamagingExplosion) return blockExplosion(param, point, damage)
        if (param is BeamAPI) return blockBeam(param, point, damage)

        return null
    }

    private fun blockExplosion(
        exploson: DamagingExplosion,
        point: Vector2f,
        damage: DamageAPI
    ): String? {

        var blocked = false
        val visuals = visuals ?: return null
        val oldDmg = damage.baseDamage
        for (piece in visuals.pieces) {
            if (!piece.checkCollision(point, exploson)) continue

            val loc = piece.getRealLoc()
            val rad = exploson.explosionSpecIfExplosion.radius
            val core = exploson.explosionSpecIfExplosion.coreRadius
            val maxDmg = exploson.explosionSpecIfExplosion.maxDamage
            val minDmg = exploson.explosionSpecIfExplosion.minDamage

            var dist = MathUtils.getDistance(loc, point)
            if (dist <= core) {
                damage.damage = damage.damage.coerceAtLeast(maxDmg)
            } else {
                val diff = rad - core
                dist -= core

                // todo test
                val newDamage = (maxDmg * (dist / diff)).coerceAtLeast(minDmg)
                damage.damage = newDamage
            }

            damage.modifier.modifyMult("MPC_triadShieldsExplosionResist", EXPLOSION_DAMAGE_TAKEN_MULT)
            piece.takeDamage(damage)
            damage.modifier.unmodify("MPC_triadShieldsExplosionResist")
            damage.damage = oldDmg

            blocked = true
        }

        if (blocked) {
            damage?.modifier?.modifyMult("MPC_triadBlocked", 0f)
            return "MPC_triadBlocked"
        }
        return null
    }

    private fun blockBeam(
        beam: BeamAPI,
        point: Vector2f,
        damage: DamageAPI
    ): String? {

        var blocked = false
        val visuals = visuals ?: return null
        val to = beam.to
        val origDamage = damage.damage
        for (piece in visuals.pieces) {
            // eh, good enough
            if (!piece.checkCollision(to)) continue

            damage.modifier?.modifyMult("MPC_triadBlockedShield", 1 - BEAM_DAMAGE_TAKEN_MULT)
            piece.takeDamage(damage)
            damage.modifier?.unmodify("MPC_triadBlockedShield")

            blocked = true
        }

        if (blocked) {
            damage.modifier?.modifyMult("MPC_triadBlocked", BEAM_DAMAGE_TAKEN_MULT)
            return "MPC_triadBlocked"
        }

        return null
    }

}