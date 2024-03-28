/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.auth.awssigning.tests

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.auth.awssigning.*
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.collections.ValuesMap
import aws.smithy.kotlin.runtime.collections.get
import aws.smithy.kotlin.runtime.http.*
import aws.smithy.kotlin.runtime.http.auth.AwsHttpSigner
import aws.smithy.kotlin.runtime.http.auth.SigV4AuthScheme
import aws.smithy.kotlin.runtime.http.operation.*
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.request.HttpRequestBuilder
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.httptest.TestEngine
import aws.smithy.kotlin.runtime.identity.asIdentityProviderConfig
import aws.smithy.kotlin.runtime.net.url.Url
import aws.smithy.kotlin.runtime.operation.ExecutionContext
import aws.smithy.kotlin.runtime.time.Instant
import io.ktor.http.cio.*
import io.ktor.util.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_SIGNING_ISO_DATE = "2015-08-30T12:36:00Z"

private val defaultTestCredentials = Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")

private val defaultTestSigningConfig = AwsSigningConfig.Builder().apply {
    algorithm = AwsSigningAlgorithm.SIGV4
    credentials = defaultTestCredentials
    signingDate = Instant.fromIso8601(DEFAULT_SIGNING_ISO_DATE)
    region = "us-east-1"
    service = "service"
    useDoubleUriEncode = true
    normalizeUriPath = true
}

public data class Sigv4TestSuiteTest(
    public val path: Path,
    public val request: HttpRequestBuilder,
    public val canonicalRequest: String,
    public val stringToSign: String,
    public val signature: String,
    public val signedRequest: HttpRequestBuilder,
    public val config: AwsSigningConfig = defaultTestSigningConfig.build(),
) {
    override fun toString(): String = path.fileName.toString()
}

private val testSuitePath: Path by lazy {
    val cl = ClassLoader.getSystemClassLoader()
    val url = cl.getResource("aws-signing-test-suite") ?: error("failed to load sigv4 test suite resource")
    val uri = url.toURI()
    FileSystems.newFileSystem(uri, mapOf<String, Any>()).getPath("/aws-signing-test-suite/v4")
}

private val AwsSignatureType.fileNamePart: String
    get() = when (this) {
        AwsSignatureType.HTTP_REQUEST_VIA_HEADERS -> "header"
        AwsSignatureType.HTTP_REQUEST_VIA_QUERY_PARAMS -> "query"
        else -> error("Unsupported signature type $this for test suite")
    }

public typealias SigningStateProvider = suspend (HttpRequest, AwsSigningConfig) -> String

// FIXME - move to common test (will require ability to access test resources in a KMP compatible way)

@TestInstance(TestInstance.Lifecycle.PER_CLASS) // necessary so arg factory methods can handle disabledTests
public actual abstract class SigningSuiteTestBase : HasSigner {
    private val testDirPaths: List<Path> by lazy {
        Files
            .walk(testSuitePath)
            // Due to https://youtrack.jetbrains.com/issue/KT-47039 setting jvmTarget compatibility isn't enough
            // ignore the toList() extension in-favor of something that should work JDK8+ even if we compile with JDK17+
            .collect(Collectors.toList())
            .filter { !it.isDirectory() && it.name == "request.txt" }
            .filterNot { it.parent.name in disabledTests }
            .map { it.parent }
    }

    protected open val disabledTests: Set<String> = setOf(
        // TODO https://github.com/awslabs/smithy-kotlin/issues/653
        // ktor-http-cio parser doesn't support parsing multiline headers since they are deprecated in RFC7230
        "get-header-value-multiline",
        // ktor fails to parse with space in it (expects it to be a valid request already encoded)
        "get-space-normalized",
        "get-space-unnormalized",

        // no signed request to test against
        "get-vanilla-query-order-key",
        "get-vanilla-query-order-value",
    )

    public fun headerTestArgs(): List<Sigv4TestSuiteTest> = getTests(AwsSignatureType.HTTP_REQUEST_VIA_HEADERS)
    public fun queryTestArgs(): List<Sigv4TestSuiteTest> = getTests(AwsSignatureType.HTTP_REQUEST_VIA_QUERY_PARAMS)

    @Test
    public fun testParseRequest() {
        // sanity test that we are converting requests from file correctly
        val noBodyTest = testSuitePath.resolve("post-vanilla")
        val actual = getSignedRequest(noBodyTest, AwsSignatureType.HTTP_REQUEST_VIA_HEADERS)

        assertEquals(3, actual.headers.names().size)
        assertIs<HttpBody.Empty>(actual.body)
        assertEquals("example.amazonaws.com", actual.headers["Host"])
        assertEquals("20150830T123600Z", actual.headers["X-Amz-Date"])
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, Signature=5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b",
            actual.headers["Authorization"],
        )
    }

    @ParameterizedTest(name = "header middleware test {0} (#{index})")
    @MethodSource("headerTestArgs")
    public fun testSigv4TestSuiteHeaders(test: Sigv4TestSuiteTest) {
        testSigv4Middleware(test)
    }

    @ParameterizedTest(name = "query param middleware test {0} (#{index})")
    @MethodSource("queryTestArgs")
    public fun testSigv4TestSuiteQuery(test: Sigv4TestSuiteTest) {
        testSigv4Middleware(test)
    }

    private fun getTests(signatureType: AwsSignatureType): List<Sigv4TestSuiteTest> =
        testDirPaths.map { dir ->
            try {
                val req = getRequest(dir)
                val config = getSigningConfig(dir) ?: defaultTestSigningConfig
                val canonicalRequest = getCanonicalRequest(dir, signatureType)
                val stringToSign = getStringToSign(dir, signatureType)
                val signature = getSignature(dir, signatureType)
                val signedReq = getSignedRequest(dir, signatureType)
                config.signatureType = signatureType
                Sigv4TestSuiteTest(dir, req, canonicalRequest, stringToSign, signature, signedReq, config.build())
            } catch (ex: Exception) {
                println("failed to get request from $dir: ${ex.message}")
                throw ex
            }
        }

    /**
     * Run a test from the suite against the AwsSigv4Middleware implementation
     */
    private fun testSigv4Middleware(test: Sigv4TestSuiteTest): Unit = runBlocking {
        try {
            val op = buildOperation(test.config, signer, test.request)
            val actual = getSignedRequest(op)
            assertRequestsEqual(test.signedRequest.build(), actual, "actual signed request for ${test.path} not equal")
        } catch (ex: Exception) {
            println("failed to get a signed request for ${test.path}: $ex")
            throw ex
        }
    }

    @ParameterizedTest(name = "header canonical request test {0} (#{index})")
    @MethodSource("headerTestArgs")
    public fun testCanonicalRequestHeaders(test: Sigv4TestSuiteTest) {
        testCanonicalRequest(test)
    }

    @ParameterizedTest(name = "query param canonical request test {0} (#{index})")
    @MethodSource("queryTestArgs")
    public fun testCanonicalRequestQuery(test: Sigv4TestSuiteTest) {
        testCanonicalRequest(test)
    }

    public open val canonicalRequestProvider: SigningStateProvider? = null

    private fun testCanonicalRequest(test: Sigv4TestSuiteTest) = runBlocking {
        assumeTrue(canonicalRequestProvider != null)
        val expected = test.canonicalRequest
        val actual = canonicalRequestProvider!!(test.request.build(), test.config)
        assertEquals(expected, actual)
    }

    @ParameterizedTest(name = "header signature test {0} (#{index})")
    @MethodSource("headerTestArgs")
    public fun testSignatureHeaders(test: Sigv4TestSuiteTest) {
        testSignature(test)
    }

    @ParameterizedTest(name = "query param signature test {0} (#{index})")
    @MethodSource("queryTestArgs")
    public fun testSignatureQuery(test: Sigv4TestSuiteTest) {
        testSignature(test)
    }

    public open val signatureProvider: SigningStateProvider? = null

    private fun testSignature(test: Sigv4TestSuiteTest) = runBlocking {
        assumeTrue(signatureProvider != null)
        val expected = test.signature
        val actual = signatureProvider!!(test.request.build(), test.config)
        assertEquals(expected, actual)
    }

    @ParameterizedTest(name = "header string to sign test {0} (#{index})")
    @MethodSource("headerTestArgs")
    public fun testStringToSignHeaders(test: Sigv4TestSuiteTest) {
        testStringToSign(test)
    }

    @ParameterizedTest(name = "query param string to sign test {0} (#{index})")
    @MethodSource("queryTestArgs")
    public fun testStringToSignQuery(test: Sigv4TestSuiteTest) {
        testStringToSign(test)
    }

    public open val stringToSignProvider: SigningStateProvider? = null

    private fun testStringToSign(test: Sigv4TestSuiteTest) = runBlocking {
        assumeTrue(stringToSignProvider != null)
        val expected = test.stringToSign
        val actual = stringToSignProvider!!(test.request.build(), test.config)
        assertEquals(expected, actual)
    }

    /**
     * Get the actual signed request after sending it through middleware
     *
     * @param config The signing config to use when creating the middleware
     * @param operation The operation to sign
     */
    @OptIn(InternalApi::class)
    private suspend fun getSignedRequest(
        operation: SdkHttpOperation<Unit, HttpResponse>,
    ): HttpRequest {
        val client = SdkHttpClient(TestEngine())
        operation.roundTrip(client, Unit)
        return operation.context[HttpOperationContext.HttpCallList].last().request
    }

    private fun ValuesMap<String>.lowerKeys(): Set<String> = entries().map { it.key.lowercase() }.toSet()

    private fun assertRequestsEqual(expected: HttpRequest, actual: HttpRequest, message: String? = null) {
        assertEquals(expected.method, actual.method, message)
        assertEquals(expected.url.path, actual.url.path, message)

        expected.headers.forEach { key, values ->
            val expectedValues = values.sorted().joinToString(separator = ", ")
            val actualValues = actual.headers.getAll(key)?.sorted()?.joinToString(separator = ", ")
            assertNotNull(actualValues, "expected header key `$key` not found in actual signed request")
            assertEquals(expectedValues, actualValues, "expected header `$key=$expectedValues` in signed request")
        }

        val extraHeaders = actual.headers.lowerKeys() - expected.headers.lowerKeys()
        assertEquals(0, extraHeaders.size, "Found extra headers in request: $extraHeaders")

        if (expected.url.parameters != actual.url.parameters) {
            fun dumpParams(request: HttpRequest) =
                request.url.parameters.entryValues.joinToString("\n", "\n") { (key, value) -> "  $key = $value" }
            fail("\nTest case parameters: ${dumpParams(expected)}\n\nActual parameters: ${dumpParams(actual)}\n")
        }

        when (val expectedBody = expected.body) {
            is HttpBody.Empty -> assertIs<HttpBody.Empty>(actual.body)
            is HttpBody.Bytes -> {
                val actualBody = assertIs<HttpBody.Bytes>(actual.body)
                assertContentEquals(expectedBody.bytes(), actualBody.bytes())
            }
            else -> fail("Unexpected body type ${expectedBody::class}")
        }
    }

    /**
     * Parse context.json if it exists into a signing config
     */
    private fun getSigningConfig(dir: Path): AwsSigningConfig.Builder? {
        val file = dir.resolve("context.json")
        if (!file.exists()) return null
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        val creds = json["credentials"]!!.jsonObject
        val config = AwsSigningConfig.Builder()
        config.credentials = jsonCredentials(creds)
        config.region = json["region"]!!.jsonPrimitive.content
        config.service = json["service"]!!.jsonPrimitive.content

        json["expiration_in_seconds"]?.jsonPrimitive?.int?.let {
            config.expiresAfter = it.seconds
        }

        json["normalize"]?.jsonPrimitive?.boolean?.let {
            config.normalizeUriPath = it
        }

        json["double_uri_encode"]?.jsonPrimitive?.boolean?.let {
            config.useDoubleUriEncode = it
        }

        val isoDate = json["timestamp"]?.jsonPrimitive?.content ?: DEFAULT_SIGNING_ISO_DATE
        config.signingDate = Instant.fromIso8601(isoDate)

        json["omit_session_token"]?.jsonPrimitive?.boolean?.let {
            config.omitSessionToken = it
        }

        val sbh = json["sign_body"]?.jsonPrimitive?.booleanOrNull ?: false
        // https://github.com/awslabs/aws-c-auth/blob/main/tests/sigv4_signing_tests.c#L566
        if (sbh) {
            config.signedBodyHeader = AwsSignedBodyHeader.X_AMZ_CONTENT_SHA256
        }

        return config
    }

    /**
     * Get `request.txt` from the given directory [dir]
     */
    private fun getRequest(dir: Path): HttpRequestBuilder {
        val path = dir.resolve("request.txt")
        return parseRequest(path)
    }

    /**
     * Get `<type>-signed-request.txt` from the given directory [dir]
     */
    private fun getSignedRequest(dir: Path, type: AwsSignatureType): HttpRequestBuilder {
        val path = dir.resolve("${type.fileNamePart}-signed-request.txt")
        return parseRequest(path)
    }

    private fun getCanonicalRequest(dir: Path, type: AwsSignatureType): String =
        dir.resolve("${type.fileNamePart}-canonical-request.txt").readText().normalizeLineEndings()

    private fun getSignature(dir: Path, type: AwsSignatureType): String =
        dir.resolve("${type.fileNamePart}-signature.txt").readText().normalizeLineEndings()

    private fun getStringToSign(dir: Path, type: AwsSignatureType): String =
        dir.resolve("${type.fileNamePart}-string-to-sign.txt").readText().normalizeLineEndings()

    /**
     * Parse a path containing an HTTP request into an in memory representation of an SDK request
     */
    private fun parseRequest(path: Path): HttpRequestBuilder {
        // we have to do some massaging of these input files to get a valid request out of the parser.
        var text = path.readText()
        val lines = text.lines()
        val hasBody = lines.last() != "" && lines.find { it == "" } != null

        // in particular the parser requires the headers section to have two trailing newlines (\r\n)
        if (!hasBody) {
            text = text.trimEnd() + "\r\n\r\n"
        }

        val chan = ByteReadChannel(text.encodeToByteArray())

        val parsed = runBlocking {
            parseRequest(chan) ?: error("failed to parse http request from: $path")
        }

        val builder = HttpRequestBuilder()
        builder.method = HttpMethod.parse(parsed.method.value)
        builder.url.copyFrom(parsed)

        val parsedHeaders = CIOHeaders(parsed.headers)
        parsedHeaders.forEach { key, values ->
            builder.headers.appendAll(key, values)
        }

        if (hasBody) {
            val bytes = runBlocking { chan.readRemaining().readBytes() }
            builder.body = HttpBody.fromBytes(bytes)
        }

        return builder
    }
}

private fun Url.Builder.copyFrom(cioRequest: Request) {
    val parts = cioRequest.uri.split('?', limit = 2)
    path.encoded = parts[0]
    parts.getOrNull(1)?.let { parameters.encoded = it }
}

private fun jsonCredentials(jsonObject: JsonObject): Credentials = Credentials(
    jsonObject["access_key_id"]!!.jsonPrimitive.content,
    jsonObject["secret_access_key"]!!.jsonPrimitive.content,
    jsonObject["token"]?.jsonPrimitive?.content,
)

/**
 * Construct on SdkHttpOperation for testing with middleware
 *
 * @param config The signing config to use to set operation context attributes
 * @param serialized The parsed HTTP request that represents the serialized version of some request/operation
 */
@OptIn(InternalApi::class)
private fun buildOperation(
    config: AwsSigningConfig,
    awsSigner: AwsSigner,
    serialized: HttpRequestBuilder,
): SdkHttpOperation<Unit, HttpResponse> {
    val op = SdkHttpOperation.build<Unit, HttpResponse> {
        serializeWith = object : HttpSerializer.NonStreaming<Unit> {
            override fun serialize(context: ExecutionContext, input: Unit): HttpRequestBuilder = serialized
        }
        @Suppress("DEPRECATION")
        deserializer = IdentityDeserializer

        context {
            operationName = "testSigningOperation"
            serviceName = "testService"
            set(AwsSigningAttributes.SigningRegion, config.region)
            config.signingDate.let {
                set(AwsSigningAttributes.SigningDate, it)
            }
            set(AwsSigningAttributes.SigningService, config.service)
        }
    }

    val idp = object : CredentialsProvider {
        override suspend fun resolve(attributes: Attributes): Credentials = config.credentials
    }

    val signerConfig = AwsHttpSigner.Config().apply {
        signer = awsSigner
        service = config.service
        useDoubleUriEncode = config.useDoubleUriEncode
        normalizeUriPath = config.normalizeUriPath
        omitSessionToken = config.omitSessionToken
        signedBodyHeader = config.signedBodyHeader
        signatureType = config.signatureType
        expiresAfter = config.expiresAfter
    }

    op.execution.auth = OperationAuthConfig.from(
        idp.asIdentityProviderConfig(),
        SigV4AuthScheme(signerConfig),
    )

    return op
}

private val irregularLineEndings = """\r\n?""".toRegex()
private fun String.normalizeLineEndings() = replace(irregularLineEndings, "\n")
