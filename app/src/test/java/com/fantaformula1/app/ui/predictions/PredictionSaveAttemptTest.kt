package com.fantaformula1.app.ui.predictions

import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.core.domain.RaceStatus
import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.data.repository.AuthRepository
import com.fantaformula1.app.data.repository.AuthState
import com.fantaformula1.app.data.repository.League
import com.fantaformula1.app.data.repository.LeagueDataRepository
import com.fantaformula1.app.data.repository.LeagueDataSnapshot
import com.fantaformula1.app.data.repository.LeagueMember
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.MemberRole
import com.fantaformula1.app.data.repository.PredictionRepository
import com.fantaformula1.app.data.repository.PredictionSaveOutcome
import com.fantaformula1.app.data.repository.RaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parte B: un fallimento di validazione incrementa il contatore usato dalla
 * schermata per riaprire le sezioni; un salvataggio riuscito non lo tocca.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PredictionSaveAttemptTest {

    @Test
    fun `fallimento di validazione incrementa il contatore e il successo non lo cambia`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val race = Race(
                id = "race-1",
                seasonId = "season-1",
                leagueId = "league-1",
                round = 1,
                name = "Gara test",
                status = RaceStatus.OPEN,
            )
            val rules = Rules.default().copy(enabledTypes = setOf(PredictionType.WINNER))
            val league = League(
                id = "league-1",
                name = "Lega test",
                inviteCode = "ABC123",
                rules = rules,
                createdBy = null,
            )
            val viewModel = PredictionViewModel(
                raceRepository = FakeRaceRepository(race),
                leagueRepository = FakeLeagueRepository(league),
                predictionRepository = FakePredictionRepository(),
                authRepository = FakeAuthRepository(),
                leagueDataRepository = FakeLeagueDataRepository(),
                seasonResultsStore = null,
                raceId = race.id,
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.canSubmit)

            viewModel.save()
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.saveAttemptFailed)
            assertTrue(viewModel.uiState.value.errors.isNotEmpty())

            viewModel.setDriverAt(PredictionType.WINNER, 0, "VER")
            advanceUntilIdle()
            viewModel.save()
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.saveAttemptFailed)
            assertTrue(viewModel.uiState.value.saved)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class FakeRaceRepository(private val race: Race) : RaceRepository {
    override suspend fun races(leagueId: String): List<Race> = listOf(race)
    override suspend fun race(raceId: String): Race? = race.takeIf { it.id == raceId }
    override suspend fun createRace(
        leagueId: String,
        name: String,
        round: Int,
        country: String?,
        isSprint: Boolean,
        deadline: String?,
    ): Race = throw UnsupportedOperationException()

    override suspend fun updateRace(
        raceId: String,
        name: String,
        round: Int,
        country: String?,
        isSprint: Boolean,
        deadline: String?,
    ) = throw UnsupportedOperationException()

    override suspend fun deleteRace(raceId: String) = throw UnsupportedOperationException()
    override suspend fun result(raceId: String): RaceResultData? = null
    override suspend fun saveResult(raceId: String, leagueId: String, result: RaceResultData) =
        throw UnsupportedOperationException()
}

private class FakeLeagueRepository(private val league: League) : LeagueRepository {
    override suspend fun myLeagues(): List<League> = listOf(league)
    override suspend fun createLeague(name: String, rules: Rules): League =
        throw UnsupportedOperationException()

    override suspend fun joinByCode(code: String): String = throw UnsupportedOperationException()
    override suspend fun league(leagueId: String): League? = league.takeIf { it.id == leagueId }
    override suspend fun updateRules(leagueId: String, rules: Rules) =
        throw UnsupportedOperationException()

    override suspend fun members(leagueId: String): List<LeagueMember> = emptyList()
    override suspend fun setMemberRole(leagueId: String, userId: String, role: MemberRole) =
        throw UnsupportedOperationException()

    override suspend fun removeMember(leagueId: String, userId: String) =
        throw UnsupportedOperationException()

    override suspend fun leaveLeague(leagueId: String) = throw UnsupportedOperationException()
    override suspend fun isAdmin(leagueId: String): Boolean = false
}

private class FakePredictionRepository : PredictionRepository {
    override suspend fun myPrediction(raceId: String): RacePrediction? = null
    override suspend fun myPredictions(leagueId: String): Map<String, RacePrediction> = emptyMap()
    override suspend fun predictionCount(raceId: String): Int? = null
    override suspend fun othersPredictions(raceId: String): Map<String, RacePrediction> = emptyMap()
    override suspend fun savePrediction(
        raceId: String,
        leagueId: String,
        prediction: RacePrediction,
        expectedUserId: String?,
        expectedRevision: Long?,
    ): PredictionSaveOutcome = PredictionSaveOutcome.Saved(prediction)

    override suspend fun deletePrediction(raceId: String) = throw UnsupportedOperationException()
}

private class FakeAuthRepository : AuthRepository {
    override val state: Flow<AuthState> = MutableStateFlow(
        AuthState.SignedIn(userId = "user-1", email = "user@example.com", displayName = "User"),
    )

    override suspend fun refresh() = Unit
    override suspend fun signIn(email: String, password: String) = Unit
    override suspend fun signUp(email: String, password: String, displayName: String) = Unit
    override suspend fun signOut() = Unit
    override fun currentUserId(): String = "user-1"
    override fun isConfigured(): Boolean = true
    override fun startSessionObserver(scope: CoroutineScope) = Unit
}

private class FakeLeagueDataRepository : LeagueDataRepository {
    override suspend fun raceData(leagueId: String): LeagueDataSnapshot =
        LeagueDataSnapshot(emptyList(), 0, 0)
}
