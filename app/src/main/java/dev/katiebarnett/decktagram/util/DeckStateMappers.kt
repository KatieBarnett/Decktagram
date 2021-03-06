package dev.katiebarnett.decktagram.util

import android.util.Log
import dev.katiebarnett.decktagram.models.Card
import dev.katiebarnett.decktagram.models.Deck
import dev.katiebarnett.decktagram.models.DeckState
import dev.katiebarnett.decktagram.models.PersistedDeckState

object PersistedDeckStateInvalid: Throwable()

fun DeckState.map(deck: Deck): PersistedDeckState {
    return this.run { 
        PersistedDeckState(
            deckId = deck.id,
            gameId = deck.gameId,
            drawnCards = drawnCards.map { it.id },
            remainingCards = remainingCards.map { it.id },
            lastModified = lastModified
        )
    }
}

fun PersistedDeckState.map(cards: List<Card>): DeckState {
    return this.run {
        DeckState(
            drawnCards = cards.selectList(drawnCards),
            remainingCards = cards.selectList(remainingCards),
            lastModified = lastModified
        )
    }
}

fun List<Card>.selectList(selection: List<Long>): List<Card> {
    return try {
        selection.map { selectionId ->
            val result = this.firstOrNull { selectionId == it.id } 
            if (result == null) {
                Log.d("SOMETHING", "error")
                throw PersistedDeckStateInvalid
            }
            result
        }
    } catch (e: Exception) {
        Log.e("SOMETHING", e.message, e)
        throw PersistedDeckStateInvalid
    }
}