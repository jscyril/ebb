package com.jscyril.ebb.guard

object DisableRequest {
    private const val WAIT_MS = 48 * 3_600_000L

    // A request filed during the pledge doesn't count, or the post-pledge wait could be pre-served.
    fun canDisable(pledge: PledgeRecord?, requestedAt: Long?, trustedNow: Long): Boolean =
        !Pledge.isActive(pledge, trustedNow) && requestedAt != null &&
            (pledge == null || requestedAt >= pledge.endsAt) &&
            trustedNow - requestedAt >= WAIT_MS
}
