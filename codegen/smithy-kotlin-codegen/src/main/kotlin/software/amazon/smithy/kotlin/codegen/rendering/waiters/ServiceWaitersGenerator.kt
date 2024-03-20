/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.kotlin.codegen.rendering.waiters

import software.amazon.smithy.kotlin.codegen.KotlinSettings
import software.amazon.smithy.kotlin.codegen.core.*
import software.amazon.smithy.kotlin.codegen.integration.KotlinIntegration
import software.amazon.smithy.kotlin.codegen.model.expectShape
import software.amazon.smithy.kotlin.codegen.model.getTrait
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.TopDownIndex
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.waiters.*

/**
 * A [KotlinIntegration] that generates the waiters for a service.
 */
class ServiceWaitersGenerator : KotlinIntegration {
    override fun enabledForService(model: Model, settings: KotlinSettings): Boolean =
        TopDownIndex.of(model)
            .getContainedOperations(settings.service)
            .any { it.waitableTrait != null }

    override fun writeAdditionalFiles(ctx: CodegenContext, delegator: KotlinDelegator) {
        delegator.useFileWriter("Waiters.kt", "${ctx.settings.pkg.name}.waiters") { writer ->
            ctx.allWaiters().forEach(writer::renderWaiter)
        }
    }
}

/**
 * Gets all the waiters in this [CodegenContext].
 * @return A list of [WaiterInfo] objects.
 */
internal fun CodegenContext.allWaiters(): List<WaiterInfo> {
    val service = model.expectShape<ServiceShape>(settings.service)

    fun operationWaiters(op: OperationShape): List<WaiterInfo> =
        op.waitableTrait?.waiters?.map { (name, waiter) ->
            WaiterInfo(this, service, op, name, waiter)
        } ?: listOf()

    return service
        .allOperations
        .map { model.expectShape<OperationShape>(it) }
        .flatMap(::operationWaiters)
}

private val OperationShape.waitableTrait: WaitableTrait?
    get() = getTrait()
