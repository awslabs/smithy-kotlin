/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.kotlin.codegen.core

import software.amazon.smithy.kotlin.codegen.model.toSymbol

/**
 * Commonly used runtime types. Provides a single definition of a runtime symbol such that codegen isn't littered
 * with inline symbol creation which makes refactoring of the runtime more difficult and error-prone.
 */
object RuntimeTypes {
    object Http : RuntimeTypePackage(KotlinDependency.HTTP) {
        val HttpBody = symbol("HttpBody")
        val HttpCall = symbol("HttpCall")
        val HttpMethod = symbol("HttpMethod")
        val readAll = symbol("readAll")
        val toByteStream = symbol("toByteStream")
        val toHttpBody = symbol("toHttpBody")
        val isSuccess = symbol("isSuccess")
        val StatusCode = symbol("HttpStatusCode")
        val toSdkByteReadChannel = symbol("toSdkByteReadChannel")
        val Headers = symbol("Headers")

        object Util : RuntimeTypePackage(KotlinDependency.HTTP, "util") {
            val encodeLabel = symbol("encodeLabel")
            val quoteHeaderValue = symbol("quoteHeaderValue")
        }

        object Request : RuntimeTypePackage(KotlinDependency.HTTP, "request") {
            val HttpRequest = symbol("HttpRequest")
            val HttpRequestBuilder = symbol("HttpRequestBuilder")
            val immutableView = symbol("immutableView")
            val url = symbol("url")
            val headers = symbol("headers")
            val toBuilder = symbol("toBuilder")
        }

        object Response : RuntimeTypePackage(KotlinDependency.HTTP, "response") {
            val HttpResponse = symbol("HttpResponse")
        }
    }
    object HttpClient : RuntimeTypePackage(KotlinDependency.HTTP_CLIENT) {
        val SdkHttpClient = symbol("SdkHttpClient")

        object Middleware : RuntimeTypePackage(KotlinDependency.HTTP, "middleware") {
            val MutateHeadersMiddleware = symbol("MutateHeaders")
        }

        object Operation : RuntimeTypePackage(KotlinDependency.HTTP, "operation") {
            val AuthSchemeResolver = symbol("AuthSchemeResolver")
            val context = symbol("context")
            val EndpointResolver = symbol("EndpointResolver")
            val ResolveEndpointRequest = symbol("ResolveEndpointRequest")
            val execute = symbol("execute")
            val HttpDeserializer = symbol("HttpDeserializer")
            val HttpOperationContext = symbol("HttpOperationContext")
            val HttpSerializer = symbol("HttpSerializer")
            val OperationAuthConfig = symbol("OperationAuthConfig")
            val OperationMetrics = symbol("OperationMetrics")
            val OperationRequest = symbol("OperationRequest")
            val roundTrip = symbol("roundTrip")
            val telemetry = symbol("telemetry")
            val SdkHttpOperation = symbol("SdkHttpOperation")
            val SdkHttpRequest = symbol("SdkHttpRequest")
            val setResolvedEndpoint = symbol("setResolvedEndpoint")
        }

        object Config : RuntimeTypePackage(KotlinDependency.HTTP, "config") {
            val HttpClientConfig = symbol("HttpClientConfig")
            val HttpEngineConfig = symbol("HttpEngineConfig")
        }

        object Engine : RuntimeTypePackage(KotlinDependency.HTTP, "engine") {
            val HttpClientEngine = symbol("HttpClientEngine")
            val manage = symbol("manage", "engine.internal", isExtension = true)
        }

        object Interceptors : RuntimeTypePackage(KotlinDependency.HTTP, "interceptors") {
            val ContinueInterceptor = symbol("ContinueInterceptor")
            val HttpInterceptor = symbol("HttpInterceptor")
            val Md5ChecksumInterceptor = symbol("Md5ChecksumInterceptor")
            val FlexibleChecksumsRequestInterceptor = symbol("FlexibleChecksumsRequestInterceptor")
            val FlexibleChecksumsResponseInterceptor = symbol("FlexibleChecksumsResponseInterceptor")
            val ResponseLengthValidationInterceptor = symbol("ResponseLengthValidationInterceptor")
            val RequestCompressionInterceptor = symbol("RequestCompressionInterceptor")
        }
    }

    object Core : RuntimeTypePackage(KotlinDependency.CORE) {
        val Clock = symbol("Clock", "time")
        val ExecutionContext = symbol("ExecutionContext", "operation")
        val ErrorMetadata = symbol("ErrorMetadata")
        val ServiceErrorMetadata = symbol("ServiceErrorMetadata")
        val Instant = symbol("Instant", "time")
        val fromEpochMilliseconds = symbol("fromEpochMilliseconds", "time")
        val TimestampFormat = symbol("TimestampFormat", "time")
        val ClientException = symbol("ClientException")

        object Collections : RuntimeTypePackage(KotlinDependency.CORE, "collections") {
            val Attributes = symbol("Attributes")
            val attributesOf = symbol("attributesOf")
            val AttributeKey = symbol("AttributeKey")
            val createOrAppend = symbol("createOrAppend")
            val get = symbol("get")
            val mutableMultiMapOf = symbol("mutableMultiMapOf")
            val putIfAbsent = symbol("putIfAbsent")
            val putIfAbsentNotNull = symbol("putIfAbsentNotNull")
            val ReadThroughCache = symbol("ReadThroughCache")
        }

        object Content : RuntimeTypePackage(KotlinDependency.CORE, "content") {
            val BigDecimal = symbol("BigDecimal")
            val BigInteger = symbol("BigInteger")
            val ByteArrayContent = symbol("ByteArrayContent")
            val ByteStream = symbol("ByteStream")
            val buildDocument = symbol("buildDocument")
            val decodeToString = symbol("decodeToString")
            val Document = symbol("Document")
            val toByteArray = symbol("toByteArray")
        }

        object Retries : RuntimeTypePackage(KotlinDependency.CORE, "retries") {
            val Outcome = symbol("Outcome")
            val RetryStrategy = symbol("RetryStrategy")
            val StandardRetryStrategy = symbol("StandardRetryStrategy")

            object Delay : RuntimeTypePackage(KotlinDependency.CORE, "retries.delay") {
                val InfiniteTokenBucket = symbol("InfiniteTokenBucket")
            }

            object Policy : RuntimeTypePackage(KotlinDependency.CORE, "retries.policy") {
                val Acceptor = symbol("Acceptor")
                val AcceptorRetryPolicy = symbol("AcceptorRetryPolicy")
                val ErrorTypeAcceptor = symbol("ErrorTypeAcceptor")
                val InputOutputAcceptor = symbol("InputOutputAcceptor")
                val OutputAcceptor = symbol("OutputAcceptor")
                val RetryDirective = symbol("RetryDirective")
                val RetryErrorType = symbol("RetryErrorType")
                val RetryPolicy = symbol("RetryPolicy")
                val StandardRetryPolicy = symbol("StandardRetryPolicy")
                val SuccessAcceptor = symbol("SuccessAcceptor")
            }
        }

        object Hashing : RuntimeTypePackage(KotlinDependency.CORE, "hashing") {
            val Sha256 = symbol("Sha256")
        }

        object IO : RuntimeTypePackage(KotlinDependency.CORE, "io") {
            val Closeable = symbol("Closeable")
            val SdkManagedGroup = symbol("SdkManagedGroup")
            val addIfManaged = symbol("addIfManaged", isExtension = true)
        }

        object Text : RuntimeTypePackage(KotlinDependency.CORE, "text") {
            object Encoding : RuntimeTypePackage(KotlinDependency.CORE, "text.encoding") {
                val decodeBase64 = symbol("decodeBase64")
                val decodeBase64Bytes = symbol("decodeBase64Bytes")
                val encodeBase64 = symbol("encodeBase64")
                val encodeBase64String = symbol("encodeBase64String")
                val PercentEncoding = symbol("PercentEncoding")
            }
        }

        object Utils : RuntimeTypePackage(KotlinDependency.CORE, "util") {
            val ExpiringValue = symbol("ExpiringValue")
            val flattenIfPossible = symbol("flattenIfPossible")
            val LazyAsyncValue = symbol("LazyAsyncValue")
            val length = symbol("length")
            val mergeSequential = symbol("mergeSequential")
            val truthiness = symbol("truthiness")
            val toNumber = symbol("toNumber")
            val type = symbol("type")
        }

        object Net : RuntimeTypePackage(KotlinDependency.CORE, "net") {
            val Host = symbol("Host")

            object Url : RuntimeTypePackage(KotlinDependency.CORE, "net.url") {
                val QueryParameters = symbol("QueryParameters")
                val Url = symbol("Url")
            }
        }
    }

    object SmithyClient : RuntimeTypePackage(KotlinDependency.SMITHY_CLIENT) {
        val SdkClient = symbol("SdkClient")
        val AbstractSdkClientBuilder = symbol("AbstractSdkClientBuilder")
        val LogMode = symbol("LogMode")
        val RetryClientConfig = symbol("RetryClientConfig")
        val RetryStrategyClientConfig = symbol("RetryStrategyClientConfig")
        val RetryStrategyClientConfigImpl = symbol("RetryStrategyClientConfigImpl")
        val SdkClientConfig = symbol("SdkClientConfig")
        val SdkClientFactory = symbol("SdkClientFactory")
        val SdkClientOption = symbol("SdkClientOption")
        val RequestInterceptorContext = symbol("RequestInterceptorContext")
        val ProtocolRequestInterceptorContext = symbol("ProtocolRequestInterceptorContext")
        val IdempotencyTokenProvider = symbol("IdempotencyTokenProvider")
        val IdempotencyTokenConfig = symbol("IdempotencyTokenConfig")
        val IdempotencyTokenProviderExt = symbol("idempotencyTokenProvider")

        object Config : RuntimeTypePackage(KotlinDependency.SMITHY_CLIENT, "config") {
            val RequestCompressionConfig = symbol("RequestCompressionConfig")
            val CompressionClientConfig = symbol("CompressionClientConfig")
        }

        object Endpoints : RuntimeTypePackage(KotlinDependency.SMITHY_CLIENT, "endpoints") {
            val EndpointProvider = symbol("EndpointProvider")
            val Endpoint = symbol("Endpoint")
            val EndpointProviderException = symbol("EndpointProviderException")
            val SigningContextAttributeKey = symbol("SigningContextAttributeKey")
            val authOptions = symbol("authOptions")
            object Functions : RuntimeTypePackage(KotlinDependency.SMITHY_CLIENT, "endpoints.functions") {
                val substring = symbol("substring")
                val isValidHostLabel = symbol("isValidHostLabel")
                val uriEncode = symbol("uriEncode")
                val parseUrl = symbol("parseUrl")
                val Url = symbol("Url")
            }
        }
    }

    object Serde : RuntimeTypePackage(KotlinDependency.SERDE) {
        val Serializer = symbol("Serializer")
        val Deserializer = symbol("Deserializer")
        val SdkFieldDescriptor = symbol("SdkFieldDescriptor")
        val SdkObjectDescriptor = symbol("SdkObjectDescriptor")
        val SerialKind = symbol("SerialKind")
        val SerializationException = symbol("SerializationException")
        val DeserializationException = symbol("DeserializationException")
        val getOrDeserializeErr = symbol("getOrDeserializeErr")

        val serializeStruct = symbol("serializeStruct")
        val serializeList = symbol("serializeList")
        val serializeMap = symbol("serializeMap")

        val deserializeStruct = symbol("deserializeStruct")
        val deserializeList = symbol("deserializeList")
        val deserializeMap = symbol("deserializeMap")
        val asSdkSerializable = symbol("asSdkSerializable")
        val field = symbol("field")

        val parse = symbol("parse")
        val parseInt = symbol("parseInt")
        val parseShort = symbol("parseShort")
        val parseLong = symbol("parseLong")
        val parseFloat = symbol("parseFloat")
        val parseDouble = symbol("parseDouble")
        val parseByte = symbol("parseByte")
        val parseBoolean = symbol("parseBoolean")
        val parseTimestamp = symbol("parseTimestamp")
        val parseBigInteger = symbol("parseBigInteger")
        val parseBigDecimal = symbol("parseBigDecimal")

        object SerdeJson : RuntimeTypePackage(KotlinDependency.SERDE_JSON) {
            val JsonSerialName = symbol("JsonSerialName")
            val JsonSerializer = symbol("JsonSerializer")
            val JsonDeserializer = symbol("JsonDeserializer")
            val IgnoreKey = symbol("IgnoreKey")
        }

        object SerdeXml : RuntimeTypePackage(KotlinDependency.SERDE_XML) {
            val XmlSerialName = symbol("XmlSerialName")
            val XmlAliasName = symbol("XmlAliasName")
            val XmlCollectionName = symbol("XmlCollectionName")
            val XmlNamespace = symbol("XmlNamespace")
            val XmlCollectionValueNamespace = symbol("XmlCollectionValueNamespace")
            val XmlMapKeyNamespace = symbol("XmlMapKeyNamespace")
            val Flattened = symbol("Flattened")
            val XmlAttribute = symbol("XmlAttribute")
            val XmlMapName = symbol("XmlMapName")
            val XmlError = symbol("XmlError")
            val XmlSerializer = symbol("XmlSerializer")
            val XmlUnwrappedOutput = symbol("XmlUnwrappedOutput")

            val XmlTagReader = symbol("XmlTagReader")
            val xmlStreamReader = symbol("xmlStreamReader")
            val xmlRootTagReader = symbol("xmlTagReader")
            val data = symbol("data")
            val tryData = symbol("tryData")
        }

        object SerdeFormUrl : RuntimeTypePackage(KotlinDependency.SERDE_FORM_URL) {
            val FormUrlSerialName = symbol("FormUrlSerialName")
            val FormUrlCollectionName = symbol("FormUrlCollectionName")
            val Flattened = symbol("FormUrlFlattened")
            val FormUrlMapName = symbol("FormUrlMapName")
            val QueryLiteral = symbol("QueryLiteral")
            val FormUrlSerializer = symbol("FormUrlSerializer")
        }
    }

    object Auth {
        object Credentials {
            object AwsCredentials : RuntimeTypePackage(KotlinDependency.AWS_CREDENTIALS) {
                val Credentials = symbol("Credentials")
                val CredentialsProvider = symbol("CredentialsProvider")
                val CredentialsProviderConfig = symbol("CredentialsProviderConfig")
                val SigV4aClientConfig = symbol("SigV4aClientConfig")
            }
        }

        object Identity : RuntimeTypePackage(KotlinDependency.IDENTITY_API) {
            val AuthSchemeId = symbol("AuthSchemeId", "auth")
            val AuthSchemeProvider = symbol("AuthSchemeProvider", "auth")
            val AuthOption = symbol("AuthOption", "auth")

            val IdentityProvider = symbol("IdentityProvider", "identity")
            val IdentityProviderConfig = symbol("IdentityProviderConfig", "identity")
        }

        object Signing {
            object AwsSigningCommon : RuntimeTypePackage(KotlinDependency.AWS_SIGNING_COMMON) {
                val AwsSignedBodyHeader = symbol("AwsSignedBodyHeader")
                val AwsSigner = symbol("AwsSigner")
                val AwsSigningAttributes = symbol("AwsSigningAttributes")
                val AwsSigningConfig = symbol("AwsSigningConfig")
                val HashSpecification = symbol("HashSpecification")
                val mergeInto = symbol("mergeInto")
                val presignRequest = symbol("presignRequest")
            }

            object AwsSigningStandard : RuntimeTypePackage(KotlinDependency.AWS_SIGNING_DEFAULT) {
                val DefaultAwsSigner = symbol("DefaultAwsSigner")
            }
        }

        object HttpAuth : RuntimeTypePackage(KotlinDependency.HTTP_AUTH) {
            val AnonymousAuthScheme = symbol("AnonymousAuthScheme")
            val AnonymousIdentity = symbol("AnonymousIdentity")
            val AnonymousIdentityProvider = symbol("AnonymousIdentityProvider")
            val HttpAuthConfig = symbol("HttpAuthConfig")
            val AuthScheme = symbol("AuthScheme")

            val BearerTokenAuthScheme = symbol("BearerTokenAuthScheme")
            val BearerTokenProviderConfig = symbol("BearerTokenProviderConfig")
            val BearerTokenProvider = symbol("BearerTokenProvider")
        }

        object HttpAuthAws : RuntimeTypePackage(KotlinDependency.HTTP_AUTH_AWS) {
            val AwsHttpSigner = symbol("AwsHttpSigner")
            val SigV4AuthScheme = symbol("SigV4AuthScheme")
            val SigV4AsymmetricAuthScheme = symbol("SigV4AsymmetricAuthScheme")
            val mergeAuthOptions = symbol("mergeAuthOptions")
            val sigV4 = symbol("sigV4")
            val sigV4A = symbol("sigV4A")
        }
    }

    object Observability {
        object TelemetryApi : RuntimeTypePackage(KotlinDependency.TELEMETRY_API) {
            val SpanKind = symbol("SpanKind", "trace")
            val TelemetryConfig = symbol("TelemetryConfig")
            val TelemetryProvider = symbol("TelemetryProvider")
            val TelemetryProviderContext = symbol("TelemetryProviderContext")
            val TelemetryContextElement = symbol("TelemetryContextElement", "context")
            val TraceSpan = symbol("TraceSpan", "trace")
            val withSpan = symbol("withSpan", "trace")
        }
        object TelemetryDefaults : RuntimeTypePackage(KotlinDependency.TELEMETRY_DEFAULTS) {
            val Global = symbol("Global")
        }
    }

    object KotlinCoroutines {
        val coroutineContext = "kotlin.coroutines.coroutineContext".toSymbol()
    }

    object KotlinxCoroutines {

        val CompletableDeferred = "kotlinx.coroutines.CompletableDeferred".toSymbol()
        val job = "kotlinx.coroutines.job".toSymbol()

        object Flow {
            // NOTE: smithy-kotlin core has an API dependency on this already
            val Flow = "kotlinx.coroutines.flow.Flow".toSymbol()
            val flowOf = "kotlinx.coroutines.flow.flowOf".toSymbol()
            val merge = "kotlinx.coroutines.flow.merge".toSymbol()
            val map = "kotlinx.coroutines.flow.map".toSymbol()
            val take = "kotlinx.coroutines.flow.take".toSymbol()
            val single = "kotlinx.coroutines.flow.single".toSymbol()
        }
    }

    object HttpClientEngines {
        object Default : RuntimeTypePackage(KotlinDependency.DEFAULT_HTTP_ENGINE) {
            val DefaultHttpEngine = symbol("DefaultHttpEngine")
            val HttpEngineConfigImpl = symbol("HttpEngineConfigImpl")
        }
    }

    object AwsProtocolCore : RuntimeTypePackage(KotlinDependency.AWS_PROTOCOL_CORE) {
        val withPayload = symbol("withPayload")
        val setAseErrorMetadata = symbol("setAseErrorMetadata")
        val AwsAttributes = symbol("AwsAttributes")
        val AwsQueryCompatibleErrorDetails = symbol("AwsQueryCompatibleErrorDetails")
        val setAwsQueryCompatibleErrorMetadata = symbol("setAwsQueryCompatibleErrorMetadata")
        val XAmznQueryErrorHeader = symbol("X_AMZN_QUERY_ERROR_HEADER")
        val ClockSkewInterceptor = symbol("ClockSkewInterceptor")
    }

    object AwsJsonProtocols : RuntimeTypePackage(KotlinDependency.AWS_JSON_PROTOCOLS) {
        val AwsJsonProtocol = symbol("AwsJsonProtocol")
        val RestJsonErrorDeserializer = symbol("RestJsonErrorDeserializer")
    }
    object AwsXmlProtocols : RuntimeTypePackage(KotlinDependency.AWS_XML_PROTOCOLS) {
        val parseRestXmlErrorResponseNoSuspend = symbol("parseRestXmlErrorResponseNoSuspend")
        val parseEc2QueryErrorResponseNoSuspend = symbol("parseEc2QueryErrorResponseNoSuspend")
    }

    object AwsEventStream : RuntimeTypePackage(KotlinDependency.AWS_EVENT_STREAM) {
        val HeaderValue = symbol("HeaderValue")
        val Message = symbol("Message")
        val MessageType = symbol("MessageType")
        val MessageTypeExt = symbol("type")

        val asEventStreamHttpBody = symbol("asEventStreamHttpBody")
        val buildMessage = symbol("buildMessage")
        val decodeFrames = symbol("decodeFrames")
        val encode = symbol("encode")

        val expectBool = symbol("expectBool")
        val expectByte = symbol("expectByte")
        val expectByteArray = symbol("expectByteArray")
        val expectInt16 = symbol("expectInt16")
        val expectInt32 = symbol("expectInt32")
        val expectInt64 = symbol("expectInt64")
        val expectTimestamp = symbol("expectTimestamp")
        val expectString = symbol("expectString")

        val sign = symbol("sign")
    }
}
