package nukleer.mindustry.neverhit

import mindustry.game.Team

class Config {
    companion object {
        // How to lose
        val targetTeam: Team = Team.sharded
        val loseDelay: Float = 30f
        val loseAnnounce: String = "Получен урон. Кампания провалена"
        val loadWarning: String = "Будьте осторожны! Малейшая ошибка - и ВСЯ ваша кампания будет поджарена Испепелителем!"

        // Render
        val victimText: String = "Где получен"
        val attackerText: String = "Откуда получен"
        val arrowStep: Float = 4f
    }
}