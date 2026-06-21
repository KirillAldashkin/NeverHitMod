package nukleer.mindustry.neverhit

import arc.Core
import arc.util.Time
import mindustry.Vars
import mindustry.content.TechTree
import mindustry.core.GameState
import mindustry.ctype.UnlockableContent
import mindustry.game.EventType
import mindustry.gen.*
import mindustry.type.Planet
import mindustry.world.blocks.defense.ForceProjector

@Suppress("unused")
class NeverHitMod : mindustry.mod.Mod() {
    private var damaged: DamageFailure? = null

    init {
        // BIG warning on startup
        arc.Events.on(EventType.ClientLoadEvent::class.java) {
            Vars.ui.showText("NoHitMod", Config.loadWarning)
        }

        // store obvious damage sources to display them nicely
        arc.Events.on(EventType.UnitDamageEvent::class.java) {
            if (it.unit.team != Config.targetTeam) return@on
            playerLost(it.unit.locatable(), (it.bullet?.owner as? Posc)?.locatable())
        }
        arc.Events.on(EventType.BuildDamageEvent::class.java) {
            if (it.build.team != Config.targetTeam) return@on
            playerLost(it.build.locatable(), (it.source?.owner as? Posc)?.locatable())
        }
        arc.Events.on(EventType.UnitBulletDestroyEvent::class.java) {
            if (it.unit.team != Config.targetTeam) return@on
            playerLost(it.unit.locatable(), (it.bullet?.owner as? Posc)?.locatable())
        }
        arc.Events.on(EventType.BuildingBulletDestroyEvent::class.java) {
            if (it.build.team != Config.targetTeam) return@on
            playerLost(it.build.locatable(), (it.bullet?.owner as? Posc)?.locatable())
        }
        arc.Events.on(EventType.UnitDrownEvent::class.java) {
            if (it.unit.team != Config.targetTeam) return@on
            playerLost(it.unit.locatable(), null)
        }

        // Check for obscure damage from mods with bruteforce
        arc.Events.run(EventType.Trigger.update) {
            Groups.unit.forEach { verifyEntity(it) }
            Vars.world.tiles.forEach { verifyEntity(it.build) }
        }

        // reset logoff timer when user closed the game themselves
        arc.Events.on(EventType.StateChangeEvent::class.java) {
            if (it.to != GameState.State.menu) return@on
            synchronized(this) {
                if (damaged == null) return@on
                damaged = null
            }
            eraseCampaign()
        }

        arc.Events.run(EventType.Trigger.draw) {
            if (Vars.state.state != GameState.State.playing) return@run
            damaged?.render()
        }
    }

    private fun <T>verifyEntity(x: T?) where T: Teamc, T: Healthc, T: Posc {
        if (x == null || x.team() != Config.targetTeam) return

        // if HP is not full, then damage was taken
        if (x.healthf() < 1) playerLost(x.locatable(), null)

        // disable any damage reduction
        if (x is Shieldc) {
            x.shield(0f)
            x.armor(0f)
        }

        // if shield is heated, then it took damage
        if (x is ForceProjector.ForceBuild && x.buildup > 0) playerLost(x.locatable(), null)
    }

    private fun playerLost(victim: Locatable, attacker: Locatable?) {
        // don't lose campaign in custom maps
        if (Vars.control.saves.current?.isSector != true) return

        synchronized(this) {
            val new = damaged == null
            damaged = damaged.update(victim, attacker)
            if (!new) return
        }

        Vars.ui.announce(Config.loseAnnounce, Config.loseDelay * Time.toSeconds)

        Time.run(Config.loseDelay * Time.toSeconds) {
            synchronized(this) {
                if (damaged == null) return@run
                damaged = null
            }
            Vars.state.set(GameState.State.menu)
            eraseCampaign()
        }
    }

    private fun eraseCampaign() {
        // resource loadout stats
        Vars.universe.clearLoadoutInfo()
        // tech tree
        for (node in TechTree.all) {
            node.reset()
        }
        Vars.content.each {
            // unlocked content
            if (it is UnlockableContent) it.clearUnlock()

            // planet progress
            if (it is Planet) {
                it.clearStats()
                var any = false
                for (sec in it.sectors) {
                    sec.clearInfo()
                    if (sec.save == null) continue
                    any = true
                    sec.save.delete()
                    sec.save = null
                }
                if (any) it.reloadMeshAsync()
            }
        }

        // sector maps
        for (slot in Vars.control.saves.saveSlots.copy()) {
            if (slot.isSector) slot.delete()
        }

        // make user select campaign again
        Core.settings.put("campaignselect", false)
        // I didn't want to close the game, but it doesn't allow to start a new campaign without this
        Core.app.exit()
    }
}