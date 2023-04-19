/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.auth.awscredentials

import aws.smithy.kotlin.runtime.io.Closeable
import aws.smithy.kotlin.runtime.tracing.*
import aws.smithy.kotlin.runtime.util.Attributes
import kotlin.coroutines.coroutineContext

// TODO - support caching the provider that actually resolved credentials such that future calls don't involve going through the full chain

/**
 * Composite [CredentialsProvider] that delegates to a chain of providers. When asked for credentials [providers]
 * are consulted in the order given until one succeeds. If none of the providers in the chain can provide credentials
 * then this class will throw an exception. The exception will include the providers tried in the message. Each
 * individual exception is available as a suppressed exception.
 *
 * @param providers the list of providers to delegate to
 */
public open class CredentialsProviderChain(
    protected vararg val providers: CredentialsProvider,
) : CloseableCredentialsProvider {
    init {
        require(providers.isNotEmpty()) { "at least one provider must be in the chain" }
    }

    override fun toString(): String =
        (listOf(this) + providers).map { it::class.simpleName }.joinToString(" -> ")

    override suspend fun resolve(attributes: Attributes): Credentials = withSpan("Credentials chain") {
        val logger = coroutineContext.traceSpan.logger<CredentialsProviderChain>()
        val chain = this@CredentialsProviderChain
        val chainException = lazy { CredentialsProviderException("No credentials could be loaded from the chain: $chain") }
        for (provider in providers) {
            logger.trace { "Attempting to load credentials from $provider" }
            try {
                return@withSpan provider.resolve(attributes)
            } catch (ex: Exception) {
                logger.debug { "unable to load credentials from $provider: ${ex.message}" }
                chainException.value.addSuppressed(ex)
            }
        }

        throw chainException.value
    }

    override fun close() {
        val exceptions = providers.mapNotNull {
            try {
                (it as? Closeable)?.close()
                null
            } catch (ex: Exception) {
                ex
            }
        }
        if (exceptions.isNotEmpty()) {
            val ex = exceptions.first()
            exceptions.drop(1).forEach(ex::addSuppressed)
            throw ex
        }
    }
}
