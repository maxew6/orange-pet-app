package com.orangepet.app.ai

import com.orangepet.app.behavior.GreetingPeriod
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePetBrainTest {

    @Test
    fun createGreeting_morning_mentionsProvidedName() = runBlocking {
        val greeting = OfflinePetBrain().createGreeting("Mahesh", GreetingPeriod.MORNING)
        assertTrue(greeting.contains("Mahesh"))
    }

    @Test
    fun createGreeting_blankName_fallsBackToFriend() = runBlocking {
        val greeting = OfflinePetBrain().createGreeting("", GreetingPeriod.NIGHT)
        assertTrue(greeting.contains("friend"))
    }

    @Test
    fun createGreeting_neverBlank_forAnyPeriod() = runBlocking {
        for (period in GreetingPeriod.entries) {
            val greeting = OfflinePetBrain().createGreeting("Sam", period)
            assertTrue(greeting.isNotBlank())
        }
    }
}
