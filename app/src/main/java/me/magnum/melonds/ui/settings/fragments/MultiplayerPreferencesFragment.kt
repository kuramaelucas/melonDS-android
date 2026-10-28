package me.magnum.melonds.ui.settings.fragments

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.R
import me.magnum.melonds.ui.settings.PreferenceFragmentTitleProvider
import java.net.Inet4Address

class MultiplayerPreferencesFragment : BasePreferenceFragment(), PreferenceFragmentTitleProvider {

    private class DiscoveredSession(val address: String, val name: String, val players: Int, val maxPlayers: Int)

    private var discoveryJob: Job? = null

    override fun getTitle() = getString(R.string.category_multiplayer)

    private lateinit var rolePreference: ListPreference
    private lateinit var playerNamePreference: EditTextPreference
    private lateinit var hostAddressPreference: EditTextPreference
    private lateinit var searchPreference: Preference
    private lateinit var localAddressPreference: Preference
    private lateinit var connectPreference: Preference
    private lateinit var statusPreference: Preference

    private var isConnecting = false
    private var statusJob: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_multiplayer, rootKey)

        rolePreference = findPreference("multiplayer_role")!!
        playerNamePreference = findPreference("multiplayer_player_name")!!
        hostAddressPreference = findPreference("multiplayer_host_address")!!
        searchPreference = findPreference("multiplayer_search_sessions")!!
        localAddressPreference = findPreference("multiplayer_local_address")!!
        connectPreference = findPreference("multiplayer_connect")!!
        statusPreference = findPreference("multiplayer_status")!!

        updateVisibility(rolePreference.value)
        localAddressPreference.summary = getLocalIpAddress(requireContext()) ?: getString(R.string.multiplayer_local_address_unknown)

        rolePreference.setOnPreferenceChangeListener { _, newValue ->
            // Changing the role invalidates the current session
            MelonEmulator.multiplayerStop()
            updateVisibility(newValue as String)
            updateStatus()
            true
        }
        searchPreference.setOnPreferenceClickListener {
            showSessionSearchDialog(hostAddressPreference)
            true
        }
        connectPreference.setOnPreferenceClickListener {
            onConnectClicked()
            true
        }
    }

    override fun onStart() {
        super.onStart()
        statusJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                updateStatus()
                delay(1000)
            }
        }
    }

    override fun onStop() {
        statusJob?.cancel()
        statusJob = null
        super.onStop()
    }

    private fun updateVisibility(role: String?) {
        hostAddressPreference.isVisible = role == "join"
        searchPreference.isVisible = role == "join"
        localAddressPreference.isVisible = role == "host"
        connectPreference.isVisible = role == "host" || role == "join"
        statusPreference.isVisible = role == "host" || role == "join"
    }

    private fun onConnectClicked() {
        if (isConnecting) return

        if (MelonEmulator.multiplayerIsActive()) {
            MelonEmulator.multiplayerStop()
            updateStatus()
            return
        }

        val isHost = rolePreference.value == "host"
        val playerName = playerNamePreference.text?.takeIf { it.isNotBlank() } ?: "Player"
        val hostAddress = hostAddressPreference.text?.trim()
        if (!isHost && hostAddress.isNullOrEmpty()) {
            Toast.makeText(requireContext(), R.string.multiplayer_join_address_missing, Toast.LENGTH_LONG).show()
            return
        }

        isConnecting = true
        updateStatus()
        viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                if (isHost) MelonEmulator.multiplayerStartHost(playerName) else MelonEmulator.multiplayerJoin(playerName, hostAddress!!)
            }
            isConnecting = false
            if (!success) {
                Toast.makeText(requireContext(), if (isHost) R.string.multiplayer_host_failed else R.string.multiplayer_join_failed, Toast.LENGTH_LONG).show()
            }
            updateStatus()
        }
    }

    private fun updateStatus() {
        if (!isAdded) return

        val isHost = rolePreference.value == "host"
        val active = MelonEmulator.multiplayerIsActive()

        connectPreference.isEnabled = !isConnecting
        connectPreference.setTitle(
            when {
                active && isHost -> R.string.multiplayer_stop_hosting
                active -> R.string.multiplayer_disconnect
                isHost -> R.string.multiplayer_start_hosting
                else -> R.string.multiplayer_connect
            }
        )

        if (isConnecting) {
            statusPreference.setSummary(R.string.multiplayer_connecting)
            return
        }
        if (!active) {
            statusPreference.setSummary(R.string.multiplayer_status_not_connected)
            return
        }

        // Status: 1 = client, 2 = host
        val remotePlayers = MelonEmulator.multiplayerGetPlayers().mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size != 4 || parts[3] == "1") return@mapNotNull null
            Triple(parts[0], parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0)
        }.filter { it.second == 1 || it.second == 2 }

        statusPreference.summary = when {
            isHost && remotePlayers.isEmpty() -> getString(R.string.multiplayer_status_waiting_for_player)
            isHost -> remotePlayers.joinToString("\n") { getString(R.string.multiplayer_status_player_connected, it.first, it.third) }
            remotePlayers.isEmpty() -> getString(R.string.multiplayer_status_connected_to_host)
            else -> remotePlayers.joinToString("\n") { getString(R.string.multiplayer_status_connected_to, it.first, it.third) }
        }
    }

    private fun showSessionSearchDialog(hostAddressPreference: EditTextPreference) {
        val adapter = ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1)
        val sessions = mutableListOf<DiscoveredSession>()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.multiplayer_discovered_peers_title)
            .setAdapter(adapter) { _, which ->
                hostAddressPreference.text = sessions[which].address
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { stopDiscovery() }
            .create()

        startDiscovery(
            onResult = { discovered ->
                sessions.clear()
                sessions.addAll(discovered)
                adapter.clear()
                if (discovered.isEmpty()) {
                    dialog.setMessage(getString(R.string.multiplayer_searching))
                } else {
                    dialog.setMessage(null)
                    adapter.addAll(discovered.map { "${it.name} (${it.players}/${it.maxPlayers}) - ${it.address}" })
                }
                adapter.notifyDataSetChanged()
            },
            onFailure = {
                dialog.setMessage(getString(R.string.multiplayer_error_discovery_failed))
            }
        )
        dialog.setMessage(getString(R.string.multiplayer_searching))
        dialog.show()
    }

    private fun startDiscovery(onResult: (List<DiscoveredSession>) -> Unit, onFailure: () -> Unit) {
        discoveryJob?.cancel()
        discoveryJob = viewLifecycleOwner.lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) { MelonEmulator.multiplayerStartDiscovery() }
            if (!started) {
                onFailure()
                return@launch
            }

            try {
                while (isActive) {
                    val discovered = withContext(Dispatchers.IO) {
                        MelonEmulator.multiplayerPollSessions().mapNotNull { parseSession(it) }
                    }
                    onResult(discovered)
                    delay(1000)
                }
            } finally {
                MelonEmulator.multiplayerStopDiscovery()
            }
        }
    }

    private fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    override fun onDestroyView() {
        stopDiscovery()
        super.onDestroyView()
    }

    private fun parseSession(entry: String): DiscoveredSession? {
        val parts = entry.split('|')
        if (parts.size != 4) return null
        return DiscoveredSession(parts[0], parts[1], parts[2].toIntOrNull() ?: 0, parts[3].toIntOrNull() ?: 0)
    }

    private fun getLocalIpAddress(context: Context): String? {
        val connectivityManager = context.getSystemService<ConnectivityManager>() ?: return null
        val linkProperties = connectivityManager.getLinkProperties(connectivityManager.activeNetwork) ?: return null
        return linkProperties.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }
}
