package com.jscyril.ebb.guard

object DisableRequest {
    const val WAIT_MS = 48 * 3_600_000L
    const val EXPIRES_MS = 72 * 3_600_000L // a matured request is usable for 24 h, then must be filed again

    // A request filed during the pledge doesn't count, or the post-pledge wait could be pre-served.
    fun canDisable(pledge: PledgeRecord?, requestedAt: Long?, trustedNow: Long): Boolean =
        !Pledge.isActive(pledge, trustedNow) && requestedAt != null &&
            (pledge == null || requestedAt >= pledge.endsAt) &&
            trustedNow - requestedAt >= WAIT_MS && !isExpired(requestedAt, trustedNow)

    fun isExpired(requestedAt: Long?, now: Long): Boolean = requestedAt != null && now - requestedAt > EXPIRES_MS
}
