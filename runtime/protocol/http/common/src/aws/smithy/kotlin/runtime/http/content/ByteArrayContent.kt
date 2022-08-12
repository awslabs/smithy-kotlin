/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.http.content

import aws.smithy.kotlin.runtime.http.HttpBody

/**
 * Implementation of [HttpBody.Bytes] backed by a byte array
 */
public class ByteArrayContent(private val bytes: ByteArray) : HttpBody.Bytes() {
    override val contentLength: Long = bytes.size.toLong()
    override fun bytes(): ByteArray = bytes
}
