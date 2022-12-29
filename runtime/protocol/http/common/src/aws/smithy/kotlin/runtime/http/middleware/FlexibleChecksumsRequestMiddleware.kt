/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.http.middleware

import aws.smithy.kotlin.runtime.ClientException
import aws.smithy.kotlin.runtime.hashing.*
import aws.smithy.kotlin.runtime.http.*
import aws.smithy.kotlin.runtime.http.operation.*
import aws.smithy.kotlin.runtime.http.request.HttpRequestBuilder
import aws.smithy.kotlin.runtime.http.request.header
import aws.smithy.kotlin.runtime.io.*
import aws.smithy.kotlin.runtime.util.*
import kotlin.coroutines.coroutineContext

/**
 * Mutate a request to enable flexible checksums.
 *
 * If the checksum will be sent as a header, calculate the checksum.
 *
 * Otherwise, if it will be sent as a trailing header, calculate the checksum as asynchronously as the body is streamed.
 * In this case, a [LazyAsyncValue] will be added to the execution context which allows the trailing checksum to be sent
 * after the entire body has been streamed.
 *
 * @param checksumAlgorithmName the name of the algorithm used to calculate the checksum
 */
@InternalApi
public class FlexibleChecksumsRequestMiddleware(private val checksumAlgorithmName: String) : ModifyRequestMiddleware {
    public companion object {
        public val TrailingHeaders: AttributeKey<LazyHeadersBuilder> = AttributeKey("TrailingHeaders")
    }

    public override suspend fun modifyRequest(req: SdkHttpRequest): SdkHttpRequest {
        val logger = coroutineContext.getLogger<FlexibleChecksumsRequestMiddleware>()

        val headerName = "x-amz-checksum-${checksumAlgorithmName.lowercase()}"
        logger.debug { "Resolved header name: $headerName" }

        // remove all checksum headers except for $headerName
        // this handles the case where a user inputs a precalculated checksum, but it doesn't match the input checksum algorithm
        req.subject.headers.removeAllChecksumHeadersExcept(headerName)

        val checksumAlgorithm = checksumAlgorithmName.toHashFunction() ?: throw ClientException("Could not parse checksum algorithm $checksumAlgorithmName")
        logger.debug { "Resolved checksum algorithm: $checksumAlgorithm" }

        if (!checksumAlgorithm.isSupported) {
            throw ClientException("Checksum algorithm $checksumAlgorithmName is not supported for flexible checksums")
        }

        if (req.subject.body.isEligibleForAwsChunkedStreaming) {
            logger.debug { "Sending checksum as a trailing header" }
            req.subject.header("x-amz-trailer", headerName)

            val lazyChecksum: LazyAsyncValue<String> = if (req.subject.headers[headerName] != null) {
                logger.debug { "User supplied a checksum, skipping asynchronous calculation" }

                val checksum = req.subject.headers[headerName]!!
                req.subject.headers.remove(headerName) // remove the checksum header because it will be sent as a trailing header
                asyncLazy { checksum }
            } else {
                logger.debug { "Calculating checksum asynchronously" }

                req.subject.setHashingBody(checksumAlgorithm)
                val body = req.subject.body
                asyncLazy { body.checksum }
            }

            // add the lazy checksum to the execution context
            val trailingHeadersBuilder: LazyHeadersBuilder = req.context.getOrNull(TrailingHeaders) ?: LazyHeadersBuilder()
            trailingHeadersBuilder.append(headerName, lazyChecksum)
            req.context[TrailingHeaders] = trailingHeadersBuilder
        } else if (req.subject.headers[headerName] == null) {
            logger.debug { "Calculating checksum" }

            val checksum = req.subject.body.calculateChecksum(checksumAlgorithm)
            req.subject.header(headerName, checksum)

            logger.debug { "Calculated checksum: $checksum" }
        }

        return req
    }

    // FIXME this duplicates the logic from aws-signing-common, but can't import from there due to circular import.
    private val HttpBody.isEligibleForAwsChunkedStreaming: Boolean
        get() = (this is HttpBody.SourceContent || this is HttpBody.ChannelContent) && contentLength != null &&
            (isOneShot || contentLength!! > 65536 * 16)

    /**
     * Wrap the HttpRequestBuilder's body with a hashing body, which will observe the data as it's being consumed and maintain
     * an internal hash.
     * @param hashFunction the hash function to apply to this body
     * @throws ClientException when the HttpBody is not supported for hashing bodies
     */
    private fun HttpRequestBuilder.setHashingBody(hashFunction: HashFunction) {
        body = when (body) {
            is HttpBody.SourceContent ->
                HashingSource(
                    hashFunction,
                    (body as HttpBody.SourceContent).readFrom(),
                ).toHttpBody(body.contentLength)

            is HttpBody.ChannelContent -> HashingByteReadChannel(
                hashFunction,
                (body as HttpBody.ChannelContent).readFrom(),
            ).toHttpBody(body.contentLength)

            else -> throw ClientException("HttpBody type is not supported")
        }
    }

    /**
     * @return if the [HashFunction] is supported by flexible checksums
     */
    private val HashFunction.isSupported: Boolean get() = when (this) {
        is Crc32, is Crc32c, is Sha256, is Sha1 -> true
        else -> false
    }

    /**
     * Gets the Base64 encoded checksum of an HttpBody
     * To use this, the HttpBody's underlying data source *must* be either a [HashingSource] or [HashingByteReadChannel],
     * which means the HttpBody must also be either an [HttpBody.SourceContent] or [HttpBody.ChannelContent]. An exception
     * will be thrown otherwise.
     * @return the Base64 encoded checksum of an HttpBody
     */
    public val HttpBody.checksum: String get() = when (this) {
        is HttpBody.SourceContent -> { (readFrom() as HashingSource).digest().encodeBase64String() }
        is HttpBody.ChannelContent -> { (readFrom() as HashingByteReadChannel).digest().encodeBase64String() }
        else -> throw ClientException("HttpBody type is not supported")
    }

    /**
     * Calculate the checksum synchronously. This will suspend until the entire body has been hashed.
     * @param hash the [HashFunction] used to calculate the checksum
     * @return the checksum of the body as a Base64 encoded string
     */
    private suspend fun HttpBody.calculateChecksum(hash: HashFunction): String = when (this) {
        is HttpBody.SourceContent -> readFrom().readToByteArray().hash(hash).encodeBase64String()
        is HttpBody.ChannelContent -> readFrom().readToBuffer().readToByteArray().hash(hash).encodeBase64String()
        is HttpBody.Bytes -> bytes().hash(hash).encodeBase64String()
        is HttpBody.Empty -> throw RuntimeException("can't calculate checksum of an empty body")
    }

    /**
     * Removes all checksum headers except [headerName]
     * @param headerName the checksum header name to keep
     */
    private fun HeadersBuilder.removeAllChecksumHeadersExcept(headerName: String) {
        names().forEach { name ->
            if (name.startsWith("x-amz-checksum-") && name != headerName) {
                remove(name)
            }
        }
    }
}
