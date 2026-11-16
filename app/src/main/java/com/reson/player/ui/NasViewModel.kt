package com.reson.player.ui

import android.net.InetAddresses
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.reson.player.ResonApplication
import com.reson.player.data.library.MusicLibrary
import com.reson.player.domain.NasClient
import com.reson.player.domain.NasDevice
import com.reson.player.domain.NasDiscovery
import com.reson.player.domain.NasEntry
import com.reson.player.domain.NasException
import com.reson.player.domain.NasLogin
import com.reson.player.domain.NasPaths
import com.reson.player.domain.NasProblem
import com.reson.player.domain.NasProfile
import com.reson.player.domain.NasProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal data class NasDraft(
    val original: NasProfile? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "445",
    val share: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
) {
    override fun toString(): String = "NasDraft"

    fun login(requireShare: Boolean): NasLogin {
        val address = host.trim().trimEnd('.')
        val number = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw NasException(NasProblem.Invalid)
        val shareName = share.trim()
        if ((!InetAddresses.isNumericAddress(address) && !NasPaths.validHostName(address)) ||
            ((requireShare || shareName.isNotEmpty()) && !NasPaths.validComponent(shareName))
        ) throw NasException(NasProblem.Invalid)
        if (InetAddresses.isNumericAddress(address) &&
            !NasPaths.isLocalAddress(InetAddresses.parseNumericAddress(address))
        ) throw NasException(NasProblem.NonLocal)
        if (username.isBlank()) throw NasException(NasProblem.Authentication)
        val profile = NasProfile(
            name = name.trim().ifBlank { shareName.ifBlank { address } },
            host = address,
            port = number,
            share = shareName,
            username = username.trim(),
            domain = domain.trim(),
        )
        val old = original ?: return NasLogin(profile, password)
        val sameLocation = old.host == profile.host && old.port == profile.port && old.share == profile.share
        return NasLogin(profile.copy(id = old.id, folder = if (sameLocation) old.folder else emptyList()), password)
    }

    companion object {
        fun from(login: NasLogin): NasDraft {
            val profile = login.profile
            return NasDraft(
                profile, profile.name, profile.host, profile.port.toString(), profile.share,
                profile.username, login.password, profile.domain,
            )
        }
    }
}

internal sealed interface NasPage {
    data object Connections : NasPage
    data class Editor(val draft: NasDraft, val shares: List<String> = emptyList()) : NasPage
    data class Folder(
        val profile: NasProfile,
        val path: List<String>,
        val entries: List<NasEntry>? = null,
        val libraryFolders: List<List<String>> = emptyList(),
    ) : NasPage
}

internal data class NasUiState(
    val page: NasPage = NasPage.Connections,
    val profiles: List<NasProfile> = emptyList(),
    val devices: List<NasDevice> = emptyList(),
    val hasPermission: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

internal class NasViewModel(
    private val profiles: NasProfiles,
    private val client: NasClient,
    private val discovery: NasDiscovery,
    private val library: MusicLibrary,
) : ViewModel() {
    private val _state = MutableStateFlow(NasUiState())
    val state = _state.asStateFlow()
    private var operation: Job? = null

    init {
        refreshProfiles()
    }

    fun setPermission(granted: Boolean) {
        _state.update { it.copy(hasPermission = granted) }
    }

    fun refreshProfiles() = work {
        val saved = runInterruptible(Dispatchers.IO) { profiles.list() }
        _state.update { it.copy(profiles = saved) }
    }

    fun discover() = work {
        val devices = discovery.discover()
        _state.update { it.copy(devices = devices) }
        if (devices.isEmpty()) notice("No NAS advertised SMB. You can enter its address manually.")
    }

    fun add(device: NasDevice? = null) {
        navigate(
            NasPage.Editor(
                NasDraft(name = device?.name.orEmpty(), host = device?.address.orEmpty(),
                    port = device?.port?.toString() ?: "445"),
            ),
        )
    }

    fun edit(profile: NasProfile) = work {
        val login = runInterruptible(Dispatchers.IO) { profiles.login(profile.id) }
        _state.update { it.copy(page = NasPage.Editor(NasDraft.from(login))) }
    }

    fun updateDraft(draft: NasDraft) {
        val editor = _state.value.page as? NasPage.Editor ?: return
        val old = editor.draft
        val sameServer = old.host == draft.host && old.port == draft.port &&
            old.username == draft.username && old.domain == draft.domain && old.password == draft.password
        _state.update {
            it.copy(page = editor.copy(draft = draft, shares = if (sameServer) editor.shares else emptyList()),
                message = null)
        }
    }

    fun test(draft: NasDraft) = work {
        val login = draft.login(requireShare = false)
        runInterruptible(Dispatchers.IO) { client.test(login) }
        notice(if (login.profile.share.isEmpty()) "Signed in to the NAS." else "Connected to the share.")
    }

    fun listShares(draft: NasDraft) = work {
        val login = draft.copy(share = "").login(requireShare = false)
        val shares = runInterruptible(Dispatchers.IO) { client.shares(login) }
        _state.update { it.copy(page = NasPage.Editor(draft, shares)) }
        if (shares.isEmpty()) notice("No visible file shares. Enter the share name manually.")
    }

    fun save(draft: NasDraft) = work {
        val login = draft.login(requireShare = true)
        library.saveProfile(login)
        _state.update { state ->
            state.copy(page = NasPage.Connections, profiles = state.profiles.withProfile(login.profile))
        }
    }

    fun remove(profile: NasProfile) = work {
        library.removeProfile(profile.id)
        _state.update { state ->
            state.copy(page = NasPage.Connections, profiles = state.profiles.filterNot { it.id == profile.id })
        }
    }

    fun browse(profile: NasProfile, path: List<String> = profile.folder) {
        navigate(NasPage.Folder(profile, path))
        work {
            val folder = NasPage.Folder(profile, path, libraryFolders = library.nasFolders(profile))
            _state.update { it.copy(page = folder) }
            val entries = runInterruptible(Dispatchers.IO) { client.browse(profiles.login(profile.id), path) }
            _state.update { it.copy(page = folder.copy(entries = entries)) }
        }
    }

    fun scanLibraryFolder(folder: NasPage.Folder) = work("Couldn’t update the music library.") {
        val count = library.scanNas(folder.profile, folder.path)
        refreshLibraryFolders(folder)
        notice(if (count == 1) "Indexed 1 track." else "Indexed $count tracks.")
    }

    fun removeLibraryFolder(folder: NasPage.Folder, path: List<String>) = work("Couldn’t update the music library.") {
        library.removeNasFolder(folder.profile, path)
        refreshLibraryFolders(folder)
        notice("Removed from library folders.")
    }

    private suspend fun refreshLibraryFolders(folder: NasPage.Folder) {
        val folders = library.nasFolders(folder.profile)
        _state.update { it.copy(page = folder.copy(libraryFolders = folders)) }
    }

    fun saveStartFolder(folder: NasPage.Folder) = work {
        val updated = runInterruptible(Dispatchers.IO) {
            val login = profiles.login(folder.profile.id)
            val profile = login.profile.copy(folder = folder.path)
            profiles.save(NasLogin(profile, login.password))
            profile
        }
        _state.update {
            it.copy(page = folder.copy(profile = updated), profiles = it.profiles.withProfile(updated))
        }
        notice("Starting folder saved.")
    }

    fun back(): Boolean {
        val page = _state.value.page
        if (page is NasPage.Folder && page.path.isNotEmpty()) {
            browse(page.profile, page.path.dropLast(1))
        } else {
            navigate(NasPage.Connections)
            if (page !is NasPage.Connections) refreshProfiles()
        }
        return page !is NasPage.Connections
    }

    private fun navigate(page: NasPage) {
        operation?.cancel()
        _state.update { it.copy(page = page, busy = false, message = null, isError = false) }
    }

    private fun work(fallback: String = NasProblem.Unreachable.description, action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null, isError = false) }
        operation = viewModelScope.launch {
            try {
                action()
                _state.update { it.copy(busy = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = (error as? NasException)?.problem?.description ?: fallback
                _state.update { it.copy(busy = false, message = message, isError = true) }
            }
        }
    }

    private fun notice(message: String) {
        _state.update { it.copy(message = message, isError = false) }
    }

    private fun List<NasProfile>.withProfile(profile: NasProfile): List<NasProfile> =
        (filterNot { it.id == profile.id } + profile).sortedBy { it.name.lowercase() }

    companion object {
        fun factory(application: ResonApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                    NasViewModel(application.nasProfiles, application.nasClient, application.nasDiscovery, application.library),
                )!!
            }
    }
}
