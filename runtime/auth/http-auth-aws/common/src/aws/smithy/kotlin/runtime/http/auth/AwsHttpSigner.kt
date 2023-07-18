/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.http.auth

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awssigning.*
import aws.smithy.kotlin.runtime.auth.awssigning.internal.isEligibleForAwsChunkedStreaming
import aws.smithy.kotlin.runtime.auth.awssigning.internal.setAwsChunkedBody
import aws.smithy.kotlin.runtime.auth.awssigning.internal.setAwsChunkedHeaders
import aws.smithy.kotlin.runtime.auth.awssigning.internal.useAwsChunkedEncoding
import aws.smithy.kotlin.runtime.http.HttpBody
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.request.HttpRequestBuilder
import aws.smithy.kotlin.runtime.util.get
import kotlin.time.Duration

/**
 * AWS SigV4/SigV4a [HttpSigner] that signs outgoing requests using the given [config]
 */
@InternalApi
public class AwsHttpSigner(private val config: Config) : HttpSigner {
    @InternalApi
    public companion object {
        public inline operator fun invoke(block: Config.() -> Unit): AwsHttpSigner {
            val config = Config().apply(block)
            requireNotNull(config.service) { "A service must be specified for the middleware" }
            requireNotNull(config.signer) { "A signer must be specified for the middleware" }
            return AwsHttpSigner(config)
        }
    }

    @InternalApi
    public class Config {
        /**
         * The signer implementation to use for signing
         */
        public var signer: AwsSigner? = null

        /**
         * The credential scope service name to sign requests for
         * NOTE: The operation context is favored when [AwsSigningAttributes.SigningService] is set
         */
        public var service: String? = null

        /**
         * Sets what signature should be computed
         */
        public var signatureType: AwsSignatureType = AwsSignatureType.HTTP_REQUEST_VIA_HEADERS

        /**
         * The algorithm to sign with
         */
        public var algorithm: AwsSigningAlgorithm = AwsSigningAlgorithm.SIGV4

        /**
         * Indicates whether the payload should be unsigned _even_ in cases where it would otherwise be signable (e.g.,
         * a replayable stream or byte buffer). Setting this value to `false` will _not_ allow signing a non-replayable
         * stream.
         */
        public var isUnsignedPayload: Boolean = false

        /**
         * The uri is assumed to be encoded once in preparation for transmission.  Certain services
         * do not decode before checking signature, requiring double-encoding the uri in the canonical
         * request in order to pass a signature check.
         */
        public var useDoubleUriEncode: Boolean = true

        /**
         * Controls whether or not the uri paths should be normalized when building the canonical request
         */
        public var normalizeUriPath: Boolean = true

        /**
         * Flag indicating if the "X-Amz-Security-Token" query param should be omitted.
         * Normally, this parameter is added during signing if the credentials have a session token.
         * The only known case where this should be true is when signing a websocket handshake to IoT Core.
         */
        public var omitSessionToken: Boolean = false

        /**
         * Controls what body "hash" header, if any, should be added to the canonical request and the signed request.
         * Most services do not require this additional header.
         */
        public var signedBodyHeader: AwsSignedBodyHeader = AwsSignedBodyHeader.NONE

        /**
         * If non-zero and the signing transform is query param, then signing will add X-Amz-Expires to the query
         * string, equal to the value specified here.  If this value is zero or if header signing is being used then
         * this parameter has no effect.
         */
        public var expiresAfter: Duration? = null
    }

    override suspend fun sign(signingRequest: SignHttpRequest) {
        require(signingRequest.identity is Credentials) { "invalid Identity type ${signingRequest.identity::class}; expected ${Credentials::class}" }
        val attributes = signingRequest.signingAttributes
        val request = signingRequest.httpRequest
        val body = request.body

        // favor attributes from the current request context
        val contextHashSpecification = attributes.getOrNull(AwsSigningAttributes.HashSpecification)
        val contextSignedBodyHeader = attributes.getOrNull(AwsSigningAttributes.SignedBodyHeader)

        // operation signing config is baseConfig + operation specific config/overrides
        val signingConfig = AwsSigningConfig {
            region = attributes[AwsSigningAttributes.SigningRegion]
            service = attributes.getOrNull(AwsSigningAttributes.SigningService) ?: checkNotNull(config.service)
            credentials = signingRequest.identity as Credentials
            algorithm = config.algorithm
            signingDate = attributes.getOrNull(AwsSigningAttributes.SigningDate)

            signatureType = config.signatureType
            omitSessionToken = config.omitSessionToken
            normalizeUriPath = config.normalizeUriPath
            useDoubleUriEncode = config.useDoubleUriEncode
            expiresAfter = config.expiresAfter

            signedBodyHeader = contextSignedBodyHeader ?: config.signedBodyHeader

            // SDKs are supposed to default to signed payload _always_ when possible (and when `unsignedPayload` trait
            // isn't present). The only exception is when the customer explicitly disables signed payloads (via Config.isUnsignedPayload).

            hashSpecification = when {
                contextHashSpecification != null -> contextHashSpecification
                body is HttpBody.Empty -> HashSpecification.EmptyBody
                body.isEligibleForAwsChunkedStreaming -> {
                    if (request.headers.contains("x-amz-trailer")) {
                        if (config.isUnsignedPayload) HashSpecification.StreamingUnsignedPayloadWithTrailers else HashSpecification.StreamingAws4HmacSha256PayloadWithTrailers
                    } else {
                        HashSpecification.StreamingAws4HmacSha256Payload
                    }
                }
                config.isUnsignedPayload -> HashSpecification.UnsignedPayload
                // use the payload to compute the hash
                else -> HashSpecification.CalculateFromPayload
            }
        }

        if (signingConfig.useAwsChunkedEncoding) {
            request.setAwsChunkedHeaders()
        }

        val signingResult = checkNotNull(config.signer).sign(request.build(), signingConfig)
        val signedRequest = signingResult.output

        // Add the signature to the request context
        attributes.getOrNull(AwsSigningAttributes.RequestSignature)?.complete(signingResult.signature)

        request.update(signedRequest)

        if (signingConfig.useAwsChunkedEncoding) {
            request.setAwsChunkedBody(
                checkNotNull(config.signer),
                signingConfig,
                signingResult.signature,
                request.trailingHeaders.build(),
            )
        }
    }
}

private fun HttpRequestBuilder.update(signedRequest: HttpRequest) {
    signedRequest.headers.forEach { key, values ->
        this.headers.appendMissing(key, values)
    }

    signedRequest.url.parameters.forEach { key, values ->
        // The signed request has a URL-encoded path which means simply appending missing could result in both the raw
        // and percent-encoded value being present. Instead, just append new keys added by signing.
        if (!this.url.parameters.contains(key)) {
            url.parameters.appendAll(key, values)
        }
    }
}
