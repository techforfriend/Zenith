package com.my.kizzy.gateway

import kotlinx.coroutines.flow.MutableStateFlow

/** Gateway facts the UI needs but the socket's own loop would otherwise swallow. */
object DiscordGatewayState {
    /** True once Discord closed the gateway because it refused the token; cleared on the next READY. */
    val authRejected = MutableStateFlow(false)
}