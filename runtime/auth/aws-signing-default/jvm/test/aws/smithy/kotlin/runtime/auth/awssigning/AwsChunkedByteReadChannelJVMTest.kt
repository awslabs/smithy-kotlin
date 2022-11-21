/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.auth.awssigning

import aws.smithy.kotlin.runtime.auth.awssigning.tests.AwsChunkedByteReadChannelJVMTestBase

class AwsChunkedByteReadChannelJVMTest : AwsChunkedByteReadChannelJVMTestBase() {
    override val signer: AwsSigner = DefaultAwsSigner
}
