package com.test.auth

import aws.smithy.kotlin.runtime.auth.AuthOption

object DefaultWaitersTestAuthSchemeProvider : WaitersTestAuthSchemeProvider {
    override suspend fun resolveAuthScheme(params: WaitersTestAuthSchemeParameters): List<AuthOption> {
        error("not needed for waiter integration tests")
    }
}