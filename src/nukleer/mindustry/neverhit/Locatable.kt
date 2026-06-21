package nukleer.mindustry.neverhit

import arc.math.geom.Vec2
import mindustry.gen.Healthc
import mindustry.gen.Posc

interface Locatable {
    fun locate(): Vec2
    fun freeze(): Locatable {
        val vec = locate()
        return VecLocatable(vec.x, vec.y)
    }
}

fun Posc.locatable(): Locatable = EntityLocatable(this)

private data class VecLocatable(val x: Float, val y: Float) : Locatable {
    override fun locate(): Vec2 = Vec2(x, y)
    override fun freeze(): Locatable = this
}

private class EntityLocatable(init: Posc) : Locatable {
    private var source: Posc? = init
    private var lastPos: Vec2 = Vec2(init.x, init.y)

    override fun locate(): Vec2 {
        val source = source
        if ((source as? Healthc)?.dead() == true) {
            // if mortal and dead, then remove for cleanup
            this.source = null
        } else if (source != null) {
            // if not dead, update pos
            lastPos = Vec2(source.x, source.y)
        }
        return lastPos
    }

    override fun equals(other: Any?): Boolean {
        return other is EntityLocatable && (source == other.source)
    }

    override fun hashCode(): Int = source?.hashCode() ?: 0
}