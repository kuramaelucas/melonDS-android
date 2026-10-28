package me.magnum.melonds.ui.settings.fragments

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_multiplayer, rootKey)

        val rolePreference = findPreference<ListPreference>("multiplayer_role")!!
        val hostAddressPreference = findPreference<EditTextPreference>("multiplayer_host_address")!!
        val searchPreference = findPreference<Preference>("multiplayer_search_sessions")!!
        val localAddressPreference = findPreference<Preference>("multiplayer_local_address")!!

        fun updateVisibility(role: String?) {
            hostAddressPreference.isVisible = role == "join"
            searchPreference.isVisible = role == "join"
            localAddressPreference.isVisible = role == "host"
        }

        updateVisibility(rolePreference.value)
        localAddressPreference.summary = getLocalIpAddress(requireContext()) ?: getString(R.string.multiplayer_local_address_unknown)

        rolePreference.setOnPreferenceChangeListener { _, newValue ->
            updateVisibility(newValue as String)
            true
        }
        searchPreference.setOnPreferenceClickListener {
            showSessionSearchDialog(hostAddressPreference)
            true
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
