package com.orangepet.app.ai

import com.orangepet.app.behavior.GreetingPeriod
import kotlin.random.Random

/**
 * Default [PetBrain]: no network, no API key, no failure mode. Used
 * whenever no Gemini API key is configured, and as the fallback whenever
 * [GeminiPetBrain] fails for any reason (see that class for the full list
 * of failure cases it catches).
 */
class OfflinePetBrain(private val random: Random = Random.Default) : PetBrain {

    override suspend fun createGreeting(userName: String, period: GreetingPeriod): String {
        val name = userName.ifBlank { "friend" }
        val options = when (period) {
            GreetingPeriod.MORNING -> listOf(
                "Good morning, $name! Hope you have a lovely day.",
                "Morning, $name! Ready to take on the day?",
                "Rise and shine, $name!"
            )
            GreetingPeriod.LUNCH -> listOf(
                "Lunch time! Remember to take a short break, $name.",
                "Hungry? Might be a good time for a bite, $name.",
                "Midday check-in: don't forget to eat, $name!"
            )
            GreetingPeriod.NIGHT -> listOf(
                "Good night, $name. See you in the morning!",
                "Time to rest, $name. Sleep well!",
                "Night night, $name — I'll be right here."
            )
        }
        return options[random.nextInt(options.size)]
    }
}
