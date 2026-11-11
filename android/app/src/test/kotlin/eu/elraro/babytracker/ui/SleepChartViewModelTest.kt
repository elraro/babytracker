package eu.elraro.babytracker.ui

import eu.elraro.babytracker.FakeTime
import eu.elraro.babytracker.TestBackend
import eu.elraro.babytracker.data.repository.BabyRepository
import eu.elraro.babytracker.data.repository.EventRepository
import eu.elraro.babytracker.ui.stats.SleepChartUiState
import eu.elraro.babytracker.ui.stats.SleepChartViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SleepChartViewModelTest {
    private val backend = TestBackend()
    private val db = backend.database

    // 10:15 on 8 October 2026 in Madrid.
    private val time = FakeTime(now = Instant.parse("2026-10-08T08:15:00Z"))
    private val babies = BabyRepository(db, time) {}
    private val events = EventRepository(db, time) {}
    private lateinit var vm: SleepChartViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        vm = SleepChartViewModel(babies, events, time)
        runBlocking { backend.signedIn() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        backend.close()
    }

    private suspend fun await(predicate: (SleepChartUiState) -> Boolean): SleepChartUiState =
        withTimeout(10_000) { vm.state.first(predicate) }

    @Test
    fun `the chart follows what is recorded on the phone, newest day first`() =
        runBlocking {
            assertTrue(await { !it.loading }.days.isEmpty())
            val baby = babies.create("Vera", LocalDate.of(2026, 7, 4))
            assertTrue(await { !it.loading }.days.isEmpty())

            events.logBottle(baby.id, 120)
            time.advance(Duration.ofHours(1))
            events.startSleep(baby.id)
            time.advance(Duration.ofMinutes(45))
            events.endSleep(baby.id)

            val days = await { state -> state.days.any { it.sleepS > 0 } }.days
            assertEquals(LocalDate.of(2026, 10, 8), days.first().date)
            assertEquals(45 * 60, days.first().sleepS)
            assertEquals(listOf(120), days.map { it.bottleMl })
            assertEquals(3, days.first().ageMonths)
        }
}
