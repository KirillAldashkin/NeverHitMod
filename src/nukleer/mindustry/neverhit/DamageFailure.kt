package nukleer.mindustry.neverhit

import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.math.geom.Vec2
import arc.util.Align
import arc.util.Log
import mindustry.gen.WorldLabel
import mindustry.graphics.Layer
import mindustry.graphics.Pal
import kotlin.experimental.or

class DamageFailure(private val victim: Locatable, private var attacker: Locatable?) {
    private var initVictim: Locatable = victim.freeze()
    private var initAttacker: Locatable? = attacker?.freeze()
    init {
        Log.info("Setting new damage ${initVictim.locate()} from ${initAttacker?.locate()}")
    }

    fun render() {
        renderOne(victim, initVictim, Config.victimText)

        if (initAttacker != null)
        renderOne(attacker!!, initAttacker!!, Config.attackerText)
    }

    fun update(victim: Locatable, attacker: Locatable?) {
        if (initVictim != victim || this.attacker != null) return

        this.attacker = attacker
        this.initAttacker = attacker?.freeze()
        Log.info("Updating damage ${initVictim.locate()} from new ${initAttacker?.locate()}")
    }
}

private fun renderOne(now: Locatable, init: Locatable, text: String) {
    val now = now.locate()
    val init = init.locate()
    val delta = Vec2(now.x - init.x, now.y - init.y)
    val pathLen = delta.len() - Config.arrowStep
    delta.setLength(Config.arrowStep)

    WorldLabel.drawAt(text, init.x, init.y, Layer.overlayUI,
        (WorldLabel.flagBackground or WorldLabel.flagOutline).toInt(),
        1f, Align.center, Align.center)

    var path = Config.arrowStep
    while (path < pathLen) {
        val oldz = Draw.z()
        Draw.z(Layer.overlayUI - 0.001f)

        Draw.color(Pal.gray)
        Fill.poly(init.x, init.y, 3, Config.arrowStep / 2, delta.angle())
        Draw.color(Pal.accent)
        Fill.poly(init.x, init.y, 3, Config.arrowStep / 2 - 0.5f, delta.angle())
        Draw.color()

        Draw.z(oldz)

        init.add(delta)
        path += Config.arrowStep
    }
}

fun DamageFailure?.update(victim: Locatable, attacker: Locatable?): DamageFailure {
    if (this == null) return DamageFailure(victim, attacker)

    this.update(victim, attacker)
    return this
}
