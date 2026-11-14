package com.jscyril.ebb.guard

object DisableRequest {
    private const val WAIT_MS = 48 * 3_600_000L

    fun canDisable(pledge: PledgeRecord?, requestedAt: Long?, trustedNow: Long): Boolean =
        !Pledge.isActive(pledge, trustedNow) && requestedAt != null && trustedNow - requestedAt >= WAIT_MS
}
