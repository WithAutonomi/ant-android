package com.autonomi.examples.antdemo.wallet

import android.app.Application
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.android.relay.ConnectionType
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal
import com.reown.appkit.presets.AppKitChainsPresets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/// WalletConnect spike (Android): connect an external self-custody wallet via
/// Reown AppKit and have it sign an Autonomi payment transaction. The mobile
/// equivalent of the desktop app's external-signer flow — the app never holds
/// a key. Kotlin counterpart of the iOS `WalletConnectManager.swift`.
///
/// ⚠️ NOT compiled. The Reown AppKit Android surface below (init params,
/// `setChains`, `ModalDelegate` method set, `request` / `getAccount` shapes)
/// is written against the documented API + the WalletConnect Android lineage,
/// but the sample couldn't be fetched — verify every Reown symbol against the
/// resolved `com.reown:appkit` version in Android Studio. These are the spots
/// most likely to need adjustment. The modal *presentation* is wired in the
/// Compose layer (see MainActivity / MainScreen), not here.
data class WalletState(
    val address: String? = null,
    val chainCaip2: String? = null,
    val lastTxHash: String? = null,
    val status: String = "Not connected",
)

object WalletConnectManager {
    private val _state = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> get() = _state

    // Pending request → resolved by the delegate's onSessionRequestResponse.
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
            // Deep-link the wallet uses to return to us. Must match an
            // intent-filter in AndroidManifest.xml (see manifest scheme).
            redirect = "antdemo-wc://request",
        )

        CoreClient.initialize(
            projectId = projectId,
            connectionType = ConnectionType.AUTOMATIC,
            application = app,
            metaData = metadata,
        )

        AppKit.initialize(
            init = Modal.Params.Init(CoreClient),
            onSuccess = { _state.value = _state.value.copy(status = "Configured — tap Connect Wallet") },
            onError = { err -> _state.value = _state.value.copy(status = "Init error: ${err.throwable.message}") },
        )

        // Advertise the chains we support. Presets cover common EVM chains;
        // Arbitrum One is keyed by its eip155 id. If a preset is missing,
        // build a Modal.Model.Chain manually (verify the constructor fields).
        val presets = AppKitChainsPresets.ethChains
        val chains = listOfNotNull(
            presets[AutonomiChain.ARBITRUM_ONE.chainId.toString()],
            presets[AutonomiChain.ARBITRUM_SEPOLIA.chainId.toString()],
        ).ifEmpty { presets.values.toList() }
        AppKit.setChains(chains)

        AppKit.setDelegate(Delegate)
    }

    /// Read the connected account into our state (call after connect / resume).
    fun refreshAccount() {
        val account = AppKit.getAccount()
        if (account == null) {
            _state.value = WalletState(status = "Not connected")
        } else {
            _state.value = _state.value.copy(
                address = account.address,
                chainCaip2 = account.chain,   // verify property name on Modal.Model.Account
                status = "Connected",
            )
        }
    }

    /// Build + send an ERC-20 `approve(vault, amount)` to the connected wallet
    /// for signing. Suspends until the wallet responds. Returns the tx hash.
    suspend fun sendApprove(chain: AutonomiChain, amount: String = "0"): String {
        val from = AppKit.getAccount()?.address ?: error("No wallet connected")
        val data = EthCalldata.approve(spender = chain.paymentVaultAddress, amount = amount)

        // eth_sendTransaction params: a 1-element array of the tx object,
        // serialized as a JSON string for Modal.Params.Request.
        val txJson = """[{"from":"$from","to":"${chain.tokenAddress}","data":"$data","value":"0x0"}]"""

        val deferred = CompletableDeferred<String>()
        pending = deferred
        _state.value = _state.value.copy(status = "Awaiting wallet signature…")

        AppKit.request(
            request = Modal.Params.Request(method = "eth_sendTransaction", params = txJson),
            onSuccess = { /* request delivered; result arrives via delegate */ },
            onError = { err -> deferred.completeExceptionally(err.throwable) },
        )
        val hash = deferred.await()
        _state.value = _state.value.copy(lastTxHash = hash, status = "Signed. tx: $hash")
        return hash
    }

    private object Delegate : AppKit.ModalDelegate {
        override fun onSessionApproved(approvedSession: Modal.Model.ApprovedSession) {
            refreshAccount()
        }

        override fun onSessionRequestResponse(response: Modal.Model.SessionRequestResponse) {
            val d = pending ?: return
            pending = null
            when (val res = response.result) {
                is Modal.Model.JsonRpcResponse.JsonRpcResult ->
                    d.complete(res.result) // tx hash string
                is Modal.Model.JsonRpcResponse.JsonRpcError ->
                    d.completeExceptionally(IllegalStateException(res.message))
            }
        }

        override fun onSessionRejected(rejectedSession: Modal.Model.RejectedSession) {}
        override fun onSessionUpdate(updatedSession: Modal.Model.UpdatedSession) {}
        override fun onSessionEvent(sessionEvent: Modal.Model.Event) {}
        override fun onSessionDelete(deletedSession: Modal.Model.DeletedSession) { refreshAccount() }
        override fun onSessionExtend(session: Modal.Model.Session) {}
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
