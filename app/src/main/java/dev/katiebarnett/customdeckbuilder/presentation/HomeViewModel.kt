package dev.katiebarnett.customdeckbuilder.presentation

import androidx.lifecycle.*
import dev.katiebarnett.customdeckbuilder.data.repositories.GameRepository
import dev.katiebarnett.customdeckbuilder.models.ErrorResource
import dev.katiebarnett.customdeckbuilder.models.Game
import dev.katiebarnett.customdeckbuilder.models.Resource
import dev.katiebarnett.customdeckbuilder.models.SuccessResource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val gameRepository: GameRepository
) : ViewModel() {
    
    private val _loading = MutableLiveData<Boolean>(false)
    val loading: LiveData<Boolean>
        get() = _loading

    private val _snackbar = MutableLiveData<String?>()
    val snackbar: LiveData<String?>
        get() = _snackbar
    
    val games = MutableLiveData<List<Game>>(listOf())
    
    private val _gameCreationResponse = MutableLiveData<Long>()
    val gameCreationResponse: LiveData<Long>
        get() = _gameCreationResponse
    
    init {
        launchDataLoad {
            games.postValue(gameRepository.getAllGames())
        }
    }
    
    fun createGame(gameName: String) {
        val game = Game(name = gameName)
        launchDataLoad {
            _gameCreationResponse.postValue(gameRepository.updateGame(game))
        }
    }

    private fun launchDataLoad(block: suspend () -> Unit): Job {
        return viewModelScope.launch(Dispatchers.IO) {
            try {
                _loading.postValue(true)
                block()
            } catch (error: Throwable) {
                _snackbar.postValue(error.message)
            } finally {
                _loading.postValue(false)
            }
        }
    }
}