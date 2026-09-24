package com.ingeint.checkin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ingeint.checkin.AppContainer

/** Fábrica genérica para ViewModels que solo necesitan [AppContainer] (DI manual, docs/06). */
class AppViewModelFactory(private val container: AppContainer, private val create: (AppContainer) -> ViewModel) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create(container) as T
}
