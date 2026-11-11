package eu.elraro.babytracker.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.elraro.babytracker.data.repository.BabyRepository
import eu.elraro.babytracker.data.repository.EventRepository
import eu.elraro.babytracker.domain.ChartDay
import eu.elraro.babytracker.domain.SleepChart
import eu.elraro.babytracker.domain.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SleepChartUiState(
    val loading: Boolean = true,
    /** Newest first; empty when there is no baby or nothing recorded yet. */
    val days: List<ChartDay> = emptyList(),
)

/** The day-by-day sleep chart: every day since the first record, read from the local database. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SleepChartViewModel
    @Inject
    constructor(
        babies: BabyRepository,
        events: EventRepository,
        time: TimeSource,
    ) : ViewModel() {
        val state: StateFlow<SleepChartUiState> =
            babies
                .observeAll()
                .map { it.firstOrNull() }
                .flatMapLatest { baby ->
                    if (baby == null) return@flatMapLatest flowOf(SleepChartUiState(loading = false))
                    val zone = time.zone()
                    val from = baby.dateOfBirth.atStartOfDay(zone).toInstant()
                    val to =
                        time
                            .now()
                            .atZone(zone)
                            .toLocalDate()
                            .plusDays(1)
                            .atStartOfDay(zone)
                            .toInstant()
                    events.observeForStats(baby.id, from, to).map { rows ->
                        val days = SleepChart.build(rows, baby.dateOfBirth, zone, time.now())
                        SleepChartUiState(loading = false, days = days.asReversed())
                    }
                }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SleepChartUiState())

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
