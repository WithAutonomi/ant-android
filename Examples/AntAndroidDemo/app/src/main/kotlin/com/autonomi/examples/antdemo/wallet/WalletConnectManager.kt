package com.autonomi.examples.antdemo.wallet

import android.app.Application
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.android.relay.ConnectionType
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal
import com.reown.appkit.client.models.request.Request
import com.reown.appkit.client.models.request.SentRequestResult
import com.reown.appkit.presets.AppKitChainsPresets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/// WalletConnect spike (Android): connect an external self-custody wallet via
/// Reown AppKit and have it sign an Autonomi payment transaction. The mobile
/// equivalent of the desktop app's external-signer flow — the app never holds
/// a key. Kotlin counterpart of the iOS `WalletConnectManager.swift`.
///
/// API verified against the resolved `com.reown:appkit:1.4.1` (javap) and the
/// upstream `sample/modal` app: `CoreClient.initialize` + `AppKit.initialize` +
/// `AppKit.setChains`, the full 14-method `ModalDelegate`, `AppKit.getAccount()`
/// (`.address` / `.chain`), and `AppKit.request(Request, onSuccess, onError)`
/// with the response delivered on `onSessionRequestResponse`.
data class WalletState(
    val address: String? = null,
    val chainId: String? = null,
    val lastTxHash: String? = null,
    val status: String = "Not connected",
)

object WalletConnectManager {
    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> get() = _state

    // Pending eth_sendTransaction → resolved by the delegate's response callback.
    private var pending: CompletableDeferred<String>? = null
    private var configured = false

    /// Call once from Application/Activity onCreate. Get a projectId from
    /// https://dashboard.reown.com.
    fun configure(app: Application, projectId: String) {
        if (configured) return
        configured = true

        val metadata = Core.Model.AppMetaData(
            name = "AntAndroidDemo",
            description = "Autonomi mobile bindings demo",
            url = "https://autonomi.com",
            icons = listOf("https://avatars.githubusercontent.com/u/179229932"),
            // Deep-link the wallet uses to return to us; must match an
            // intent-filter in AndroidManifest.xml.
            redirect = "antdemo-wc://request",
            linkMode = false,
            appLink = "",
        )

        CoreClient.initialize(
            projectId = projectId,
            connectionType = ConnectionType.AUTOMATIC,
            application = app,
            metaData = metadata,
        ) { error -> _state.value = _state.value.copy(status = "Core init error: ${error.throwable.message}") }

        AppKit.initialize(Modal.Params.Init(core = CoreClient)) { error ->
            _state.value = _state.value.copy(status = "AppKit init error: ${error.throwable.message}")
        }

        // Advertise Arbitrum One/Sepolia from the presets (keyed/identified by
        // CAIP-2). Fall back to all preset chains if the ids aren't present.
        val wanted = setOf(AutonomiChain.ARBITRUM_ONE.caip2, AutonomiChain.ARBITRUM_SEPOLIA.caip2)
        val all = AppKitChainsPresets.ethChains.values.toList()
        val chains = all.filter { it.id in wanted }.ifEmpty { all }
        AppKit.setChains(chains)

        AppKit.setDelegate(Delegate)
        refreshAccount()
    }

    /// Read the connected account into our state (call after connect / resume).
    fun refreshAccount() {
        val account = AppKit.getAccount()
        _state.value = if (account == null) {
            WalletState(status = "Not connected")
        } else {
            _state.value.copy(address = account.address, chainId = account.chain.id, status = "Connected")
        }
    }

    /// Build + send an ERC-20 `approve(vault, amount)` to the connected wallet
    /// for signing. Suspends until the wallet responds. Returns the tx hash.
    suspend fun sendApprove(chain: AutonomiChain, amount: String = "0"): String {
        val from = AppKit.getAccount()?.address ?: error("No wallet connected")
        val data = EthCalldata.approve(spender = chain.paymentVaultAddress, amount = amount)
        // eth_sendTransaction params: a 1-element array of the tx object (JSON).
        val txJson = """[{"from":"$from","to":"${chain.tokenAddress}","data":"$data","value":"0x0"}]"""

        val deferred = CompletableDeferred<String>()
        pending = deferred
        _state.value = _state.value.copy(status = "Awaiting wallet signature…")

        // Request's 3rd arg (numeric chainId: Long?) is defaulted → omitted, so
        // the request targets the session's selected chain (user picks Arbitrum
        // in the connect modal). The explicit SentRequestResult param type
        // disambiguates request()'s two overloads.
        AppKit.request(
            request = Request(method = "eth_sendTransaction", params = txJson),
            onSuccess = { _: SentRequestResult -> /* delivered; result via delegate */ },
            onError = { err: Throwable -> deferred.completeExceptionally(err) },
        )
        val hash = deferred.await()
        _state.value = _state.value.copy(lastTxHash = hash, status = "Signed. tx: $hash")
        return hash
    }

    private object Delegate : AppKit.ModalDelegate {
        override fun onSessionApproved(approvedSession: Modal.Model.ApprovedSession) { refreshAccount() }
        override fun onSessionRejected(rejectedSession: Modal.Model.RejectedSession) {}
        override fun onSessionUpdate(updatedSession: Modal.Model.UpdatedSession) {}
        override fun onSessionEvent(sessionEvent: Modal.Model.SessionEvent) {}
        override fun onSessionEvent(sessionEvent: Modal.Model.Event) {}
        override fun onSessionExtend(session: Modal.Model.Session) {}
        override fun onSessionDelete(deletedSession: Modal.Model.DeletedSession) { refreshAccount() }

        override fun onSessionRequestResponse(response: Modal.Model.SessionRequestResponse) {
            val d = pending ?: return
            pending = null
            when (val res = response.result) {
                is Modal.Model.JsonRpcResponse.JsonRpcResult -> d.complete(res.result)
                is Modal.Model.JsonRpcResponse.JsonRpcError -> d.completeExceptionally(IllegalStateException(res.message))
            }
        }

        override fun onSessionAuthenticateResponse(sessionAuthenticateResponse: Modal.Model.SessionAuthenticateResponse) {}
        override fun onSIWEAuthenticationResponse(response: Modal.Model.SIWEAuthenticateResponse) {}
        override fun onProposalExpired(proposal: Modal.Model.ExpiredProposal) {}
        override fun onRequestExpired(request: Modal.Model.ExpiredRequest) {}
        override fun onConnectionStateChange(state: Modal.Model.ConnectionState) {}
        override fun onError(error: Modal.Model.Error) {
            _state.value = _state.value.copy(status = "Wallet error: ${error.throwable.message}")
            pending?.completeExceptionally(error.throwable)
            pending = null
        }
    }
}
