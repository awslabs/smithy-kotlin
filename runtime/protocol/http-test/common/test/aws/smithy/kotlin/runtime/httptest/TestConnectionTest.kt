/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package aws.smithy.kotlin.runtime.httptest

import aws.smithy.kotlin.runtime.http.content.ByteArrayContent
import aws.smithy.kotlin.runtime.http.request.HttpRequestBuilder
import aws.smithy.kotlin.runtime.http.response.complete
import aws.smithy.kotlin.runtime.http.sdkHttpClient
import aws.smithy.kotlin.runtime.testing.runSuspendTest
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertFails

class TestConnectionTest {
    @Test
    fun testAssertRequestsSuccess() = runSuspendTest {
        val engine = buildTestConnection {
            expect {
                request {
                    url.host = "test.com"
                    url.path = "/turtles-all-the-way-down"
                    headers.append("x-foo", "bar")
                    body = ByteArrayContent("tests for your tests".encodeToByteArray())
                }
            }
        }

        val client = sdkHttpClient(engine)

        val req = HttpRequestBuilder().apply {
            url.host = "test.com"
            url.path = "/turtles-all-the-way-down"
            headers.append("x-foo", "bar")
            headers.append("x-qux", "quux")
            body = ByteArrayContent("tests for your tests".encodeToByteArray())
        }
        client.call(req).complete()

        engine.assertRequests()
    }

    @Test
    fun testAssertRequestsUrlDifferent() = runSuspendTest {
        val engine = buildTestConnection {
            expect {
                request {
                    url.host = "test.com"
                    url.path = "/turtles-all-the-way-down"
                    headers.append("x-foo", "bar")
                    body = ByteArrayContent("tests for your tests".encodeToByteArray())
                }
            }
        }

        val client = sdkHttpClient(engine)

        val req = HttpRequestBuilder().apply {
            url.host = "test.com"
            url.path = "/tests-for-your-tests"
            headers.append("x-foo", "bar")
        }
        client.call(req).complete()

        assertFails {
            engine.assertRequests()
        }.message.shouldContain("URL mismatch")
    }

    @Test
    fun testAssertRequestsMissingHeader() = runSuspendTest {
        val engine = buildTestConnection {
            expect {
                request {
                    url.host = "test.com"
                    url.path = "/turtles-all-the-way-down"
                    headers.append("x-foo", "bar")
                    headers.append("x-baz", "qux")
                }
            }
        }

        val client = sdkHttpClient(engine)

        val req = HttpRequestBuilder().apply {
            url.host = "test.com"
            url.path = "/tests-for-your-tests"
            headers.append("x-foo", "bar")
        }
        client.call(req).complete()

        assertFails {
            engine.assertRequests()
        }.message.shouldContain("header x-baz missing value qux")
    }

    @Test
    fun testAssertRequestsBodyDifferent() = runSuspendTest {
        val engine = buildTestConnection {
            expect {
                request {
                    url.host = "test.com"
                    url.path = "/turtles-all-the-way-down"
                    headers.append("x-foo", "bar")
                    body = ByteArrayContent("tests for your tests".encodeToByteArray())
                }
            }
        }

        val client = sdkHttpClient(engine)

        val req = HttpRequestBuilder().apply {
            url.host = "test.com"
            url.path = "/tests-for-your-tests"
            headers.append("x-foo", "bar")
            body = ByteArrayContent("tests are good".encodeToByteArray())
        }
        client.call(req).complete()

        assertFails {
            engine.assertRequests()
        }.message.shouldContain("body mismatch")
    }
}
